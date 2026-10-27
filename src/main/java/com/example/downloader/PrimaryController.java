package com.example.downloader;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class PrimaryController {

   @FXML
   private TextField urlField;
   @FXML
   private Button downloadButton;
   @FXML
   private Label statusLabel;

   @FXML
   private void handleDownloadButtonAction() {
      String urlString = urlField.getText().trim();
      if (urlString.isEmpty()) {
         statusLabel.setText("Error: URL tidak boleh kosong!");
         return;
      }

      URI uri;
      try {
         uri = URI.create(urlString);
         if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            statusLabel.setText("Error: Hanya URL http/https yang didukung.");
            return;
         }
      } catch (IllegalArgumentException e) {
         statusLabel.setText("Error: Format URL tidak valid!");
         return;
      }

      File destinationFile = askForDestination(urlString);
      if (destinationFile != null) {
         startDownload(urlString, destinationFile);
      } else {
         statusLabel.setText("Status: Download dibatalkan.");
      }
   }

   private File askForDestination(String urlString) {
      FileChooser fileChooser = new FileChooser();
      fileChooser.setTitle("Simpan File Sebagai");
      fileChooser.setInitialFileName(extractFileNameFromUrl(urlString));
      return fileChooser.showSaveDialog(downloadButton.getScene().getWindow());
   }

   private String extractFileNameFromUrl(String urlString) {
      String path = URI.create(urlString).getPath();
      if (path == null || path.isEmpty() || path.endsWith("/")) {
         return "downloaded_file";
      }
      String name = path.substring(path.lastIndexOf('/') + 1);
      return name.isEmpty() ? "downloaded_file" : name;
   }

   private void startDownload(String urlString, File destinationFile) {
      downloadButton.setDisable(true);

      Task<Void> downloadTask = new Task<>() {
         @Override
         protected Void call() throws Exception {
            // 1. HttpClient dengan followRedirects ALWAYS
            HttpClient client = HttpClient.newBuilder()
                  .followRedirects(HttpClient.Redirect.ALWAYS)
                  .build();

            // 2. Request dengan header ala browser
            HttpRequest request = HttpRequest.newBuilder()
                  .uri(URI.create(urlString))
                  .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                  .header("Accept", "*/*")
                  .GET()
                  .build();

            updateMessage("Status: Menghubungi server...");
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status != 200) {
               throw new RuntimeException(
                     "Server merespons HTTP " + status + ". URL mungkin bukan link file langsung.");
            }

            // 3. Validasi Content-Type: tolak jika HTML
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
            if (contentType.startsWith("text/html")) {
               throw new RuntimeException(
                     "URL mengembalikan halaman HTML, bukan file. Gunakan link 'Copy link download' dari browser.");
            }

            // 4. Ambil ukuran file dari Content-Length (bisa -1 jika server tidak memberi tahu)
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);

            try (InputStream in = response.body();
                  OutputStream out = new FileOutputStream(destinationFile)) {

               byte[] buffer = new byte[8192];
               int bytesRead;
               long totalBytesRead = 0;
               int counter = 0;

               while ((bytesRead = in.read(buffer)) != -1) {
                  out.write(buffer, 0, bytesRead);
                  totalBytesRead += bytesRead;

                  // Update UI setiap ~100 iterasi agar tidak flooding
                  if (++counter % 100 == 0) {
                     String msg = contentLength > 0
                           ? String.format("Mengunduh... %.1f%% (%.2f MB / %.2f MB)",
                                 totalBytesRead * 100.0 / contentLength,
                                 totalBytesRead / 1024.0 / 1024.0,
                                 contentLength / 1024.0 / 1024.0)
                           : String.format("Mengunduh... %.2f MB (ukuran tidak diketahui)",
                                 totalBytesRead / 1024.0 / 1024.0);
                     updateMessage("Status: " + msg);
                  }
               }
            }
            return null;
         }

         @Override
         protected void succeeded() {
            statusLabel.textProperty().unbind();
            statusLabel.setText("Status: Selesai! Disimpan sebagai " + destinationFile.getName());
            downloadButton.setDisable(false);
         }

         @Override
         protected void failed() {
            Throwable ex = getException();
            statusLabel.textProperty().unbind();
            statusLabel.setText("Status: Gagal! " + ex.getMessage());
            downloadButton.setDisable(false);
            ex.printStackTrace();
         }
      };

      // Binding agar label otomatis update dari background thread
      statusLabel.textProperty().bind(downloadTask.messageProperty());

      Thread thread = new Thread(downloadTask);
      thread.setDaemon(true);
      thread.start();
   }
}