package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VideoAvDomainTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void exactEpochRetainsFractionalOriginAndCommonSampleClock() {
    var epoch = new VideoAvEpoch(180001, 1, 90000, 720000);
    assertEquals(2000011, epoch.timelineOriginUs());
    assertEquals(533, epoch.sampleAt(24000));
    assertEquals(33, epoch.startMs(24000));
    assertEquals(34, epoch.endMs(24000));
    assertThrows(RuntimeException.class, () -> new VideoAvEpoch(1, 1, 90000, 16000));
  }

  @Test
  void actualSilenceIsMediaAndAbsentAudioIsNotFakeSilence() {
    var epoch = new VideoAvEpoch(0, 1, 16000, 16000);
    var audio = new AudioWaveform(HASH, "decoder-v1", 0, 2, new byte[4]);
    var timings = List.of(new VideoAvFrameTiming(0, 0, 2, 2, 2, HASH));
    var clip =
        new VideoAvClip(
            new byte[] {1},
            ModelValues.sha256(new byte[] {1}),
            0,
            2,
            timings,
            VideoAvProfile.framesManifestSha256(timings));
    var window = new VideoAvWindow("window-a", 0, 0, 2, clip, audio);
    var compiled = new VideoAvCompilation(HASH, "decoder-v1", epoch, 2, true, List.of(window));
    assertEquals(1, compiled.windows().size());
    assertThrows(RuntimeException.class, () -> new VideoAvWindow("window-a", 0, 0, 2, null, null));
    assertThrows(
        RuntimeException.class,
        () -> new VideoAvCompilation(HASH, "decoder-v1", epoch, 2, false, List.of(window)));
  }

  @Test
  void compilationRejectsOmittedSamplesAndWindowGaps() {
    var epoch = new VideoAvEpoch(0, 1, 16000, 16000);
    var frames = List.of(new VideoAvFrameTiming(0, 0, 2, 2, 2, HASH));
    var clip =
        new VideoAvClip(
            new byte[] {1},
            ModelValues.sha256(new byte[] {1}),
            0,
            2,
            frames,
            VideoAvProfile.framesManifestSha256(frames));
    var first =
        new VideoAvWindow(
            "window-a", 0, 0, 2, clip, new AudioWaveform(HASH, "decoder-v1", 0, 2, new byte[4]));
    var skipped =
        new VideoAvWindow(
            "window-b", 1, 2, 4, null, new AudioWaveform(HASH, "decoder-v1", 3, 4, new byte[2]));
    assertThrows(
        RuntimeException.class,
        () -> new VideoAvCompilation(HASH, "decoder-v1", epoch, 4, true, List.of(first, skipped)));
    assertThrows(
        RuntimeException.class,
        () -> new VideoAvCompilation(HASH, "decoder-v1", epoch, 5, true, List.of(first)));
  }

  @Test
  void twoIndependentCollectionsMustUseOneEmbeddingSpace() {
    var visual = new IndexTarget("embedding-v1", "a".repeat(64), "model-v1", 2);
    var audio = new IndexTarget("embedding-v1", "b".repeat(64), "model-v1", 2);
    var targets = new VideoAvTargets(visual, audio);
    assertNotEquals(
        VideoAvProfile.fingerprint(targets, "analysis-v1", "decoder-v1", 1),
        VideoAvProfile.fingerprint(targets, "analysis-v1", "decoder-v1", 2));
    assertThrows(RuntimeException.class, () -> new VideoAvTargets(visual, visual));
    assertThrows(
        RuntimeException.class,
        () ->
            new VideoAvTargets(visual, new IndexTarget("other-v1", "b".repeat(64), "model-v1", 2)));
  }

  @Test
  void routeIdentityIsSeparatedAndFramesBindActualPixelsAndDurations() {
    assertNotEquals(
        VideoAvProfile.physicalSegmentId("generation-a", VideoAvRoute.VISUAL, "window-a"),
        VideoAvProfile.physicalSegmentId("generation-a", VideoAvRoute.AUDIO, "window-a"));
    var first = new VideoAvFrameTiming(0, 0, 2, 2, 2, HASH);
    var changed = new VideoAvFrameTiming(0, 0, 2, 2, 2, "b".repeat(64));
    assertNotEquals(
        VideoAvProfile.framesManifestSha256(List.of(first)),
        VideoAvProfile.framesManifestSha256(List.of(changed)));
    assertTrue(VideoAvProfile.framesManifestSha256(List.of(first)).matches("[a-f0-9]{64}"));
  }
}
