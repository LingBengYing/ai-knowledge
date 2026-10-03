package com.evidence.rag.model.domain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Exact identities for complete audiovisual material, independent of all historical indexes. */
public final class VideoAvProfile {
  private VideoAvProfile() {}

  public static String fingerprint(
      VideoAvTargets targets, String analysis, String decoder, int chunk) {
    if (targets == null || chunk < 1 || chunk > 30) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(analysis, 200);
    ModelValues.identifier(decoder, 200);
    var values =
        new ArrayList<>(
            List.of("java-video-av-profile-v1", analysis, decoder, Integer.toString(chunk)));
    target(values, targets.visual());
    target(values, targets.audio());
    return digest(values);
  }

  public static String windowId(String revision, int ordinal) {
    ModelValues.indexIdentity(revision);
    if (ordinal < 0 || ordinal >= 1201) {
      throw ModelValues.invalid();
    }
    return "av-" + digest(List.of("video-av-window-v1", revision, Integer.toString(ordinal)));
  }

  public static String physicalSegmentId(String generation, VideoAvRoute route, String window) {
    ModelValues.indexIdentity(generation);
    ModelValues.indexIdentity(window);
    if (route == null) {
      throw ModelValues.invalid();
    }
    return "seg-"
        + digest(List.of("evidence-rag-physical-segment-v1", generation, route.name(), window));
  }

  public static String framesManifestSha256(List<VideoAvFrameTiming> frames) {
    if (frames == null || frames.isEmpty()) {
      throw ModelValues.invalid();
    }
    var values = new ArrayList<>(List.of("video-av-frames-v1", Integer.toString(frames.size())));
    for (var f : frames) {
      if (f == null) {
        throw ModelValues.invalid();
      }
      values.addAll(
          List.of(
              Integer.toString(f.sourceOrdinal()),
              Long.toString(f.localTick()),
              Long.toString(f.durationTick()),
              Integer.toString(f.width()),
              Integer.toString(f.height()),
              f.pixelSha256()));
    }
    return digest(values);
  }

  public static String windowManifestSha256(VideoAvCompilation compilation) {
    if (compilation == null) {
      throw ModelValues.invalid();
    }
    var values =
        windowValues(
            compilation.epoch(),
            compilation.durationTick(),
            compilation.hasAudio(),
            compilation.windows().size());
    for (var w : compilation.windows()) {
      metadata(
          values,
          w.id(),
          w.ordinal(),
          w.startTick(),
          w.endTick(),
          VideoAvVideoMetadata.from(w.video()),
          VideoAvAudioMetadata.from(w.audio()));
    }
    return digest(values);
  }

  public static String windowManifestSha256(
      VideoAvEpoch epoch, long duration, boolean audio, List<VideoAvPublishedWindow> windows) {
    if (windows == null) {
      throw ModelValues.invalid();
    }
    var values = windowValues(epoch, duration, audio, windows.size());
    for (var w : windows) {
      metadata(values, w.id(), w.ordinal(), w.startTick(), w.endTick(), w.video(), w.audio());
    }
    return digest(values);
  }

  private static ArrayList<String> windowValues(
      VideoAvEpoch epoch, long duration, boolean audio, int count) {
    if (epoch == null || count < 1 || count > 1201) {
      throw ModelValues.invalid();
    }
    var values =
        new ArrayList<>(
            List.of("video-av-windows-v1", Long.toString(duration), Boolean.toString(audio)));
    epoch(values, epoch);
    values.add(Integer.toString(count));
    return values;
  }

  private static void metadata(
      List<String> values,
      String id,
      int ordinal,
      long start,
      long end,
      VideoAvVideoMetadata video,
      VideoAvAudioMetadata audio) {
    values.addAll(List.of(id, Integer.toString(ordinal), Long.toString(start), Long.toString(end)));
    if (video == null) {
      values.add("VIDEO_ABSENT");
    } else {
      values.addAll(
          List.of(
              "VIDEO",
              video.clipSha256(),
              Integer.toString(video.frameCount()),
              video.framesManifestSha256(),
              Long.toString(video.firstLocalTick()),
              Long.toString(video.endLocalTick())));
    }
    if (audio == null) {
      values.add("AUDIO_ABSENT");
    } else {
      values.addAll(
          List.of(
              "AUDIO",
              audio.pcmSha256(),
              audio.wavSha256(),
              Long.toString(audio.startSample()),
              Long.toString(audio.endSample()),
              Integer.toString(audio.sampleRate())));
    }
  }

  public static String absenceSha256(
      String source,
      String generation,
      IndexTarget target,
      VideoAvRoute route,
      VideoAvEpoch epoch,
      String windows) {
    hash(source);
    hash(windows);
    ModelValues.indexIdentity(generation);
    var values =
        new ArrayList<>(
            List.of("video-av-route-ABSENT-v1", source, generation, route.name(), windows));
    target(values, target);
    epoch(values, epoch);
    return digest(values);
  }

  public static String routeManifestSha256(
      String workspace,
      String document,
      String generation,
      VideoAvRoute route,
      List<VideoAvPublishedWindow> windows) {
    var entries = new TreeMap<String, String>();
    for (var w : windows) {
      String physical = route == VideoAvRoute.VISUAL ? w.visualPhysicalId() : w.audioPhysicalId();
      String entry = route == VideoAvRoute.VISUAL ? w.visualEntrySha256() : w.audioEntrySha256();
      if (physical != null && entries.put(physical, entry) != null) {
        throw ModelValues.invalid();
      }
    }
    if (entries.isEmpty()) {
      throw ModelValues.invalid();
    }
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      for (String s :
          List.of("evidence-rag-revision-manifest-v1", workspace, document, generation)) {
        text(out, s);
      }
      out.writeInt(entries.size());
      for (var e : entries.entrySet()) {
        text(out, e.getKey());
        text(out, e.getValue());
      }
      return ModelValues.sha256(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException("Video AV identity failed");
    }
  }

  public static boolean compiledMatches(
      VideoAvPublication publication, VideoAvCompilation compilation) {
    return publication.sourceSha256().equals(compilation.sourceSha256())
        && publication.decoderRevision().equals(compilation.decoderRevision())
        && publication.epoch().equals(compilation.epoch())
        && publication.durationTick() == compilation.durationTick()
        && publication.hasAudio() == compilation.hasAudio()
        && publication.windowManifestSha256().equals(windowManifestSha256(compilation));
  }

  public static String publicationManifestSha256(VideoAvPublication p) {
    var values =
        new ArrayList<>(
            List.of(
                "video-av-publication-v1",
                p.id(),
                p.workspaceId(),
                p.documentId(),
                p.sourceRevisionId(),
                p.sourceSha256(),
                p.filename(),
                p.mediaType(),
                Long.toString(p.sizeBytes()),
                p.decoderRevision(),
                p.analysisModelRevision(),
                p.profileFingerprint(),
                p.windowManifestSha256()));
    epoch(values, p.epoch());
    target(values, p.visualTarget());
    target(values, p.audioTarget());
    for (var r : List.of(p.visualReceipt(), p.audioReceipt())) {
      values.addAll(List.of(r.route().name(), Integer.toString(r.count()), r.manifestSha256()));
    }
    return digest(values);
  }

  private static void target(List<String> values, IndexTarget t) {
    values.addAll(
        List.of(
            t.embeddingIdentity(),
            t.projectionIdentity(),
            t.modelRevision(),
            Integer.toString(t.dimensions())));
  }

  private static void epoch(List<String> values, VideoAvEpoch e) {
    values.addAll(
        List.of(
            Long.toString(e.sourceFirstPts()),
            Long.toString(e.sourceTimeBaseNumerator()),
            Long.toString(e.sourceTimeBaseDenominator()),
            Long.toString(e.ticksPerSecond())));
  }

  private static String digest(List<String> values) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      for (String value : values) {
        text(out, value);
      }
      return ModelValues.sha256(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException("Video AV identity failed");
    }
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    byte[] b = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(b.length);
    out.write(b);
  }

  public static void hash(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
  }
}
