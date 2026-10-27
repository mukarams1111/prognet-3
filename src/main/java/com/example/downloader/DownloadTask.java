package com.example.downloader;

import javafx.concurrent.Task;

import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DownloadTask extends Task<Void> {

   private final DownloadItem item;
   private final HttpClient client;
   private final boolean isResume;

   public DownloadTask(DownloadItem item, HttpClient sharedClient, boolean isResume) {
      this.item = item;
      this.client = sharedClient;
      this.isResume = isResume;
   }

   @Override
   protected Void call() throws Exception {
      if (!isResume) {
         item.setStateSafe(DownloadItem.State.PROBING);

         HttpResponse<InputStream> probeResp = fetchResponse(0);
         int code = probeResp.statusCode();
         if (code != 200) {
            try {
               probeResp.body().close();
            } catch (Exception ignored) {
            }
            throw new RuntimeException("HTTP " + code);
         }
         String ct = probeResp.headers().firstValue("Content-Type").orElse("").toLowerCase();
         if (ct.startsWith("text/html")) {
            try {
               probeResp.body().close();
            } catch (Exception ignored) {
            }
            throw new RuntimeException("Server mengembalikan HTML, bukan file");
         }
         try {
            probeResp.body().close();
         } catch (Exception ignored) {
         }

         long size = probeResp.headers().firstValueAsLong("Content-Length").orElse(-1L);
         boolean rangeOk = "bytes".equalsIgnoreCase(
               probeResp.headers().firstValue("Accept-Ranges").orElse(""));
         item.setTotalBytes(size); // → memicu refreshSizeText
         item.setSupportsRange(rangeOk);
         if (size > 0 && rangeOk && size >= DownloadItem.MIN_CHUNK_SIZE * DownloadItem.NUM_CONNECTIONS) {
            item.setSegments(item.buildSegments());
         }
      }

      item.setStateSafe(DownloadItem.State.DOWNLOADING);
      if (item.getSegments() != null) {
         return downloadMulti();
      } else {
         return downloadSingle();
      }
   }

   // --------------------------------------------------------------
   // MULTI-CONNECTION
   // --------------------------------------------------------------
   private Void downloadMulti() throws Exception {
      try (RandomAccessFile raf = new RandomAccessFile(item.getFile(), "rw")) {
         if (item.bytesRef().get() == 0) {
            raf.setLength(item.getTotalBytes());
         }
         FileChannel channel = raf.getChannel();

         ExecutorService segPool = Executors.newFixedThreadPool(DownloadItem.NUM_CONNECTIONS);
         List<Future<Void>> futures = new ArrayList<>();
         for (Segment seg : item.getSegments()) {
            futures.add(segPool.submit(() -> runSegment(channel, seg)));
         }

         Thread monitor = new Thread(this::monitorProgress, "monitor-" + item.getFile().getName());
         monitor.setDaemon(true);
         monitor.start();

         try {
            for (Future<Void> f : futures)
               f.get();
         } finally {
            segPool.shutdownNow();
            monitor.interrupt();
         }
      }
      return null;
   }

   private Void runSegment(FileChannel channel, Segment seg) throws Exception {
      int retries = 0;
      while (!seg.isComplete()) {
         if (item.isPauseRequested() || item.isCancelRequested())
            return null;
         if (retries > 3)
            throw new RuntimeException("Segmen " + seg.id + " gagal");

         try {
            HttpRequest req = HttpRequest.newBuilder()
                  .uri(URI.create(item.getUrl()))
                  .header("User-Agent", DownloadItem.UA)
                  .header("Range", "bytes=" + seg.currentOffset + "-" + seg.end)
                  .GET().build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 206) {
               try {
                  resp.body().close();
               } catch (Exception ignored) {
               }
               throw new RuntimeException("Segmen " + seg.id + " HTTP " + resp.statusCode());
            }
            try (InputStream in = resp.body()) {
               byte[] buf = new byte[65536];
               int n;
               while ((n = in.read(buf)) != -1) {
                  if (item.isPauseRequested() || item.isCancelRequested())
                     return null;
                  channel.write(ByteBuffer.wrap(buf, 0, n), seg.currentOffset);
                  seg.currentOffset += n;
                  item.bytesRef().addAndGet(n);
               }
            }
            retries = 0;
         } catch (Exception e) {
            if (item.isPauseRequested() || item.isCancelRequested())
               return null;
            retries++;
            Thread.sleep(1000L * retries);
         }
      }
      return null;
   }

   private void monitorProgress() {
      long lastT = System.nanoTime();
      long lastB = item.bytesRef().get();
      while (!item.isPauseRequested() && !item.isCancelRequested()) {
         try {
            Thread.sleep(200);
         } catch (InterruptedException e) {
            return;
         }

         long now = System.nanoTime();
         long cur = item.bytesRef().get();
         long total = item.getTotalBytes();
         if (total > 0)
            item.setProgressSafe((double) cur / total);
         item.refreshSizeText(); // ← update "downloaded / total"

         double sec = (now - lastT) / 1_000_000_000.0;
         long delta = cur - lastB;
         double bps = sec > 0 ? delta / sec : 0;
         item.setSpeedText(DownloadItem.formatSpeed(bps));
         item.setEtaText(total > 0 ? DownloadItem.formatEta(total - cur, bps) : "?");

         lastT = now;
         lastB = cur;

         if (item.getSegments().stream().allMatch(Segment::isComplete))
            break;
      }
   }

   // --------------------------------------------------------------
   // SINGLE-CONNECTION
   // --------------------------------------------------------------
   private Void downloadSingle() throws Exception {
      long startOffset = item.bytesRef().get();
      HttpResponse<InputStream> resp = fetchResponse(startOffset);
      int status = resp.statusCode();

      if (startOffset > 0 && status == 200) {
         try {
            resp.body().close();
         } catch (Exception ignored) {
         }
         startOffset = 0;
         item.bytesRef().set(0);
         resp = fetchResponse(0);
         status = resp.statusCode();
      }
      if ((startOffset > 0 && status != 206) || (startOffset == 0 && status != 200)) {
         resp.body().close();
         throw new RuntimeException("HTTP " + status);
      }

      item.refreshSizeText(); // tampilkan "0 / X" sebelum byte pertama

      try (InputStream in = resp.body();
            RandomAccessFile raf = new RandomAccessFile(item.getFile(), "rw")) {
         if (startOffset == 0)
            raf.setLength(0);
         raf.seek(startOffset);

         byte[] buf = new byte[65536];
         int n;
         long total = startOffset;
         long lastT = System.nanoTime();
         long lastB = startOffset;
         long lastUi = 0;

         while ((n = in.read(buf)) != -1) {
            if (item.isPauseRequested() || item.isCancelRequested())
               break;
            raf.write(buf, 0, n);
            total += n;
            item.bytesRef().set(total);

            long now = System.nanoTime();
            if (now - lastUi >= 200_000_000L) {
               long totalSize = item.getTotalBytes();
               if (totalSize > 0)
                  item.setProgressSafe((double) total / totalSize);
               item.refreshSizeText(); // ← update "downloaded / total"

               double sec = (now - lastT) / 1_000_000_000.0;
               long delta = total - lastB;
               double bps = sec > 0 ? delta / sec : 0;
               item.setSpeedText(DownloadItem.formatSpeed(bps));
               item.setEtaText(totalSize > 0 ? DownloadItem.formatEta(totalSize - total, bps) : "?");
               lastT = now;
               lastB = total;
               lastUi = now;
            }
         }
      }
      return null;
   }

   private HttpResponse<InputStream> fetchResponse(long offset) throws Exception {
      HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(item.getUrl()))
            .header("User-Agent", DownloadItem.UA)
            .header("Accept", "*/*");
      if (offset > 0)
         b.header("Range", "bytes=" + offset + "-");
      return client.send(b.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
   }
}