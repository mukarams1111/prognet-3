package com.example.downloader;

import javafx.application.Platform;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PrimaryController {

   private static final int MAX_CONCURRENT_DOWNLOADS = 3;
   private static final long AUTO_SAVE_INTERVAL_SEC = 5;

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
   private TableColumn<DownloadItem, String> sizeColumn;
   @FXML
   private TableColumn<DownloadItem, String> speedColumn;
   @FXML
   private TableColumn<DownloadItem, String> etaColumn;
   @FXML
   private TableColumn<DownloadItem, DownloadItem> actionColumn;

   private final ObservableList<DownloadItem> items = FXCollections.observableArrayList();
   private final QueueStorage storage = new QueueStorage();

   private final ExecutorService downloadPool = Executors.newFixedThreadPool(
         MAX_CONCURRENT_DOWNLOADS,
         r -> {
            Thread t = new Thread(r, "download-worker");
            t.setDaemon(true);
            return t;
         });

   private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(
         r -> {
            Thread t = new Thread(r, "save-worker");
            t.setDaemon(true);
            return t;
         });

   private final ScheduledExecutorService autoSaveExecutor = Executors.newSingleThreadScheduledExecutor(
         r -> {
            Thread t = new Thread(r, "auto-save");
            t.setDaemon(true);
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
      sizeColumn.setCellValueFactory(cd -> cd.getValue().sizeTextProperty());
      speedColumn.setCellValueFactory(cd -> cd.getValue().speedProperty());
      etaColumn.setCellValueFactory(cd -> cd.getValue().etaProperty());

      progressColumn.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
      progressColumn.setCellFactory(col -> new TableCell<>() {
         private final ProgressBar pb = new ProgressBar(0);
         {
            pb.setPrefWidth(160);
            pb.setMaxWidth(Double.MAX_VALUE);
         }

         @Override
         protected void updateItem(DownloadItem it, boolean empty) {
            super.updateItem(it, empty);
            pb.progressProperty().unbind();
            if (empty || it == null)
               setGraphic(null);
            else {
               pb.progressProperty().bind(it.progressProperty());
               setGraphic(pb);
            }
         }
      });

      actionColumn.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
      actionColumn.setCellFactory(col -> new TableCell<>() {
         private final Button pauseBtn = new Button("Pause");
         private final Button resumeBtn = new Button("Resume");
         private final Button cancelBtn = new Button("Cancel");
         private final Button removeBtn = new Button("Remove");
         private final HBox box = new HBox(4, pauseBtn, resumeBtn, cancelBtn, removeBtn);
         {
            pauseBtn.setOnAction(e -> {
               DownloadItem it = current();
               if (it != null)
                  it.requestPause();
            });
            resumeBtn.setOnAction(e -> {
               DownloadItem it = current();
               if (it != null)
                  resumeItem(it);
            });
            cancelBtn.setOnAction(e -> {
               DownloadItem it = current();
               if (it != null)
                  cancelItem(it);
            });
            removeBtn.setOnAction(e -> {
               DownloadItem it = current();
               if (it != null)
                  removeItem(it);
            });
         }

         private DownloadItem current() {
            int idx = getIndex();
            if (idx < 0 || idx >= getTableView().getItems().size())
               return null;
            return getTableView().getItems().get(idx);
         }

         @Override
         protected void updateItem(DownloadItem it, boolean empty) {
            super.updateItem(it, empty);
            for (Button b : new Button[] { pauseBtn, resumeBtn, cancelBtn, removeBtn }) {
               b.visibleProperty().unbind();
               b.managedProperty().unbind();
            }
            if (empty || it == null) {
               setGraphic(null);
               return;
            }
            var st = it.stateProperty();
            pauseBtn.visibleProperty().bind(st.isEqualTo(DownloadItem.State.DOWNLOADING));
            pauseBtn.managedProperty().bind(pauseBtn.visibleProperty());
            resumeBtn.visibleProperty().bind(
                  st.isEqualTo(DownloadItem.State.PAUSED).or(st.isEqualTo(DownloadItem.State.FAILED)));
            resumeBtn.managedProperty().bind(resumeBtn.visibleProperty());
            cancelBtn.visibleProperty().bind(
                  st.isEqualTo(DownloadItem.State.DOWNLOADING).or(st.isEqualTo(DownloadItem.State.PAUSED)));
            cancelBtn.managedProperty().bind(cancelBtn.visibleProperty());
            removeBtn.visibleProperty().bind(
                  st.isEqualTo(DownloadItem.State.CANCELLED)
                        .or(st.isEqualTo(DownloadItem.State.COMPLETED))
                        .or(st.isEqualTo(DownloadItem.State.FAILED))
                        .or(st.isEqualTo(DownloadItem.State.PAUSED)));
            removeBtn.managedProperty().bind(removeBtn.visibleProperty());
            setGraphic(box);
         }
      });

      downloadTable.setRowFactory(tv -> {
         TableRow<DownloadItem> row = new TableRow<>();
         ContextMenu cm = new ContextMenu();
         MenuItem removeMi = new MenuItem("Hapus dari daftar");
         removeMi.setOnAction(e -> {
            if (row.getItem() != null)
               removeItem(row.getItem());
         });
         cm.getItems().add(removeMi);
         row.contextMenuProperty().bind(
               Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(cm));
         return row;
      });

      downloadTable.setOnKeyPressed(e -> {
         if (e.getCode() == KeyCode.DELETE) {
            DownloadItem sel = downloadTable.getSelectionModel().getSelectedItem();
            if (sel != null)
               removeItem(sel);
         }
      });

      // Restore
      List<DownloadItem> restored = storage.load();
      for (DownloadItem it : restored)
         registerItem(it, false);
      if (!restored.isEmpty()) {
         System.out.println("Restored " + restored.size() + " item(s) from " + storage.getFilePath());
      }

      // Auto-save
      autoSaveExecutor.scheduleAtFixedRate(this::autoSave, AUTO_SAVE_INTERVAL_SEC, AUTO_SAVE_INTERVAL_SEC,
            TimeUnit.SECONDS);
   }

   private void registerItem(DownloadItem item, boolean autoSubmit) {
      item.stateProperty().addListener((obs, oldV, newV) -> saveAsync());
      items.add(item);
      if (autoSubmit)
         submitTask(item, false);
   }

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
      registerItem(item, true);
      urlField.clear();
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

   private void removeItem(DownloadItem item) {
      if (!item.isRemovable()) {
         showAlert("Tidak bisa dihapus",
               "Hanya item yang Selesai, Gagal, Dibatalkan, atau Dijeda yang bisa dihapus.");
         return;
      }
      if (item.activeTask != null)
         return;
      items.remove(item);
      saveAsync();
   }

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
         item.refreshSizeText();
      } else if (item.getState() == DownloadItem.State.DOWNLOADING) {
         item.setStateSafe(DownloadItem.State.COMPLETED);
         item.setProgressSafe(1.0);
         // Pastikan teks ukuran di-update ke nilai final (byte tertulis terakhir)
         item.refreshSizeText();
      }
   }

   private void saveAsync() {
      List<DownloadItem> snapshot = new ArrayList<>(items);
      saveExecutor.submit(() -> storage.save(snapshot));
   }

   private void autoSave() {
      Platform.runLater(() -> {
         List<DownloadItem> snapshot = new ArrayList<>(items);
         boolean hasActive = snapshot.stream()
               .anyMatch(it -> it.getState() == DownloadItem.State.DOWNLOADING);
         if (hasActive)
            saveExecutor.submit(() -> storage.save(snapshot));
      });
   }

   public void shutdown() {
      try {
         storage.save(new ArrayList<>(items));
      } catch (Exception ignored) {
      }
      for (DownloadItem item : items) {
         if (item.activeTask != null)
            item.requestCancel();
      }
      autoSaveExecutor.shutdownNow();
      saveExecutor.shutdownNow();
      downloadPool.shutdownNow();
   }

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
      if (f != null && f.exists() && !f.delete())
         System.err.println("Gagal hapus: " + f.getAbsolutePath());
   }

   private void showAlert(String title, String msg) {
      Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
      a.setTitle(title);
      a.setHeaderText(null);
      a.showAndWait();
   }
}