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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
   private Label statusLabel;
   @FXML
   private ProgressBar progressBar;
   @FXML
   private Label speedLabel;
   @FXML
   private Label etaLabel;

   private HttpResponse<InputStream> probedResponse;

   @FXML
   private void handleDownloadButtonAction() {
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
      startProbe(urlString);
   }

   // ================================================================
   // TAHAP PROBE (Iterasi 1) — tidak diubah, hanya UI reset
   // ================================================================
   private void startProbe(String urlString) {
      downloadButton.setDisable(true);
      resetUiForNewDownload();

      Task<HttpResponse<InputStream>> probeTask = new Task<>() {
         @Override
         protected HttpResponse<InputStream> call() throws Exception {
            updateMessage("Status: Menghubungi server...");

            HttpClient client = HttpClient.newBuilder()
                  .followRedirects(HttpClient.Redirect.ALWAYS)
                  .build();

            HttpRequest request = HttpRequest.newBuilder()
                  .uri(URI.create(urlString))
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
            return response;
         }
      };

      statusLabel.textProperty().bind(probeTask.messageProperty());

      probeTask.setOnSucceeded(e -> {
         statusLabel.textProperty().unbind();
         probedResponse = probeTask.getValue();

         String detectedName = detectFilename(urlString, probedResponse);
         statusLabel.setText("Status: Terdeteksi: " + detectedName);

         File destination = showSaveDialog(detectedName);
         if (destination != null) {
            startDownload(destination);
         } else {
            try {
               probedResponse.body().close();
            } catch (Exception ignored) {
            }
            probedResponse = null;
            statusLabel.setText("Status: Download dibatalkan.");
            downloadButton.setDisable(false);
         }
      });

      probeTask.setOnFailed(e -> {
         statusLabel.textProperty().unbind();
         statusLabel.setText("Status: Gagal! " + probeTask.getException().getMessage());
         downloadButton.setDisable(false);
         probeTask.getException().printStackTrace();
      });

      Thread t = new Thread(probeTask);
      t.setDaemon(true);
      t.start();
   }

   // ================================================================
   // TAHAP DOWNLOAD — Iterasi 2: progress + speed + ETA
   // ================================================================
   private void startDownload(File destinationFile) {
      if (probedResponse == null) {
         statusLabel.setText("Status: Error internal.");
         downloadButton.setDisable(false);
         return;
      }

      final HttpResponse<InputStream> response = probedResponse;
      probedResponse = null;

      DownloadTask task = new DownloadTask(response, destinationFile);

      // Bind properties Task → UI
      progressBar.progressProperty().bind(task.progressProperty());
      statusLabel.textProperty().bind(task.messageProperty());
      speedLabel.textProperty().bind(task.speedTextProperty());
      etaLabel.textProperty().bind(task.etaTextProperty());

      task.setOnSucceeded(e -> {
         unbindAfterTask();
         progressBar.setProgress(1.0);
         statusLabel.setText("Status: Selesai! Disimpan sebagai " + destinationFile.getName());
         speedLabel.setText("Kecepatan: -");
         etaLabel.setText("ETA: -");
         downloadButton.setDisable(false);
      });

      task.setOnFailed(e -> {
         unbindAfterTask();
         progressBar.setProgress(0);
         Throwable ex = task.getException();
         statusLabel.setText("Status: Gagal! " + (ex != null ? ex.getMessage() : "unknown"));
         speedLabel.setText("Kecepatan: -");
         etaLabel.setText("ETA: -");
         downloadButton.setDisable(false);
         if (ex != null)
            ex.printStackTrace();
      });

      Thread t = new Thread(task);
      t.setDaemon(true);
      t.start();
   }

   private void unbindAfterTask() {
      progressBar.progressProperty().unbind();
      statusLabel.textProperty().unbind();
      speedLabel.textProperty().unbind();
      etaLabel.textProperty().unbind();
   }

   private void resetUiForNewDownload() {
      progressBar.setProgress(0);
      speedLabel.setText("Kecepatan: -");
      etaLabel.setText("ETA: -");
   }

   // ================================================================
   // CUSTOM TASK — progress + speed + ETA
   // ================================================================
   private static class DownloadTask extends Task<Void> {

      private final HttpResponse<InputStream> response;
      private final File destinationFile;

      private final ReadOnlyStringWrapper speedText = new ReadOnlyStringWrapper("-");
      private final ReadOnlyStringWrapper etaText = new ReadOnlyStringWrapper("-");

      DownloadTask(HttpResponse<InputStream> response, File destinationFile) {
         this.response = response;
         this.destinationFile = destinationFile;
      }

      public ReadOnlyStringProperty speedTextProperty() {
         return speedText.getReadOnlyProperty();
      }

      public ReadOnlyStringProperty etaTextProperty() {
         return etaText.getReadOnlyProperty();
      }

      @Override
      protected Void call() throws Exception {
         long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
         boolean knownSize = contentLength > 0;

         updateMessage("Status: Mengunduh...");

         try (InputStream in = response.body();
               OutputStream out = new java.io.BufferedOutputStream(
                     new FileOutputStream(destinationFile), 65536)) { // 64 KB OS-level buffer
            byte[] buffer = new byte[65536]; // 64 KB in-JVM read buffer
            int bytesRead;
            long totalBytesRead = 0;

            // Sampling untuk speed/ETA
            long lastSampleTime = System.nanoTime();
            long lastSampleBytes = 0;
            long lastUiUpdate = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
               out.write(buffer, 0, bytesRead);
               totalBytesRead += bytesRead;

               long now = System.nanoTime();
               if (now - lastUiUpdate >= 200_000_000L) { // 200ms
                  // Progress (thread-safe via Task infrastructure)
                  if (knownSize) {
                     updateProgress(totalBytesRead, contentLength);
                  }

                  // Speed & ETA
                  double secondsElapsed = (now - lastSampleTime) / 1_000_000_000.0;
                  long bytesSinceLast = totalBytesRead - lastSampleBytes;
                  double speedBps = secondsElapsed > 0 ? bytesSinceLast / secondsElapsed : 0;

                  String speedStr = formatSpeed(speedBps);
                  String etaStr = knownSize
                        ? formatEta(contentLength - totalBytesRead, speedBps)
                        : "tidak diketahui";

                  final String speedSnapshot = speedStr;
                  final String etaSnapshot = etaStr;
                  Platform.runLater(() -> {
                     speedText.set(speedSnapshot);
                     etaText.set(etaSnapshot);
                  });

                  lastSampleTime = now;
                  lastSampleBytes = totalBytesRead;
                  lastUiUpdate = now;
               }
            }
         }
         return null;
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
   }

   // ================================================================
   // DETEKSI NAMA FILE (Iterasi 1) — tidak diubah
   // ================================================================
   private String detectFilename(String urlString, HttpResponse<?> response) {
      Optional<String> disposition = response.headers().firstValue("Content-Disposition");
      if (disposition.isPresent()) {
         String name = parseFilenameFromDisposition(disposition.get());
         if (name != null && !name.isBlank()) {
            return sanitizeFilename(name);
         }
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