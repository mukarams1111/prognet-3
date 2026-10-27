package com.example.downloader;

import javafx.application.Platform;
import javafx.beans.property.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class DownloadItem {
   public enum State {
      QUEUED, PROBING, DOWNLOADING, PAUSING, PAUSED, COMPLETED, FAILED, CANCELLED
   }

   static final int NUM_CONNECTIONS = 4;
   static final long MIN_CHUNK_SIZE = 1024 * 1024;
   static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
         "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

   private final String url;
   private final File file;

   private final StringProperty fileName = new SimpleStringProperty();
   private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.QUEUED);
   private final StringProperty statusText = new SimpleStringProperty("Menunggu");
   private final DoubleProperty progress = new SimpleDoubleProperty(0);
   private final StringProperty speed = new SimpleStringProperty("-");
   private final StringProperty eta = new SimpleStringProperty("-");
   private final StringProperty errorMessage = new SimpleStringProperty("");

   private long totalBytes = -1;
   private final AtomicLong bytesDownloaded = new AtomicLong(0);
   private boolean supportsRange = false;
   private List<Segment> segments;

   private volatile boolean pauseRequested = false;
   private volatile boolean cancelRequested = false;
   DownloadTask activeTask;

   public DownloadItem(String url, File file) {
      this.url = url;
      this.file = file;
      this.fileName.set(file.getName());
      state.addListener((obs, o, n) -> {
         statusText.set(switch (n) {
            case QUEUED -> "Menunggu";
            case PROBING -> "Menghubungi...";
            case DOWNLOADING -> "Mengunduh";
            case PAUSING -> "Menghentikan...";
            case PAUSED -> "Dijeda";
            case COMPLETED -> "Selesai";
            case FAILED -> "Gagal";
            case CANCELLED -> "Dibatalkan";
         });
      });
   }

   // Properti publik untuk binding
   public StringProperty fileNameProperty() {
      return fileName;
   }

   public ObjectProperty<State> stateProperty() {
      return state;
   }

   public StringProperty statusTextProperty() {
      return statusText;
   }

   public DoubleProperty progressProperty() {
      return progress;
   }

   public StringProperty speedProperty() {
      return speed;
   }

   public StringProperty etaProperty() {
      return eta;
   }

   public StringProperty errorMessageProperty() {
      return errorMessage;
   }

   public String getUrl() {
      return url;
   }

   public File getFile() {
      return file;
   }

   public State getState() {
      return state.get();
   }

   // Button-state queries
   public boolean isPausable() {
      return state.get() == State.DOWNLOADING;
   }

   public boolean isResumable() {
      return state.get() == State.PAUSED || state.get() == State.FAILED;
   }

   public boolean isCancellable() {
      return state.get() == State.DOWNLOADING || state.get() == State.PAUSED;
   }

   // Aksi user
   public void requestPause() {
      if (state.get() != State.DOWNLOADING)
         return;
      pauseRequested = true;
      setStateSafe(State.PAUSING);
   }

   public void requestCancel() {
      if (!isCancellable())
         return;
      cancelRequested = true;
      pauseRequested = true;
   }

   public void prepareResume() {
      pauseRequested = false;
      cancelRequested = false;
      if (segments != null) {
         long sum = segments.stream().mapToLong(s -> s.currentOffset - s.start).sum();
         bytesDownloaded.set(sum);
      }
   }

   public boolean isRemovable() {
      State s = state.get();
      return s == State.CANCELLED || s == State.COMPLETED || s == State.FAILED;
   }

   // Data backend (diakses oleh DownloadTask)
   long getTotalBytes() {
      return totalBytes;
   }

   void setTotalBytes(long t) {
      this.totalBytes = t;
   }

   void setSupportsRange(boolean s) {
      this.supportsRange = s;
   }

   boolean supportsRange() {
      return supportsRange;
   }

   List<Segment> getSegments() {
      return segments;
   }

   void setSegments(List<Segment> s) {
      this.segments = s;
   }

   AtomicLong bytesRef() {
      return bytesDownloaded;
   }

   boolean isPauseRequested() {
      return pauseRequested;
   }

   boolean isCancelRequested() {
      return cancelRequested;
   }

   void setCancelRequested(boolean c) {
      this.cancelRequested = c;
   }

   // Update UI helpers (thread-safe)
   void setStateSafe(State s) {
      Platform.runLater(() -> state.set(s));
   }

   void setProgressSafe(double p) {
      Platform.runLater(() -> progress.set(p));
   }

   void setSpeedText(String s) {
      Platform.runLater(() -> speed.set(s));
   }

   void setEtaText(String e) {
      Platform.runLater(() -> eta.set(e));
   }

   void setErrorSafe(String m) {
      Platform.runLater(() -> errorMessage.set(m));
   }

   List<Segment> buildSegments() {
      List<Segment> segs = new ArrayList<>();
      long chunkSize = totalBytes / NUM_CONNECTIONS;
      for (int i = 0; i < NUM_CONNECTIONS; i++) {
         long start = i * chunkSize;
         long end = (i == NUM_CONNECTIONS - 1) ? totalBytes - 1 : (start + chunkSize - 1);
         segs.add(new Segment(i, start, end));
      }
      return segs;
   }

   boolean canMultiConnect() {
      return supportsRange && totalBytes >= MIN_CHUNK_SIZE * NUM_CONNECTIONS;
   }

   static String formatSpeed(double bps) {
      if (bps < 1024)
         return String.format("%.0f B/s", bps);
      if (bps < 1024 * 1024)
         return String.format("%.1f KB/s", bps / 1024.0);
      return String.format("%.2f MB/s", bps / 1024.0 / 1024.0);
   }

   static String formatEta(long remainingBytes, double speedBps) {
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