package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SynopsisSubtitleDomainTest {
  @Test
  void subtitleIsIndependentTimedTextNotTranscriptOcrOrFrame() {
    var kind = SynopsisEvidence.Kind.valueOf("VIDEO_SUBTITLE");
    var time = new SynopsisEvidence.TimeRange(499966, 1249967);
    var item = new SynopsisEvidence("subtitle", kind, new SynopsisEvidence.Text("尾部条件😀"), time);
    assertEquals(time, item.time());
    assertNotEquals(SynopsisEvidence.Kind.VIDEO_TRANSCRIPT, item.kind());
    assertThrows(
        RuntimeException.class, () -> new SynopsisEvidence("subtitle", kind, item.content(), null));
    assertThrows(
        RuntimeException.class,
        () ->
            new SynopsisEvidence(
                "subtitle",
                kind,
                new SynopsisEvidence.Image(new VisualImage("image/png", new byte[] {1})),
                time));
  }

  @Test
  void fullFileFingerprintAndBatchPartitionIncludeSubtitleTailAndExactTimes() {
    var kind = SynopsisEvidence.Kind.valueOf("VIDEO_SUBTITLE");
    var items =
        java.util.stream.IntStream.range(0, 65)
            .mapToObj(
                i ->
                    new SynopsisEvidence(
                        "cue" + i,
                        kind,
                        new SynopsisEvidence.Text(i == 64 ? "末尾：未经确认不得上线。" : "字幕😀"),
                        new SynopsisEvidence.TimeRange(i * 1000000L, i * 1000000L + 500001)))
            .toList();
    var publication =
        new PublicationVersion(
            "document",
            "publication",
            "revision",
            "generation",
            "a".repeat(64),
            "java-video-compiler-v3:" + "b".repeat(64),
            new IndexTarget("embedding", "collection", "model-v1", 2),
            "c".repeat(64),
            65);
    var file = new SynopsisFileInput(publication, items);
    var batches = SynopsisBatch.partition(file);
    assertEquals(List.of(64, 1), batches.stream().map(b -> b.evidence().size()).toList());
    assertEquals(items.getLast(), batches.getLast().evidence().getLast());
    var changed = new java.util.ArrayList<>(items);
    changed.set(
        64,
        new SynopsisEvidence(
            "cue64", kind, new SynopsisEvidence.Text("末尾被改写"), items.getLast().time()));
    assertNotEquals(file.fingerprint(), new SynopsisFileInput(publication, changed).fingerprint());
  }
}
