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
import java.util.Optional;

public class PrimaryController {

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

   // Konteks download yang sedang aktif
   private String currentUrl;
   private File currentFile;
   private long totalBytes = -1;
   private long bytesDownloaded = 0;
   private boolean supportsRange = false;

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
         startDownload(true); // resume
      }
   }

   @FXML
   private void handleCancelButtonAction() {
      if (state == State.PAUSED) {
         // Tidak ada task aktif — langsung bersihkan
         deletePartialFile();
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
   // PROBE (metadata saja — body langsung ditutup)
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
                  .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                  .header("Accept", "*/*")
                  .GET()
                  .build();

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

            // Body tidak diperlukan — tutup segera
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

         if (!supportsRange) {
            System.out.println("WARNING: Server tidak mendukung Range. Resume tidak akan bekerja.");
         }

         File destination = showSaveDialog(r.filename);
         if (destination == null) {
            statusLabel.setText("Status: Download dibatalkan.");
            state = State.IDLE;
            updateButtonState();
            return;
         }
         currentFile = destination;
         bytesDownloaded = 0;
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
   // DOWNLOAD (fresh atau resume)
   // ================================================================
   private void startDownload(boolean isResume) {
      state = State.DOWNLOADING;
      pauseRequested = false;
      cancelRequested = false;
      updateButtonState();

      long startOffset = isResume ? bytesDownloaded : 0;
      activeTask = new DownloadTask(startOffset);

      progressBar.progressProperty().bind(activeTask.progressProperty());
      statusLabel.textProperty().bind(activeTask.messageProperty());
      speedLabel.textProperty().bind(activeTask.speedTextProperty());
      etaLabel.textProperty().bind(activeTask.etaTextProperty());

      activeTask.setOnSucceeded(e -> {
         unbindAfterTask();
         if (cancelRequested) {
            deletePartialFile();
            resetToIdle();
            statusLabel.setText("Status: Download dibatalkan.");
         } else if (pauseRequested) {
            state = State.PAUSED;
            updateButtonState();
            if (totalBytes > 0) {
               statusLabel.setText(String.format("Status: Dijeda di %.1f%% (%s / %s)",
                     bytesDownloaded * 100.0 / totalBytes,
                     formatBytes(bytesDownloaded), formatBytes(totalBytes)));
            } else {
               statusLabel.setText("Status: Dijeda di " + formatBytes(bytesDownloaded));
            }
            speedLabel.setText("Kecepatan: -");
            etaLabel.setText("ETA: -");
         } else {
            state = State.IDLE;
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
   // DOWNLOAD TASK — mendukung Range resume
   // ================================================================
   private class DownloadTask extends Task<Void> {

      private final long startOffset;
      private final ReadOnlyStringWrapper speedText = new ReadOnlyStringWrapper("-");
      private final ReadOnlyStringWrapper etaText = new ReadOnlyStringWrapper("-");

      DownloadTask(long startOffset) {
         this.startOffset = startOffset;
      }

      public ReadOnlyStringProperty speedTextProperty() {
         return speedText.getReadOnlyProperty();
      }

      public ReadOnlyStringProperty etaTextProperty() {
         return etaText.getReadOnlyProperty();
      }

      @Override
      protected Void call() throws Exception {
         updateMessage(startOffset > 0 ? "Status: Melanjutkan unduhan..." : "Status: Mengunduh...");

         HttpClient client = HttpClient.newBuilder()
               .followRedirects(HttpClient.Redirect.ALWAYS)
               .build();

         long actualStart = startOffset;
         HttpResponse<InputStream> response = fetchResponse(client, actualStart);
         int status = response.statusCode();

         // Handle resume: expect 206. If 200, server ignored Range → restart dari 0
         if (actualStart > 0) {
            if (status == 200) {
               try {
                  response.body().close();
               } catch (Exception ignored) {
               }
               updateMessage("Status: Server tidak mendukung resume, restart dari awal...");
               actualStart = 0;
               bytesDownloaded = 0;
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

         // Jika totalBytes belum diketahui, coba parse dari Content-Range (206)
         if (totalBytes <= 0) {
            String contentRange = response.headers().firstValue("Content-Range").orElse("");
            int slash = contentRange.indexOf('/');
            if (slash > 0) {
               try {
                  totalBytes = Long.parseLong(contentRange.substring(slash + 1));
               } catch (NumberFormatException ignored) {
               }
            }
         }

         try (InputStream in = response.body();
               RandomAccessFile raf = new RandomAccessFile(currentFile, "rw")) {

            if (actualStart == 0) {
               raf.setLength(0); // truncate (fresh start atau restart)
            }
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
               bytesDownloaded = total;

               long now = System.nanoTime();
               if (now - lastUiUpdate >= 200_000_000L) {
                  if (totalBytes > 0) {
                     updateProgress(total, totalBytes);
                  } else {
                     updateProgress(-1, 1); // indeterminate
                  }
                  double elapsed = (now - lastSampleTime) / 1_000_000_000.0;
                  long delta = total - lastSampleBytes;
                  double speedBps = elapsed > 0 ? delta / elapsed : 0;
                  String s = formatSpeed(speedBps);
                  String eta = (totalBytes > 0)
                        ? formatEta(totalBytes - total, speedBps)
                        : "tidak diketahui";
                  Platform.runLater(() -> {
                     speedText.set(s);
                     etaText.set(eta);
                  });
                  lastSampleTime = now;
                  lastSampleBytes = total;
                  lastUiUpdate = now;
               }
            }

            if (pauseRequested && !cancelRequested) {
               updateMessage("Status: Dijeda");
            }
         }
         return null;
      }

      private HttpResponse<InputStream> fetchResponse(HttpClient client, long offset) throws Exception {
         HttpRequest.Builder b = HttpRequest.newBuilder()
               .uri(URI.create(currentUrl))
               .header("User-Agent",
                     "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
               .header("Accept", "*/*");
         if (offset > 0) {
            b.header("Range", "bytes=" + offset + "-");
         }
         return client.send(b.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
      }
   }

   // ================================================================
   // HELPERS
   // ================================================================
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
      bytesDownloaded = 0;
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
      if (currentFile != null && currentFile.exists()) {
         if (!currentFile.delete()) {
            System.err.println("Gagal hapus file partial: " + currentFile.getAbsolutePath());
         }
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
   // Deteksi nama file (tidak berubah dari Iterasi 1.5)
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
      switch (mime) {
         case "application/pdf":
            return ".pdf";
         case "application/zip":
            return ".zip";
         case "application/x-7z-compressed":
            return ".7z";
         case "application/x-rar-compressed":
         case "application/vnd.rar":
            return ".rar";
         case "application/x-tar":
            return ".tar";
         case "application/gzip":
            return ".gz";
         case "application/json":
            return ".json";
         case "application/xml":
            return ".xml";
         case "application/msword":
            return ".doc";
         case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
            return ".docx";
         case "application/vnd.ms-excel":
            return ".xls";
         case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
            return ".xlsx";
         case "application/vnd.ms-powerpoint":
            return ".ppt";
         case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
            return ".pptx";
         case "text/plain":
            return ".txt";
         case "text/csv":
            return ".csv";
         case "image/jpeg":
            return ".jpg";
         case "image/png":
            return ".png";
         case "image/gif":
            return ".gif";
         case "image/webp":
            return ".webp";
         case "image/svg+xml":
            return ".svg";
         case "audio/mpeg":
            return ".mp3";
         case "audio/ogg":
            return ".ogg";
         case "audio/wav":
            return ".wav";
         case "video/mp4":
            return ".mp4";
         case "video/webm":
            return ".webm";
         case "video/x-matroska":
            return ".mkv";
         default:
            if (mime.contains("/")) {
               String subtype = mime.substring(mime.indexOf('/') + 1);
               if (subtype.matches("[a-z0-9]{1,5}"))
                  return "." + subtype;
            }
            return null;
      }
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