package com.evidence.rag;

import com.evidence.rag.client.model.GeminiVideoAvEmbeddingModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoAvAudioMetadata;
import com.evidence.rag.model.domain.VideoAvBuildClaim;
import com.evidence.rag.model.domain.VideoAvClip;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvEpoch;
import com.evidence.rag.model.domain.VideoAvFrameTiming;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvPublication;
import com.evidence.rag.model.domain.VideoAvPublishedWindow;
import com.evidence.rag.model.domain.VideoAvReceipt;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvRouteReceipt;
import com.evidence.rag.model.domain.VideoAvTargets;
import com.evidence.rag.model.domain.VideoAvVideoMetadata;
import com.evidence.rag.model.domain.VideoAvWindow;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/** Small trusted metadata fixture; native media fidelity is checked by the explicit native IT. */
public final class VideoAvTestFixture {
  public static final Actor OWNER = new Actor("org", "owner");
  public static final Duration BUDGET = Duration.ofSeconds(5);

  private VideoAvTestFixture() {}

  public static byte[] raw() {
    byte[] b = new byte[16];
    System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, b, 4, 4);
    return b;
  }

  public static DocumentOriginal original() {
    byte[] raw = raw();
    return new DocumentOriginal(
        "doc", "rev", "clip.mp4", "video", "video/mp4", ModelValues.sha256(raw), raw.length, raw);
  }

  public static GeminiVideoAvEmbeddingModels.Configuration models() {
    return new GeminiVideoAvEmbeddingModels.Configuration(
        new OpenAiCompatibleModels.Endpoint(
            URI.create("http://127.0.0.1:1"), "video-av", "fixture-key"),
        "embedding-v1",
        2,
        "decoder-v1",
        BUDGET,
        65536,
        true);
  }

  public static MilvusRestProjection.Settings projection(VideoAvRoute route) {
    return new MilvusRestProjection.Settings(
        URI.create("http://127.0.0.1:1"),
        "",
        "default",
        "java_video_av_" + route.name().toLowerCase(java.util.Locale.ROOT),
        "org",
        models().revision(),
        2,
        BUDGET,
        65536,
        true);
  }

  public static VideoAvTargets targets() {
    var m = models();
    return new VideoAvTargets(
        new IndexTarget(m.revision(), projection(VideoAvRoute.VISUAL).identity(), m.revision(), 2),
        new IndexTarget(m.revision(), projection(VideoAvRoute.AUDIO).identity(), m.revision(), 2));
  }

  public static VideoAvClip clip(int ordinal, long duration) {
    byte[] bytes = {(byte) (ordinal + 1)};
    var frames =
        List.of(
            new VideoAvFrameTiming(
                ordinal, 0, duration, 16, 16, ModelValues.sha256(new byte[] {(byte) ordinal})));
    return new VideoAvClip(
        bytes,
        ModelValues.sha256(bytes),
        0,
        duration,
        frames,
        VideoAvProfile.framesManifestSha256(frames));
  }

  public static VideoAvCompilation compilation(DocumentOriginal original, boolean hasAudio) {
    var windows = new ArrayList<VideoAvWindow>();
    var epoch = new VideoAvEpoch(180001, 1, 90000, 720000);
    long second = epoch.ticksPerSecond();
    for (int i = 0; i < 2; i++) {
      byte[] pcm = new byte[32000];
      if (i == 0) {
        pcm[0] = 7;
      }
      windows.add(
          new VideoAvWindow(
              VideoAvProfile.windowId(original.revisionId(), i),
              i,
              i * second,
              (i + 1) * second,
              clip(i, second),
              hasAudio
                  ? new AudioWaveform(
                      original.sourceSha256(), "decoder-v1", i * 16000L, (i + 1) * 16000L, pcm)
                  : null));
    }
    long duration = 2 * second;
    if (hasAudio) {
      duration += second / 16000;
      windows.add(
          new VideoAvWindow(
              VideoAvProfile.windowId(original.revisionId(), 2),
              2,
              2 * second,
              duration,
              null,
              new AudioWaveform(
                  original.sourceSha256(), "decoder-v1", 32000, 32001, new byte[] {9, 0})));
    }
    return new VideoAvCompilation(
        original.sourceSha256(), "decoder-v1", epoch, duration, hasAudio, windows);
  }

  public static VideoAvBuildClaim claim(boolean hasAudio) {
    var original = original();
    var targets = targets();
    return new VideoAvBuildClaim(
        OWNER,
        original,
        targets,
        UUID.randomUUID().toString(),
        "analysis-v1",
        "decoder-v1",
        1,
        compilation(original, hasAudio),
        VideoAvProfile.fingerprint(targets, "analysis-v1", "decoder-v1", 1));
  }

  public static VideoAvReceipt receipt(VideoAvBuildClaim claim) {
    var entries = new ArrayList<VideoAvReceipt.Entry>();
    var receipts = new ArrayList<VideoAvRouteReceipt>();
    for (var route : VideoAvRoute.values()) {
      var digests = new TreeMap<String, String>();
      for (var w : claim.compilation().windows()) {
        String sha =
            route == VideoAvRoute.VISUAL
                ? (w.video() == null ? null : w.video().sha256())
                : (w.audio() == null ? null : w.audio().pcmSha256());
        if (sha == null) {
          continue;
        }
        String physical = VideoAvProfile.physicalSegmentId(claim.generationId(), route, w.id());
        var projected =
            new RetrievalProjection.Entry(
                physical,
                claim.actor().workspaceId(),
                claim.original().documentId(),
                claim.generationId(),
                sha,
                List.of(0.25, 0.75));
        String digest = RetrievalProjection.entryDigest(projected);
        digests.put(physical, digest);
        entries.add(new VideoAvReceipt.Entry(w.id(), route, physical, projected.vector(), digest));
      }
      var target = claim.targets().target(route);
      String sha =
          digests.isEmpty()
              ? VideoAvProfile.absenceSha256(
                  claim.original().sourceSha256(),
                  claim.generationId(),
                  target,
                  route,
                  claim.compilation().epoch(),
                  VideoAvProfile.windowManifestSha256(claim.compilation()))
              : new RetrievalProjection.RevisionManifest(
                      claim.actor().workspaceId(),
                      claim.original().documentId(),
                      claim.generationId(),
                      digests)
                  .sha256();
      receipts.add(
          new VideoAvRouteReceipt(
              route,
              digests.size(),
              sha,
              digests.isEmpty()
                  ? null
                  : new VerifiedRevision(target.projectionIdentity(), sha, digests.size())));
    }
    return new VideoAvReceipt(entries, receipts.get(0), receipts.get(1));
  }

  public static VideoAvPublication publication(VideoAvBuildClaim claim) {
    var receipt = receipt(claim);
    var entries = new HashMap<String, VideoAvReceipt.Entry>();
    for (var e : receipt.entries()) {
      entries.put(e.route() + ":" + e.windowId(), e);
    }
    var windows = new ArrayList<VideoAvPublishedWindow>();
    for (var w : claim.compilation().windows()) {
      var visual = entries.get(VideoAvRoute.VISUAL + ":" + w.id());
      var audio = entries.get(VideoAvRoute.AUDIO + ":" + w.id());
      windows.add(
          new VideoAvPublishedWindow(
              w.id(),
              w.ordinal(),
              w.startTick(),
              w.endTick(),
              VideoAvVideoMetadata.from(w.video()),
              VideoAvAudioMetadata.from(w.audio()),
              visual == null ? null : visual.physicalSegmentId(),
              visual == null ? null : visual.entrySha256(),
              audio == null ? null : audio.physicalSegmentId(),
              audio == null ? null : audio.entrySha256()));
    }
    var o = claim.original();
    var c = claim.compilation();
    return new VideoAvPublication(
        claim.generationId(),
        claim.actor().workspaceId(),
        o.documentId(),
        o.revisionId(),
        o.sourceSha256(),
        o.filename(),
        o.mediaType(),
        o.sizeBytes(),
        c.epoch(),
        c.durationTick(),
        c.hasAudio(),
        claim.decoderRevision(),
        claim.analysisModelRevision(),
        claim.profileFingerprint(),
        claim.targets().visual(),
        claim.targets().audio(),
        claim.chunkSeconds(),
        VideoAvProfile.windowManifestSha256(c),
        windows,
        receipt.visualReceipt(),
        receipt.audioReceipt(),
        System.currentTimeMillis());
  }
}
