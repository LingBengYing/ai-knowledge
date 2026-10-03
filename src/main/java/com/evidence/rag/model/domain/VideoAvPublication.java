package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record VideoAvPublication(
    String id,
    String workspaceId,
    String documentId,
    String sourceRevisionId,
    String sourceSha256,
    String filename,
    String mediaType,
    long sizeBytes,
    VideoAvEpoch epoch,
    long durationTick,
    boolean hasAudio,
    String decoderRevision,
    String analysisModelRevision,
    String profileFingerprint,
    IndexTarget visualTarget,
    IndexTarget audioTarget,
    int chunkSeconds,
    String windowManifestSha256,
    List<VideoAvPublishedWindow> windows,
    VideoAvRouteReceipt visualReceipt,
    VideoAvRouteReceipt audioReceipt,
    long createdAtMs) {
  public VideoAvPublication {
    if (id == null || !UUID.fromString(id).toString().equals(id)) {
      throw ModelValues.invalid();
    }
    ModelValues.indexIdentity(workspaceId);
    ModelValues.indexIdentity(documentId);
    ModelValues.indexIdentity(sourceRevisionId);
    ModelValues.identifier(filename, 255);
    VideoAvProfile.hash(sourceSha256);
    VideoAvProfile.hash(windowManifestSha256);
    var targets = new VideoAvTargets(visualTarget, audioTarget);
    if (epoch == null
        || durationTick < 1
        || durationTick > epoch.durationLimit(600)
        || mediaType == null
        || !List.of("video/mp4", "video/quicktime", "video/webm", "video/x-matroska")
            .contains(mediaType)
        || sizeBytes < 1
        || sizeBytes > 20 * 1024 * 1024
        || windows == null
        || windows.isEmpty()
        || windows.size() > 1201
        || createdAtMs < 1
        || !VideoAvProfile.fingerprint(
                targets, analysisModelRevision, decoderRevision, chunkSeconds)
            .equals(profileFingerprint)) {
      throw ModelValues.invalid();
    }
    long cursor = 0, samples = 0;
    int visualCount = 0, audioCount = 0;
    var ids = new HashSet<String>();
    for (int i = 0; i < windows.size(); i++) {
      var w = windows.get(i);
      if (w == null
          || w.ordinal() != i
          || !w.id().equals(VideoAvProfile.windowId(sourceRevisionId, i))
          || w.startTick() != cursor
          || w.endTick() - cursor > epoch.durationLimit(chunkSeconds)
          || !ids.add(w.id())) {
        throw ModelValues.invalid();
      }
      if (w.video() != null) {
        visualCount++;
        if (!w.visualPhysicalId()
            .equals(VideoAvProfile.physicalSegmentId(id, VideoAvRoute.VISUAL, w.id()))) {
          throw ModelValues.invalid();
        }
      }
      if (w.audio() != null) {
        audioCount++;
        if (!hasAudio
            || w.audio().startSample() != samples
            || w.audio().startSample() != epoch.sampleAt(w.startTick())
            || w.audio().endSample() > epoch.sampleAt(w.endTick())
            || !w.audioPhysicalId()
                .equals(VideoAvProfile.physicalSegmentId(id, VideoAvRoute.AUDIO, w.id()))) {
          throw ModelValues.invalid();
        }
        samples = w.audio().endSample();
      }
      cursor = w.endTick();
    }
    if (cursor != durationTick
        || visualCount == 0
        || hasAudio != (audioCount > 0)
        || !windowManifestSha256.equals(
            VideoAvProfile.windowManifestSha256(epoch, durationTick, hasAudio, windows))) {
      throw ModelValues.invalid();
    }
    validateReceipt(
        visualReceipt,
        VideoAvRoute.VISUAL,
        visualCount,
        sourceSha256,
        id,
        visualTarget,
        epoch,
        windowManifestSha256,
        workspaceId,
        documentId,
        windows);
    validateReceipt(
        audioReceipt,
        VideoAvRoute.AUDIO,
        audioCount,
        sourceSha256,
        id,
        audioTarget,
        epoch,
        windowManifestSha256,
        workspaceId,
        documentId,
        windows);
    windows = List.copyOf(windows);
  }

  private static void validateReceipt(
      VideoAvRouteReceipt receipt,
      VideoAvRoute role,
      int count,
      String source,
      String generation,
      IndexTarget target,
      VideoAvEpoch epoch,
      String windowManifest,
      String workspace,
      String doc,
      List<VideoAvPublishedWindow> windows) {
    if (receipt == null || receipt.route() != role || receipt.count() != count) {
      throw ModelValues.invalid();
    }
    String manifest =
        count == 0
            ? VideoAvProfile.absenceSha256(source, generation, target, role, epoch, windowManifest)
            : VideoAvProfile.routeManifestSha256(workspace, doc, generation, role, windows);
    if (!manifest.equals(receipt.manifestSha256())
        || (count > 0
            && !receipt.verified().projectionIdentity().equals(target.projectionIdentity()))) {
      throw ModelValues.invalid();
    }
  }

  public int windowCount() {
    return windows.size();
  }

  public int videoWindowCount() {
    return visualReceipt.count();
  }

  public int audioWindowCount() {
    return audioReceipt.count();
  }

  public String manifestSha256() {
    return VideoAvProfile.publicationManifestSha256(this);
  }

  public VideoAvTargets targets() {
    return new VideoAvTargets(visualTarget, audioTarget);
  }

  @Override
  public String toString() {
    return "VideoAvPublication[redacted]";
  }
}
