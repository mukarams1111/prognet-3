package com.example.downloader;

import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PrimaryController {

   private static final int MAX_CONCURRENT_DOWNLOADS = 3;

   @FXML
   private TextField urlField;
   @FXML
   private Button addButton;
   @FXML
   private TableView<DownloadItem> downloadTable;
   @FXML
   private TableColumn<DownloadItem, String> nameColumn;
   @FXML
   private TableColumn<DownloadItem, String> statusColumn;
   @FXML
   private TableColumn<DownloadItem, DownloadItem> progressColumn;
   @FXML
   private TableColumn<DownloadItem, String> speedColumn;
   @FXML
   private TableColumn<DownloadItem, String> etaColumn;
   @FXML
   private TableColumn<DownloadItem, DownloadItem> actionColumn;

   private final ObservableList<DownloadItem> items = FXCollections.observableArrayList();
   private final ExecutorService downloadPool = Executors.newFixedThreadPool(
         MAX_CONCURRENT_DOWNLOADS,
         r -> {
            Thread t = new Thread(r, "download-worker");
            t.setDaemon(true); // ← restore: daemon = JVM bisa exit kapan saja
            return t;
         });
   private final HttpClient sharedClient = HttpClient.newBuilder()
         .followRedirects(HttpClient.Redirect.ALWAYS)
         .version(HttpClient.Version.HTTP_1_1)
         .build();

   @FXML
   public void initialize() {
      downloadTable.setItems(items);

      nameColumn.setCellValueFactory(cd -> cd.getValue().fileNameProperty());
      statusColumn.setCellValueFactory(cd -> cd.getValue().statusTextProperty());
      speedColumn.setCellValueFactory(cd -> cd.getValue().speedProperty());
      etaColumn.setCellValueFactory(cd -> cd.getValue().etaProperty());

      // Progress column
      progressColumn.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
      progressColumn.setCellFactory(col -> new TableCell<>() {
         private final ProgressBar pb = new ProgressBar(0);
         {
            pb.setPrefWidth(180);
            pb.setMaxWidth(Double.MAX_VALUE);
         }

         @Override
         protected void updateItem(DownloadItem it, boolean empty) {
            super.updateItem(it, empty);
            pb.progressProperty().unbind();
            if (empty || it == null) {
               setGraphic(null);
            } else {
               pb.progressProperty().bind(it.progressProperty());
               setGraphic(pb);
            }
         }
      });

      // Action column: Pause / Resume / Cancel / Remove (visibility by state)
      actionColumn.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
      actionColumn.setCellFactory(col -> new TableCell<>() {

         private final Button pauseBtn = new Button("Pause");
         private final Button resumeBtn = new Button("Resume");
         private final Button cancelBtn = new Button("Cancel");
         private final Button removeBtn = new Button("Remove");
         private final HBox box = new HBox(4, pauseBtn, resumeBtn, cancelBtn, removeBtn);

         {
            pauseBtn.setOnAction(e -> {
               DownloadItem it = currentItem();
               if (it != null)
                  it.requestPause();
            });
            resumeBtn.setOnAction(e -> {
               DownloadItem it = currentItem();
               if (it != null)
                  resumeItem(it);
            });
            cancelBtn.setOnAction(e -> {
               DownloadItem it = currentItem();
               if (it != null)
                  cancelItem(it);
            });
            removeBtn.setOnAction(e -> {
               DownloadItem it = currentItem();
               if (it != null)
                  removeItem(it);
            });
         }

         private DownloadItem currentItem() {
            int idx = getIndex();
            if (idx < 0 || idx >= getTableView().getItems().size())
               return null;
            return getTableView().getItems().get(idx);
         }

         @Override
         protected void updateItem(DownloadItem it, boolean empty) {
            super.updateItem(it, empty);

            // Unbind dulu semua (penting untuk cell recycling)
            pauseBtn.visibleProperty().unbind();
            pauseBtn.managedProperty().unbind();
            resumeBtn.visibleProperty().unbind();
            resumeBtn.managedProperty().unbind();
            cancelBtn.visibleProperty().unbind();
            cancelBtn.managedProperty().unbind();
            removeBtn.visibleProperty().unbind();
            removeBtn.managedProperty().unbind();

            if (empty || it == null) {
               setGraphic(null);
               return;
            }

            var state = it.stateProperty();

            pauseBtn.visibleProperty().bind(
                  state.isEqualTo(DownloadItem.State.DOWNLOADING));
            pauseBtn.managedProperty().bind(pauseBtn.visibleProperty());

            resumeBtn.visibleProperty().bind(
                  state.isEqualTo(DownloadItem.State.PAUSED)
                        .or(state.isEqualTo(DownloadItem.State.FAILED)));
            resumeBtn.managedProperty().bind(resumeBtn.visibleProperty());

            cancelBtn.visibleProperty().bind(
                  state.isEqualTo(DownloadItem.State.DOWNLOADING)
                        .or(state.isEqualTo(DownloadItem.State.PAUSED)));
            cancelBtn.managedProperty().bind(cancelBtn.visibleProperty());

            removeBtn.visibleProperty().bind(
                  state.isEqualTo(DownloadItem.State.CANCELLED)
                        .or(state.isEqualTo(DownloadItem.State.COMPLETED))
                        .or(state.isEqualTo(DownloadItem.State.FAILED)));
            removeBtn.managedProperty().bind(removeBtn.visibleProperty());

            setGraphic(box);
         }
      });

      // Context menu (klik kanan) — hanya di baris yang terisi
      downloadTable.setRowFactory(tv -> {
         TableRow<DownloadItem> row = new TableRow<>();
         ContextMenu cm = new ContextMenu();
         MenuItem removeMi = new MenuItem("Hapus dari daftar");
         removeMi.setOnAction(e -> {
            DownloadItem item = row.getItem();
            if (item != null)
               removeItem(item);
         });
         cm.getItems().add(removeMi);
         row.contextMenuProperty().bind(
               Bindings.when(row.emptyProperty())
                     .then((ContextMenu) null)
                     .otherwise(cm));
         return row;
      });

      // Shortcut: tekan Delete pada baris terpilih
      downloadTable.setOnKeyPressed(e -> {
         if (e.getCode() == KeyCode.DELETE) {
            DownloadItem sel = downloadTable.getSelectionModel().getSelectedItem();
            if (sel != null)
               removeItem(sel);
         }
      });
   }

   // ================================================================
   // ACTIONS
   // ================================================================
   @FXML
   private void handleAddDownload() {
      String url = urlField.getText().trim();
      if (url.isEmpty()) {
         showAlert("URL kosong", "Masukkan URL terlebih dahulu.");
         return;
      }
      try {
         URI uri = URI.create(url);
         if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            showAlert("URL tidak valid", "Hanya http/https yang didukung.");
            return;
         }
      } catch (IllegalArgumentException e) {
         showAlert("URL tidak valid", "Format URL salah.");
         return;
      }

      FileChooser fc = new FileChooser();
      fc.setTitle("Simpan File Sebagai");
      fc.setInitialFileName(extractFilenameFromUrl(url));
      File dest = fc.showSaveDialog(addButton.getScene().getWindow());
      if (dest == null)
         return;

      DownloadItem item = new DownloadItem(url, dest);
      items.add(item);
      urlField.clear();
      submitTask(item, false);
   }

   private void resumeItem(DownloadItem item) {
      item.prepareResume();
      submitTask(item, true);
   }

   private void cancelItem(DownloadItem item) {
      item.requestCancel();
      if (item.activeTask == null) {
         item.setStateSafe(DownloadItem.State.CANCELLED);
         deleteFile(item.getFile());
      }
   }

   /**
    * Remove dari daftar — hanya untuk state terminal.
    * File fisik TIDAK dihapus (untuk COMPLETED, file adalah hasil yang diinginkan user).
    */
   private void removeItem(DownloadItem item) {
      if (!item.isRemovable()) {
         showAlert("Tidak bisa dihapus",
               "Hanya unduhan yang Selesai, Gagal, atau Dibatalkan yang bisa dihapus dari daftar.");
         return;
      }
      if (item.activeTask != null)
         return; // safety (tidak mungkin untuk state terminal)
      items.remove(item);
   }

   // ================================================================
   // TASK ORCHESTRATION
   // ================================================================
   private void submitTask(DownloadItem item, boolean isResume) {
      DownloadTask task = new DownloadTask(item, sharedClient, isResume);
      item.activeTask = task;

      task.setOnSucceeded(e -> {
         if (item.activeTask != task)
            return;
         handleCompletion(item);
         item.activeTask = null;
      });

      task.setOnFailed(e -> {
         if (item.activeTask != task)
            return;
         Throwable ex = task.getException();
         item.setErrorSafe(ex != null ? ex.getMessage() : "unknown");
         item.setStateSafe(DownloadItem.State.FAILED);
         item.setSpeedText("-");
         item.setEtaText("-");
         item.activeTask = null;
         if (ex != null)
            ex.printStackTrace();
      });

      downloadPool.submit(task);
   }

   private void handleCompletion(DownloadItem item) {
      item.setSpeedText("-");
      item.setEtaText("-");
      if (item.isCancelRequested()) {
         item.setStateSafe(DownloadItem.State.CANCELLED);
         deleteFile(item.getFile());
      } else if (item.getState() == DownloadItem.State.PAUSING) {
         item.setStateSafe(DownloadItem.State.PAUSED);
      } else if (item.getState() == DownloadItem.State.DOWNLOADING) {
         item.setStateSafe(DownloadItem.State.COMPLETED);
         item.setProgressSafe(1.0);
      }
   }

   // ================================================================
   // HELPERS
   // ================================================================
   private static String extractFilenameFromUrl(String url) {
      try {
         String path = URI.create(url).getPath();
         if (path == null || path.isEmpty() || path.endsWith("/"))
            return "downloaded_file";
         String name = path.substring(path.lastIndexOf('/') + 1);
         return name.isEmpty() ? "downloaded_file" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
      } catch (Exception e) {
         return "downloaded_file";
      }
   }

   private void deleteFile(File f) {
      if (f != null && f.exists() && !f.delete()) {
         System.err.println("Gagal hapus file: " + f.getAbsolutePath());
      }
   }

   private void showAlert(String title, String message) {
      Alert a = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
      a.setTitle(title);
      a.setHeaderText(null);
      a.showAndWait();
   }

   public void shutdown() {
      // Set flag cancel ke semua item aktif agar loop membaca lebih cepat
      for (DownloadItem item : items) {
         if (item.activeTask != null)
            item.requestCancel();
      }
      downloadPool.shutdownNow();
   }
}