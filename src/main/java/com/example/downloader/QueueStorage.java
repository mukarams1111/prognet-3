package com.example.downloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class QueueStorage {

   private static final int FORMAT_VERSION = 1;

   private final Path filePath;
   private final Gson gson;

   public QueueStorage() {
      this(defaultPath());
   }

   public QueueStorage(Path filePath) {
      this.filePath = filePath;
      this.gson = new GsonBuilder().setPrettyPrinting().create();
   }

   private static Path defaultPath() {
      String os = System.getProperty("os.name").toLowerCase();
      String base;
      if (os.contains("win")) {
         base = System.getenv("APPDATA");
         if (base == null)
            base = System.getProperty("user.home");
      } else if (os.contains("mac")) {
         base = System.getProperty("user.home") + "/Library/Application Support";
      } else {
         String xdg = System.getenv("XDG_CONFIG_HOME");
         base = (xdg != null) ? xdg : System.getProperty("user.home") + "/.config";
      }
      return Paths.get(base, "DownloadManager", "queue.json");
   }

   public Path getFilePath() {
      return filePath;
   }

   public List<DownloadItem> load() {
      if (!Files.exists(filePath))
         return new ArrayList<>();
      try (Reader r = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
         PersistedQueue pq = gson.fromJson(r, PersistedQueue.class);
         if (pq == null || pq.items == null)
            return new ArrayList<>();
         List<DownloadItem> result = new ArrayList<>();
         for (PersistedItem pi : pq.items) {
            try {
               DownloadItem item = restoreItem(pi);
               if (item != null)
                  result.add(item);
            } catch (Exception e) {
               System.err.println("Skip item rusak (" + pi.url + "): " + e.getMessage());
            }
         }
         return result;
      } catch (Exception e) {
         System.err.println("Gagal load queue: " + e.getMessage());
         return new ArrayList<>();
      }
   }

   public synchronized void save(List<DownloadItem> items) {
      PersistedQueue pq = new PersistedQueue();
      pq.version = FORMAT_VERSION;
      pq.items = new ArrayList<>();
      for (DownloadItem it : items) {
         PersistedItem pi = toPersisted(it);
         if (pi != null)
            pq.items.add(pi);
      }
      Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
      try {
         Files.createDirectories(filePath.getParent());
         try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            gson.toJson(pq, w);
         }
         try {
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException e) {
         System.err.println("Gagal save queue: " + e.getMessage());
      }
   }

   private PersistedItem toPersisted(DownloadItem it) {
      DownloadItem.State s = it.getState();
      if (s == DownloadItem.State.COMPLETED || s == DownloadItem.State.CANCELLED)
         return null;

      PersistedItem pi = new PersistedItem();
      pi.url = it.getUrl();
      pi.filePath = it.getFile().getAbsolutePath();
      pi.fileName = it.fileNameProperty().get();
      pi.totalBytes = it.getTotalBytes();
      pi.supportsRange = it.supportsRange();
      pi.bytesDownloaded = it.bytesRef().get();
      pi.state = (s == DownloadItem.State.FAILED) ? "FAILED" : "PAUSED";
      if (it.getSegments() != null) {
         pi.segments = new ArrayList<>();
         for (Segment seg : it.getSegments()) {
            PersistedSegment ps = new PersistedSegment();
            ps.id = seg.id;
            ps.start = seg.start;
            ps.end = seg.end;
            ps.currentOffset = seg.currentOffset;
            pi.segments.add(ps);
         }
      }
      return pi;
   }

   private DownloadItem restoreItem(PersistedItem pi) {
      File file = new File(pi.filePath);
      boolean fileMissing = !file.exists();

      DownloadItem item = new DownloadItem(pi.url, file);
      item.setTotalBytes(pi.totalBytes);
      item.setSupportsRange(pi.supportsRange);
      item.fileNameProperty().set(pi.fileName);

      long progressFromSegments = 0;
      if (pi.segments != null && !pi.segments.isEmpty()) {
         List<Segment> segs = new ArrayList<>();
         for (PersistedSegment ps : pi.segments) {
            Segment s = new Segment(ps.id, ps.start, ps.end);
            s.currentOffset = fileMissing ? ps.start : ps.currentOffset;
            segs.add(s);
            progressFromSegments += (s.currentOffset - s.start);
         }
         item.setSegments(segs);
         item.bytesRef().set(progressFromSegments);
      } else {
         item.bytesRef().set(fileMissing ? 0 : pi.bytesDownloaded);
      }

      // File hilang & tidak bisa resume (tanpa totalBytes) → drop
      if (fileMissing && pi.totalBytes <= 0)
         return null;

      if (pi.totalBytes > 0) {
         item.setProgressSafe((double) item.bytesRef().get() / pi.totalBytes);
      }

      DownloadItem.State target = "FAILED".equals(pi.state)
            ? DownloadItem.State.FAILED
            : DownloadItem.State.PAUSED;
      item.setStateSafe(target);
      return item;
   }

   // ---------- DTO ----------
   static class PersistedQueue {
      int version;
      List<PersistedItem> items;
   }

   static class PersistedItem {
      String url;
      String filePath;
      String fileName;
      long totalBytes;
      long bytesDownloaded;
      boolean supportsRange;
      String state;
      List<PersistedSegment> segments;
   }

   static class PersistedSegment {
      int id;
      long start;
      long end;
      long currentOffset;
   }
}