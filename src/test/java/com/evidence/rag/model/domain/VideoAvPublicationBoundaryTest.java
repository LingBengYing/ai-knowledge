package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.VideoAvTestFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoAvPublicationBoundaryTest {
  @Test
  void generationCannotBeReplayedEvenWhenAllMediaAndReceiptBytesAreUnchanged() {
    var base = VideoAvTestFixture.publication(VideoAvTestFixture.claim(true));
    var replay = new Copy(base);
    replay.id = UUID.randomUUID().toString();
    assertThrows(RuntimeException.class, replay::build);
    var noncanonical = new Copy(base);
    noncanonical.id = "A" + base.id().substring(1);
    assertThrows(RuntimeException.class, noncanonical::build);
  }

  @Test
  void revisionWindowOrdinalAndPartitionMustAllMatchCompleteSavedSource() {
    var base = VideoAvTestFixture.publication(VideoAvTestFixture.claim(true));
    var revision = new Copy(base);
    revision.revision = "replacement-revision";
    assertThrows(RuntimeException.class, revision::build);
    var gap = new Copy(base);
    var w = base.windows().get(1);
    gap.windows.set(
        1,
        window(
            w,
            w.ordinal(),
            w.startTick() + 1,
            w.endTick() + 1,
            w.audio(),
            w.visualPhysicalId(),
            w.audioPhysicalId()));
    assertThrows(RuntimeException.class, gap::build);
    var ordinal = new Copy(base);
    ordinal.windows.set(
        1,
        window(
            w,
            2,
            w.startTick(),
            w.endTick(),
            w.audio(),
            w.visualPhysicalId(),
            w.audioPhysicalId()));
    assertThrows(RuntimeException.class, ordinal::build);
    var missingTail = new Copy(base);
    missingTail.windows.removeLast();
    assertThrows(RuntimeException.class, missingTail::build);
  }

  @Test
  void actualSampleFloorAndFullPcmCursorCannotBeShiftedByOneSample() {
    var base = VideoAvTestFixture.publication(VideoAvTestFixture.claim(true));
    var w = base.windows().getFirst();
    var offset = new Copy(base);
    var a = w.audio();
    offset.windows.set(
        0,
        window(
            w,
            w.ordinal(),
            w.startTick(),
            w.endTick(),
            new VideoAvAudioMetadata(a.pcmSha256(), a.wavSha256(), 1, a.endSample(), 16000),
            w.visualPhysicalId(),
            w.audioPhysicalId()));
    assertThrows(RuntimeException.class, offset::build);
    var beyond = new Copy(base);
    beyond.windows.set(
        0,
        window(
            w,
            w.ordinal(),
            w.startTick(),
            w.endTick(),
            new VideoAvAudioMetadata(a.pcmSha256(), a.wavSha256(), 0, a.endSample() + 1, 16000),
            w.visualPhysicalId(),
            w.audioPhysicalId()));
    assertThrows(RuntimeException.class, beyond::build);
    var falseAbsence = new Copy(base);
    falseAbsence.hasAudio = false;
    assertThrows(RuntimeException.class, falseAbsence::build);
  }

  @Test
  void eachRoutePhysicalIdIsDerivedFromGenerationAndWindowInsteadOfTrustedFromReceipt() {
    var base = VideoAvTestFixture.publication(VideoAvTestFixture.claim(true));
    var w = base.windows().getFirst();
    var visual = new Copy(base);
    visual.windows.set(
        0,
        window(
            w,
            0,
            w.startTick(),
            w.endTick(),
            w.audio(),
            "seg-" + "b".repeat(64),
            w.audioPhysicalId()));
    assertThrows(RuntimeException.class, visual::build);
    var audio = new Copy(base);
    audio.windows.set(
        0,
        window(
            w,
            0,
            w.startTick(),
            w.endTick(),
            w.audio(),
            w.visualPhysicalId(),
            "seg-" + "b".repeat(64)));
    assertThrows(RuntimeException.class, audio::build);
  }

  @Test
  void completeWindowManifestAlsoBindsSilentTailMetadataAndOriginalEpoch() {
    var base = VideoAvTestFixture.publication(VideoAvTestFixture.claim(true));
    var altered = new Copy(base);
    var tail = base.windows().getLast();
    var a = tail.audio();
    altered.windows.set(
        altered.windows.size() - 1,
        window(
            tail,
            tail.ordinal(),
            tail.startTick(),
            tail.endTick(),
            new VideoAvAudioMetadata(
                a.pcmSha256(), "c".repeat(64), a.startSample(), a.endSample(), 16000),
            tail.visualPhysicalId(),
            tail.audioPhysicalId()));
    assertThrows(RuntimeException.class, altered::build);
    var epoch = new Copy(base);
    epoch.epoch =
        new VideoAvEpoch(
            base.epoch().sourceFirstPts() + 1,
            base.epoch().sourceTimeBaseNumerator(),
            base.epoch().sourceTimeBaseDenominator(),
            base.epoch().ticksPerSecond());
    assertThrows(RuntimeException.class, epoch::build);
  }

  @Test
  void verifiedRouteCountProjectionAndWorkspaceCannotBeSubstituted() {
    var base = VideoAvTestFixture.publication(VideoAvTestFixture.claim(true));
    var projection = new Copy(base);
    var r = base.visualReceipt();
    projection.visual =
        new VideoAvRouteReceipt(
            VideoAvRoute.VISUAL,
            r.count(),
            r.manifestSha256(),
            new VerifiedRevision("b".repeat(64), r.manifestSha256(), r.count()));
    assertThrows(RuntimeException.class, projection::build);
    var count = new Copy(base);
    count.visual =
        new VideoAvRouteReceipt(
            VideoAvRoute.VISUAL,
            1,
            r.manifestSha256(),
            new VerifiedRevision(base.visualTarget().projectionIdentity(), r.manifestSha256(), 1));
    assertThrows(RuntimeException.class, count::build);
    var role = new Copy(base);
    role.visual = base.audioReceipt();
    assertThrows(RuntimeException.class, role::build);
    var workspace = new Copy(base);
    workspace.workspace = "different-workspace";
    assertThrows(RuntimeException.class, workspace::build);
    var document = new Copy(base);
    document.document = "different-document";
    assertThrows(RuntimeException.class, document::build);
  }

  @Test
  void absenceReceiptBindsFullOriginalHashAndDoesNotStandInForVerifiedAudio() {
    var base = VideoAvTestFixture.publication(VideoAvTestFixture.claim(false));
    assertEquals(0, base.audioWindowCount());
    assertNull(base.audioReceipt().verified());
    var source = new Copy(base);
    source.source = "d".repeat(64);
    assertThrows(RuntimeException.class, source::build);
    var fake = new Copy(base);
    fake.audio = new VideoAvRouteReceipt(VideoAvRoute.AUDIO, 0, "e".repeat(64), null);
    assertThrows(RuntimeException.class, fake::build);
    var flag = new Copy(base);
    flag.hasAudio = true;
    assertThrows(RuntimeException.class, flag::build);
  }

  @Test
  void sourceEnvelopeAndStoredProfileAreCheckedBeforeAStateCanAdvertisePublication() {
    var claim = VideoAvTestFixture.claim(true);
    var base = VideoAvTestFixture.publication(claim);
    var changed = new Copy(base);
    changed.filename = "renamed-source.mp4";
    var renamed = changed.build();
    assertNotEquals(base.manifestSha256(), renamed.manifestSha256());
    assertThrows(
        RuntimeException.class,
        () -> new VideoAvState(claim.original(), base.visualTarget(), base.audioTarget(), renamed));
    var wrongProfile = new Copy(base);
    wrongProfile.profile = "a".repeat(64);
    assertThrows(RuntimeException.class, wrongProfile::build);
    var duration = new Copy(base);
    duration.duration = base.epoch().durationLimit(600) + 1;
    assertThrows(RuntimeException.class, duration::build);
    var mime = new Copy(base);
    mime.mime = "audio/wav";
    assertThrows(RuntimeException.class, mime::build);
    var size = new Copy(base);
    size.size = 20L * 1024 * 1024 + 1;
    assertThrows(RuntimeException.class, size::build);
  }

  @Test
  void routeReceiptRequiresVerifiedCountAndManifestForPresentMediaOnly() {
    var hash = "b".repeat(64);
    assertThrows(
        RuntimeException.class, () -> new VideoAvRouteReceipt(VideoAvRoute.VISUAL, 1, hash, null));
    assertThrows(
        RuntimeException.class,
        () ->
            new VideoAvRouteReceipt(
                VideoAvRoute.AUDIO, 0, hash, new VerifiedRevision(hash, hash, 1)));
    assertThrows(
        RuntimeException.class,
        () ->
            new VideoAvRouteReceipt(
                VideoAvRoute.VISUAL, 1, hash, new VerifiedRevision(hash, "c".repeat(64), 1)));
  }

  private static VideoAvPublishedWindow window(
      VideoAvPublishedWindow w,
      int ordinal,
      long start,
      long end,
      VideoAvAudioMetadata audio,
      String visualPhysical,
      String audioPhysical) {
    return new VideoAvPublishedWindow(
        w.id(),
        ordinal,
        start,
        end,
        w.video(),
        audio,
        visualPhysical,
        w.visualEntrySha256(),
        audioPhysical,
        w.audioEntrySha256());
  }

  private static final class Copy {
    private final VideoAvPublication base;
    private String id;
    private String workspace;
    private String document;
    private String revision;
    private String source;
    private String filename;
    private String mime;
    private String profile;
    private long size;
    private long duration;
    private boolean hasAudio;
    private VideoAvEpoch epoch;
    private List<VideoAvPublishedWindow> windows;
    private VideoAvRouteReceipt visual;
    private VideoAvRouteReceipt audio;

    private Copy(VideoAvPublication base) {
      this.base = base;
      id = base.id();
      workspace = base.workspaceId();
      document = base.documentId();
      revision = base.sourceRevisionId();
      source = base.sourceSha256();
      filename = base.filename();
      mime = base.mediaType();
      profile = base.profileFingerprint();
      size = base.sizeBytes();
      duration = base.durationTick();
      hasAudio = base.hasAudio();
      epoch = base.epoch();
      windows = new ArrayList<>(base.windows());
      visual = base.visualReceipt();
      audio = base.audioReceipt();
    }

    private VideoAvPublication build() {
      return new VideoAvPublication(
          id,
          workspace,
          document,
          revision,
          source,
          filename,
          mime,
          size,
          epoch,
          duration,
          hasAudio,
          base.decoderRevision(),
          base.analysisModelRevision(),
          profile,
          base.visualTarget(),
          base.audioTarget(),
          base.chunkSeconds(),
          base.windowManifestSha256(),
          windows,
          visual,
          audio,
          base.createdAtMs());
    }
  }
}
