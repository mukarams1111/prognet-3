package com.example.downloader;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class PrimaryController {

   private static final int NUM_CONNECTIONS = 4;
   private static final long MIN_CHUNK_SIZE = 1024 * 1024; // 1 MB per segmen minimum
   private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
         "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

   @FXML
   private TextField urlField;
   @FXML
   private Button downloadButton;
   @FXML
   private Button pauseResumeButton;
   @FXML
   private Button cancelButton;
   @FXML
   private Label statusLabel;
   @FXML
   private ProgressBar progressBar;
   @FXML
   private Label speedLabel;
   @FXML
   private Label etaLabel;

   // ================================================================
   // STATE MACHINE
   // ================================================================
   private enum State {
      IDLE, PROBING, DOWNLOADING, PAUSED
   }

   private State state = State.IDLE;

   private String currentUrl;
   private File currentFile;
   private long totalBytes = -1;
   private final AtomicLong bytesDownloaded = new AtomicLong(0);
   private boolean supportsRange = false;

   // Jika tidak null → pakai multi-connection. Jika null → single-connection.
   private List<Segment> currentSegments;

   private DownloadTask activeTask;
   private volatile boolean pauseRequested = false;
   private volatile boolean cancelRequested = false;

   // ================================================================
   // EVENT HANDLERS
   // ================================================================
   @FXML
   private void handleDownloadButtonAction() {
      if (state != State.IDLE)
         return;
      String urlString = urlField.getText().trim();
      if (urlString.isEmpty()) {
         statusLabel.setText("Error: URL tidak boleh kosong!");
         return;
      }
      try {
         URI uri = URI.create(urlString);
         if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            statusLabel.setText("Error: Hanya URL http/https yang didukung.");
            return;
         }
      } catch (IllegalArgumentException e) {
         statusLabel.setText("Error: Format URL tidak valid!");
         return;
      }
      currentUrl = urlString;
      startProbe();
   }

   @FXML
   private void handlePauseResumeButtonAction() {
      if (state == State.DOWNLOADING) {
         pauseRequested = true;
         pauseResumeButton.setDisable(true);
         statusLabel.textProperty().unbind();
         statusLabel.setText("Status: Menghentikan sementara...");
      } else if (state == State.PAUSED) {
         startDownload(true);
      }
   }

   @FXML
   private void handleCancelButtonAction() {
      if (state == State.PAUSED) {
         deletePartialFile();
         currentSegments = null;
         resetToIdle();
         statusLabel.setText("Status: Download dibatalkan.");
      } else if (state == State.DOWNLOADING) {
         cancelRequested = true;
         pauseRequested = true;
         pauseResumeButton.setDisable(true);
         cancelButton.setDisable(true);
         statusLabel.textProperty().unbind();
         statusLabel.setText("Status: Membatalkan...");
      }
   }

   // ================================================================
   // PROBE
   // ================================================================
   private void startProbe() {
      state = State.PROBING;
      updateButtonState();
      progressBar.setProgress(0);
      speedLabel.setText("Kecepatan: -");
      etaLabel.setText("ETA: -");

      Task<ProbeResult> probeTask = new Task<>() {
         @Override
         protected ProbeResult call() throws Exception {
            updateMessage("Status: Menghubungi server...");
            HttpClient client = HttpClient.newBuilder()
                  .followRedirects(HttpClient.Redirect.ALWAYS)
                  .build();
            HttpRequest request = HttpRequest.newBuilder()
                  .uri(URI.create(currentUrl))
                  .header("User-Agent", UA)
                  .header("Accept", "*/*")
                  .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
               try {
                  response.body().close();
               } catch (Exception ignored) {
               }
               throw new RuntimeException("Server merespons HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
            if (contentType.startsWith("text/html")) {
               try {
                  response.body().close();
               } catch (Exception ignored) {
               }
               throw new RuntimeException("URL mengembalikan halaman HTML, bukan file.");
            }
            try {
               response.body().close();
            } catch (Exception ignored) {
            }

            long size = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            boolean rangeOk = "bytes".equalsIgnoreCase(
                  response.headers().firstValue("Accept-Ranges").orElse(""));
            String filename = detectFilename(currentUrl, response);
            return new ProbeResult(size, rangeOk, filename);
         }
      };

      statusLabel.textProperty().bind(probeTask.messageProperty());

      probeTask.setOnSucceeded(e -> {
         statusLabel.textProperty().unbind();
         ProbeResult r = probeTask.getValue();
         totalBytes = r.size;
         supportsRange = r.supportsRange;

         File destination = showSaveDialog(r.filename);
         if (destination == null) {
            statusLabel.setText("Status: Download dibatalkan.");
            state = State.IDLE;
            updateButtonState();
            return;
         }
         currentFile = destination;
         bytesDownloaded.set(0);
         currentSegments = canUseMultiConnection() ? buildSegments() : null;

         if (currentSegments != null) {
            System.out.printf("Multi-connection mode: %d segments of ~%d bytes%n",
                  NUM_CONNECTIONS, totalBytes / NUM_CONNECTIONS);
         } else {
            System.out.println("Single-connection mode (" +
                  (!supportsRange ? "no Range support" : "file too small") + ")");
         }

         startDownload(false);
      });

      probeTask.setOnFailed(e -> {
         statusLabel.textProperty().unbind();
         Throwable ex = probeTask.getException();
         statusLabel.setText("Status: Gagal! " + (ex != null ? ex.getMessage() : "unknown"));
         state = State.IDLE;
         updateButtonState();
         if (ex != null)
            ex.printStackTrace();
      });

      Thread t = new Thread(probeTask);
      t.setDaemon(true);
      t.start();
   }

   // ================================================================
   // DOWNLOAD DISPATCHER
   // ================================================================
   private void startDownload(boolean isResume) {
      state = State.DOWNLOADING;
      pauseRequested = false;
      cancelRequested = false;

      // Re-sum progress dari segmen (lebih akurat dari bytesDownloaded yang mungkin drift)
      if (isResume && currentSegments != null) {
         long sum = currentSegments.stream()
               .mapToLong(s -> s.currentOffset - s.start)
               .sum();
         bytesDownloaded.set(sum);
      }

      updateButtonState();

      activeTask = new DownloadTask(isResume);

      progressBar.progressProperty().bind(activeTask.progressProperty());
      statusLabel.textProperty().bind(activeTask.messageProperty());
      speedLabel.textProperty().bind(activeTask.speedTextProperty());
      etaLabel.textProperty().bind(activeTask.etaTextProperty());

      activeTask.setOnSucceeded(e -> {
         unbindAfterTask();
         if (cancelRequested) {
            deletePartialFile();
            currentSegments = null;
            resetToIdle();
            statusLabel.setText("Status: Download dibatalkan.");
         } else if (pauseRequested) {
            state = State.PAUSED;
            updateButtonState();
            if (totalBytes > 0) {
               statusLabel.setText(String.format("Status: Dijeda di %.1f%% (%s / %s)",
                     bytesDownloaded.get() * 100.0 / totalBytes,
                     formatBytes(bytesDownloaded.get()), formatBytes(totalBytes)));
            } else {
               statusLabel.setText("Status: Dijeda di " + formatBytes(bytesDownloaded.get()));
            }
            speedLabel.setText("Kecepatan: -");
            etaLabel.setText("ETA: -");
         } else {
            state = State.IDLE;
            currentSegments = null;
            progressBar.setProgress(1.0);
            statusLabel.setText("Status: Selesai! Disimpan sebagai " + currentFile.getName());
            speedLabel.setText("Kecepatan: -");
            etaLabel.setText("ETA: -");
            updateButtonState();
         }
         activeTask = null;
      });

      activeTask.setOnFailed(e -> {
         unbindAfterTask();
         Throwable ex = activeTask.getException();
         statusLabel.setText("Status: Gagal! " + (ex != null ? ex.getMessage() : "unknown"));
         speedLabel.setText("Kecepatan: -");
         etaLabel.setText("ETA: -");
         currentSegments = null;
         state = State.IDLE;
         updateButtonState();
         if (ex != null)
            ex.printStackTrace();
         activeTask = null;
      });

      Thread t = new Thread(activeTask);
      t.setDaemon(true);
      t.start();
   }

   // ================================================================
   // TASK — dispatch ke single atau multi
   // ================================================================
   private class DownloadTask extends Task<Void> {

      private final ReadOnlyStringWrapper speedText = new ReadOnlyStringWrapper("-");
      private final ReadOnlyStringWrapper etaText = new ReadOnlyStringWrapper("-");

      DownloadTask(boolean isResume) {
      }

      public ReadOnlyStringProperty speedTextProperty() {
         return speedText.getReadOnlyProperty();
      }

      public ReadOnlyStringProperty etaTextProperty() {
         return etaText.getReadOnlyProperty();
      }

      @Override
      protected Void call() throws Exception {
         if (currentSegments != null) {
            return downloadMulti();
         } else {
            return downloadSingle();
         }
      }

      // ------------------------------------------------------------
      // MULTI-CONNECTION
      // ------------------------------------------------------------
      private Void downloadMulti() throws Exception {
         updateMessage("Status: Mengunduh (" + NUM_CONNECTIONS + " koneksi paralel)...");

         HttpClient client = HttpClient.newBuilder()
               .followRedirects(HttpClient.Redirect.ALWAYS)
               .version(HttpClient.Version.HTTP_1_1) // paksa 1.1 → parallel TCP
               .build();

         try (RandomAccessFile raf = new RandomAccessFile(currentFile, "rw")) {
            if (bytesDownloaded.get() == 0) {
               raf.setLength(totalBytes); // pre-allocate untuk fresh start
            }
            FileChannel channel = raf.getChannel();

            ExecutorService executor = Executors.newFixedThreadPool(NUM_CONNECTIONS);
            List<Future<Void>> futures = new ArrayList<>();
            for (Segment seg : currentSegments) {
               futures.add(executor.submit(() -> downloadSegment(client, channel, seg)));
            }

            Thread monitor = new Thread(this::monitorProgress, "progress-monitor");
            monitor.setDaemon(true);
            monitor.start();

            try {
               for (Future<Void> f : futures)
                  f.get();
            } catch (Exception e) {
               cancelRequested = true;
               throw e;
            } finally {
               executor.shutdownNow();
               monitor.interrupt();
            }
         }
         return null;
      }

      private Void downloadSegment(HttpClient client, FileChannel channel, Segment seg) throws Exception {
         int retries = 0;
         while (!seg.isComplete()) {
            if (pauseRequested || cancelRequested)
               return null;
            if (retries > 3) {
               throw new RuntimeException("Segment " + seg.id + " gagal setelah 3 retry");
            }

            try {
               HttpRequest req = HttpRequest.newBuilder()
                     .uri(URI.create(currentUrl))
                     .header("User-Agent", UA)
                     .header("Accept", "*/*")
                     .header("Range", "bytes=" + seg.currentOffset + "-" + seg.end)
                     .GET().build();

               HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
               int code = resp.statusCode();

               if (code == 200) {
                  try {
                     resp.body().close();
                  } catch (Exception ignored) {
                  }
                  throw new RuntimeException("Server mengabaikan Range (200), multi-connection tidak bisa dilanjutkan");
               }
               if (code != 206) {
                  try {
                     resp.body().close();
                  } catch (Exception ignored) {
                  }
                  throw new RuntimeException("Segment " + seg.id + " HTTP " + code);
               }

               try (InputStream in = resp.body()) {
                  byte[] buffer = new byte[65536];
                  int n;
                  while ((n = in.read(buffer)) != -1) {
                     if (pauseRequested || cancelRequested)
                        return null;
                     channel.write(ByteBuffer.wrap(buffer, 0, n), seg.currentOffset);
                     seg.currentOffset += n;
                     bytesDownloaded.addAndGet(n);
                  }
               }
               retries = 0; // reset on success
            } catch (Exception e) {
               if (pauseRequested || cancelRequested)
                  return null;
               retries++;
               System.err.println("Segment " + seg.id + " error (retry " + retries + "): " + e.getMessage());
               Thread.sleep(1000L * retries);
            }
         }
         return null;
      }

      private void monitorProgress() {
         long lastTime = System.nanoTime();
         long lastBytes = bytesDownloaded.get();
         while (!pauseRequested && !cancelRequested) {
            try {
               Thread.sleep(200);
            } catch (InterruptedException e) {
               return;
            }

            long now = System.nanoTime();
            long current = bytesDownloaded.get();
            boolean allDone = currentSegments.stream().allMatch(Segment::isComplete);

            if (totalBytes > 0) {
               updateProgress(current, totalBytes);
            }

            double elapsed = (now - lastTime) / 1_000_000_000.0;
            long delta = current - lastBytes;
            double speedBps = elapsed > 0 ? delta / elapsed : 0;
            String s = formatSpeed(speedBps);
            String eta = totalBytes > 0 ? formatEta(totalBytes - current, speedBps) : "tidak diketahui";

            Platform.runLater(() -> {
               speedText.set(s);
               etaText.set(eta);
            });

            lastTime = now;
            lastBytes = current;

            if (allDone)
               break;
         }
      }

      // ------------------------------------------------------------
      // SINGLE-CONNECTION (fallback, sama seperti Iterasi 3)
      // ------------------------------------------------------------
      private Void downloadSingle() throws Exception {
         updateMessage(bytesDownloaded.get() > 0
               ? "Status: Melanjutkan unduhan (1 koneksi)..."
               : "Status: Mengunduh (1 koneksi)...");

         HttpClient client = HttpClient.newBuilder()
               .followRedirects(HttpClient.Redirect.ALWAYS)
               .build();

         long actualStart = bytesDownloaded.get();
         HttpResponse<InputStream> response = fetchResponse(client, actualStart);
         int status = response.statusCode();

         if (actualStart > 0) {
            if (status == 200) {
               try {
                  response.body().close();
               } catch (Exception ignored) {
               }
               updateMessage("Status: Server tidak mendukung resume, restart dari awal...");
               actualStart = 0;
               bytesDownloaded.set(0);
               response = fetchResponse(client, 0);
               status = response.statusCode();
               if (status != 200) {
                  response.body().close();
                  throw new RuntimeException("Server merespons HTTP " + status);
               }
            } else if (status != 206) {
               response.body().close();
               throw new RuntimeException("Server merespons HTTP " + status + " saat resume");
            }
         } else if (status != 200) {
            response.body().close();
            throw new RuntimeException("Server merespons HTTP " + status);
         }

         if (totalBytes <= 0) {
            String cr = response.headers().firstValue("Content-Range").orElse("");
            int slash = cr.indexOf('/');
            if (slash > 0) {
               try {
                  totalBytes = Long.parseLong(cr.substring(slash + 1));
               } catch (NumberFormatException ignored) {
               }
            }
         }

         try (InputStream in = response.body();
               RandomAccessFile raf = new RandomAccessFile(currentFile, "rw")) {

            if (actualStart == 0)
               raf.setLength(0);
            raf.seek(actualStart);

            byte[] buffer = new byte[65536];
            int n;
            long total = actualStart;
            long lastSampleTime = System.nanoTime();
            long lastSampleBytes = actualStart;
            long lastUiUpdate = 0;

            while ((n = in.read(buffer)) != -1) {
               if (pauseRequested || cancelRequested)
                  break;
               raf.write(buffer, 0, n);
               total += n;
               bytesDownloaded.set(total);

               long now = System.nanoTime();
               if (now - lastUiUpdate >= 200_000_000L) {
                  if (totalBytes > 0)
                     updateProgress(total, totalBytes);
                  else
                     updateProgress(-1, 1);

                  double elapsed = (now - lastSampleTime) / 1_000_000_000.0;
                  long delta = total - lastSampleBytes;
                  double speedBps = elapsed > 0 ? delta / elapsed : 0;
                  String s = formatSpeed(speedBps);
                  String eta = totalBytes > 0 ? formatEta(totalBytes - total, speedBps) : "tidak diketahui";
                  Platform.runLater(() -> {
                     speedText.set(s);
                     etaText.set(eta);
                  });
                  lastSampleTime = now;
                  lastSampleBytes = total;
                  lastUiUpdate = now;
               }
            }
            if (pauseRequested && !cancelRequested)
               updateMessage("Status: Dijeda");
         }
         return null;
      }

      private HttpResponse<InputStream> fetchResponse(HttpClient client, long offset) throws Exception {
         HttpRequest.Builder b = HttpRequest.newBuilder()
               .uri(URI.create(currentUrl))
               .header("User-Agent", UA)
               .header("Accept", "*/*");
         if (offset > 0)
            b.header("Range", "bytes=" + offset + "-");
         return client.send(b.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
      }
   }

   // ================================================================
   // SEGMENT
   // ================================================================
   private static class Segment {
      final int id;
      final long start;
      final long end;
      volatile long currentOffset;

      Segment(int id, long start, long end) {
         this.id = id;
         this.start = start;
         this.end = end;
         this.currentOffset = start;
      }

      boolean isComplete() {
         return currentOffset > end;
      }
   }

   // ================================================================
   // HELPERS
   // ================================================================
   private boolean canUseMultiConnection() {
      return supportsRange && totalBytes > 0 && totalBytes >= MIN_CHUNK_SIZE * NUM_CONNECTIONS;
   }

   private List<Segment> buildSegments() {
      List<Segment> segs = new ArrayList<>();
      long chunkSize = totalBytes / NUM_CONNECTIONS;
      for (int i = 0; i < NUM_CONNECTIONS; i++) {
         long start = i * chunkSize;
         long end = (i == NUM_CONNECTIONS - 1) ? totalBytes - 1 : (start + chunkSize - 1);
         segs.add(new Segment(i, start, end));
      }
      return segs;
   }

   private void updateButtonState() {
      switch (state) {
         case IDLE -> {
            downloadButton.setDisable(false);
            pauseResumeButton.setDisable(true);
            pauseResumeButton.setText("Pause");
            cancelButton.setDisable(true);
            urlField.setDisable(false);
         }
         case PROBING -> {
            downloadButton.setDisable(true);
            pauseResumeButton.setDisable(true);
            cancelButton.setDisable(true);
            urlField.setDisable(true);
         }
         case DOWNLOADING -> {
            downloadButton.setDisable(true);
            pauseResumeButton.setDisable(false);
            pauseResumeButton.setText("Pause");
            cancelButton.setDisable(false);
            urlField.setDisable(true);
         }
         case PAUSED -> {
            downloadButton.setDisable(true);
            pauseResumeButton.setDisable(false);
            pauseResumeButton.setText("Resume");
            cancelButton.setDisable(false);
            urlField.setDisable(true);
         }
      }
   }

   private void resetToIdle() {
      state = State.IDLE;
      currentUrl = null;
      currentFile = null;
      totalBytes = -1;
      bytesDownloaded.set(0);
      pauseRequested = false;
      cancelRequested = false;
      progressBar.setProgress(0);
      speedLabel.setText("Kecepatan: -");
      etaLabel.setText("ETA: -");
      updateButtonState();
   }

   private void unbindAfterTask() {
      progressBar.progressProperty().unbind();
      statusLabel.textProperty().unbind();
      speedLabel.textProperty().unbind();
      etaLabel.textProperty().unbind();
   }

   private void deletePartialFile() {
      if (currentFile != null && currentFile.exists() && !currentFile.delete()) {
         System.err.println("Gagal hapus file partial: " + currentFile.getAbsolutePath());
      }
   }

   private static String formatSpeed(double bps) {
      if (bps < 1024)
         return String.format("%.0f B/s", bps);
      if (bps < 1024 * 1024)
         return String.format("%.1f KB/s", bps / 1024.0);
      return String.format("%.2f MB/s", bps / 1024.0 / 1024.0);
   }

   private static String formatEta(long remainingBytes, double speedBps) {
      if (speedBps <= 0 || remainingBytes <= 0)
         return "∞";
      long seconds = (long) (remainingBytes / speedBps);
      if (seconds < 60)
         return seconds + "s";
      if (seconds < 3600)
         return String.format("%dm %ds", seconds / 60, seconds % 60);
      return String.format("%dh %dm", seconds / 3600, (seconds % 3600) / 60);
   }

   private static String formatBytes(long b) {
      if (b < 1024)
         return b + " B";
      if (b < 1024 * 1024)
         return String.format("%.1f KB", b / 1024.0);
      if (b < 1024L * 1024 * 1024)
         return String.format("%.1f MB", b / 1024.0 / 1024.0);
      return String.format("%.2f GB", b / 1024.0 / 1024.0 / 1024.0);
   }

   private record ProbeResult(long size, boolean supportsRange, String filename) {
   }

   // ================================================================
   // Deteksi nama file (tidak berubah)
   // ================================================================
   private String detectFilename(String urlString, HttpResponse<?> response) {
      Optional<String> disposition = response.headers().firstValue("Content-Disposition");
      if (disposition.isPresent()) {
         String name = parseFilenameFromDisposition(disposition.get());
         if (name != null && !name.isBlank())
            return sanitizeFilename(name);
      }
      String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
      String extFromMime = extensionFromMimeType(contentType);
      String baseName = extractBaseNameFromUrl(urlString);
      if (hasExtension(baseName))
         return baseName;
      if (extFromMime != null)
         return baseName + extFromMime;
      return baseName;
   }

   private String parseFilenameFromDisposition(String disposition) {
      String lower = disposition.toLowerCase();
      int idx = lower.indexOf("filename*=");
      if (idx >= 0) {
         String value = disposition.substring(idx + "filename*=".length()).trim();
         int semi = value.indexOf(';');
         if (semi >= 0)
            value = value.substring(0, semi);
         int q = value.indexOf("''");
         if (q >= 0) {
            try {
               return URLDecoder.decode(value.substring(q + 2), "UTF-8");
            } catch (Exception e) {
               return value.substring(q + 2);
            }
         }
         return value;
      }
      idx = lower.indexOf("filename=");
      if (idx >= 0) {
         String value = disposition.substring(idx + "filename=".length()).trim();
         if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            if (end > 0)
               return value.substring(1, end);
         } else {
            int semi = value.indexOf(';');
            if (semi >= 0)
               value = value.substring(0, semi);
            return value.trim();
         }
      }
      return null;
   }

   private String extensionFromMimeType(String contentType) {
        String mime = contentType.split(";")[0].trim();
        return switch (mime) {
            case "application/pdf" -> ".pdf";
            case "application/zip" -> ".zip";
            case "application/x-7z-compressed" -> ".7z";
            case "application/x-rar-compressed", "application/vnd.rar" -> ".rar";
            case "application/x-tar" -> ".tar";
            case "application/gzip" -> ".gz";
            case "application/json" -> ".json";
            case "application/xml" -> ".xml";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.ms-excel" -> ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "application/vnd.ms-powerpoint" -> ".ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "audio/mpeg" -> ".mp3";
            case "audio/ogg" -> ".ogg";
            case "audio/wav" -> ".wav";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/x-matroska" -> ".mkv";
            default -> {
                if (mime.contains("/")) {
                    String subtype = mime.substring(mime.indexOf('/') + 1);
                    if (subtype.matches("[a-z0-9]{1,5}")) yield "." + subtype;
                }
                yield null;
            }
        };
    }

   private String extractBaseNameFromUrl(String urlString) {
      try {
         String path = URI.create(urlString).getPath();
         if (path == null || path.isEmpty() || path.endsWith("/"))
            return "downloaded_file";
         String name = path.substring(path.lastIndexOf('/') + 1);
         return name.isEmpty() ? "downloaded_file" : sanitizeFilename(name);
      } catch (Exception e) {
         return "downloaded_file";
      }
   }

   private boolean hasExtension(String filename) {
      int dot = filename.lastIndexOf('.');
      if (dot <= 0 || dot == filename.length() - 1)
         return false;
      return filename.substring(dot + 1).matches("[A-Za-z0-9]{1,6}");
   }

   private String sanitizeFilename(String name) {
      return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
   }

   private File showSaveDialog(String suggestedName) {
      FileChooser fileChooser = new FileChooser();
      fileChooser.setTitle("Simpan File Sebagai");
      fileChooser.setInitialFileName(suggestedName);
      return fileChooser.showSaveDialog(downloadButton.getScene().getWindow());
   }
}