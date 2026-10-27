package com.example.downloader;

class Segment {
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