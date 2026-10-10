package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.GeminiVideoAvEmbeddingModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoAvBuildClaim;
import com.evidence.rag.model.domain.VideoAvClip;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvEpoch;
import com.evidence.rag.model.domain.VideoAvFrameTiming;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvReceipt;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvRouteReceipt;
import com.evidence.rag.model.domain.VideoAvTargets;
import com.evidence.rag.model.domain.VideoAvWindow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

/** Independent, bounded complete-material protocol. No historical packet format is changed. */
final class VideoAvIndexProtocol {
  static final int MAGIC = 0x52414756, VERSION = 1;
  static final int MAX_REQUEST = 128 * 1024 * 1024, MAX_OUTPUT = 128 * 1024 * 1024;

  private VideoAvIndexProtocol() {}

  record Request(
      GeminiVideoAvEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings visualProjection,
      MilvusRestProjection.Settings audioProjection,
      Duration timeout,
      VideoAvBuildClaim claim,
      IndexWorkerLifetime.Parent parent) {
    @Override
    public String toString() {
      return "VideoAvIndexRequest[redacted]";
    }
  }

  static Request request(
      GeminiVideoAvEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings visual,
      MilvusRestProjection.Settings audio,
      Duration timeout,
      VideoAvBuildClaim claim) {
    var value =
        new Request(models, visual, audio, timeout, claim, IndexWorkerLifetime.Parent.current());
    validate(value);
    return value;
  }

  static void validateSettings(
      GeminiVideoAvEmbeddingModels.Configuration m,
      MilvusRestProjection.Settings v,
      MilvusRestProjection.Settings a,
      Duration timeout) {
    if (m == null
        || v == null
        || a == null
        || timeout == null
        || timeout.compareTo(Duration.ofMillis(10)) < 0
        || timeout.compareTo(Duration.ofMillis(120000)) > 0
        || m.dimensions() != v.dimension()
        || m.dimensions() != a.dimension()
        || !v.workspaceId().equals(a.workspaceId())
        || !v.embeddingIdentity().equals(m.revision())
        || !a.embeddingIdentity().equals(m.revision())
        || (v.endpoint().equals(a.endpoint())
            && v.database().equals(a.database())
            && v.collection().equals(a.collection()))) {
      throw invalid();
    }
  }

  private static void validate(Request r) {
    validateSettings(r.models(), r.visualProjection(), r.audioProjection(), r.timeout());
    var c = r.claim();
    if (c == null
        || r.parent() == null
        || !r.models().decoderRevision().equals(c.decoderRevision())) {
      throw invalid();
    }
    for (var route : VideoAvRoute.values()) {
      var s = route == VideoAvRoute.VISUAL ? r.visualProjection() : r.audioProjection();
      var t = c.targets().target(route);
      if (!s.workspaceId().equals(c.actor().workspaceId())
          || !s.identity().equals(t.projectionIdentity())
          || !t.embeddingIdentity().equals(r.models().revision())
          || !t.modelRevision().equals(r.models().revision())
          || t.dimensions() != r.models().dimensions()) {
        throw invalid();
      }
    }
  }

  static void writeRequest(OutputStream output, Request r) throws IOException {
    validate(r);
    var bytes = new ByteArrayOutputStream();
    var w = new DataOutputStream(bytes);
    w.writeInt(MAGIC);
    w.writeInt(VERSION);
    w.writeLong(r.timeout().toNanos());
    var m = r.models();
    string(w, m.endpoint().baseUrl().toString());
    string(w, m.endpoint().model());
    string(w, m.endpoint().apiKey());
    string(w, m.modelRevision());
    w.writeInt(m.dimensions());
    string(w, m.decoderRevision());
    w.writeLong(m.deadline().toNanos());
    w.writeInt(m.maxResponseBytes());
    w.writeBoolean(m.allowLoopbackHttp());
    projection(w, r.visualProjection());
    projection(w, r.audioProjection());
    var c = r.claim();
    string(w, c.actor().workspaceId());
    string(w, c.actor().principalId());
    var o = c.original();
    for (String s :
        List.of(
            o.documentId(),
            o.revisionId(),
            o.filename(),
            o.documentType(),
            o.mediaType(),
            o.sourceSha256())) {
      string(w, s);
    }
    binary(w, o.content());
    target(w, c.targets().visual());
    target(w, c.targets().audio());
    string(w, c.generationId());
    string(w, c.analysisModelRevision());
    string(w, c.decoderRevision());
    w.writeInt(c.chunkSeconds());
    string(w, c.profileFingerprint());
    var compilation = c.compilation();
    var e = compilation.epoch();
    w.writeLong(e.sourceFirstPts());
    w.writeLong(e.sourceTimeBaseNumerator());
    w.writeLong(e.sourceTimeBaseDenominator());
    w.writeLong(e.ticksPerSecond());
    w.writeLong(compilation.durationTick());
    w.writeBoolean(compilation.hasAudio());
    w.writeInt(compilation.windows().size());
    for (var win : compilation.windows()) {
      string(w, win.id());
      w.writeInt(win.ordinal());
      w.writeLong(win.startTick());
      w.writeLong(win.endTick());
      w.writeBoolean(win.video() != null);
      if (win.video() != null) {
        var clip = win.video();
        binary(w, clip.content());
        string(w, clip.sha256());
        w.writeLong(clip.firstLocalTick());
        w.writeLong(clip.endLocalTick());
        string(w, clip.framesManifestSha256());
        w.writeInt(clip.frames().size());
        for (var f : clip.frames()) {
          w.writeInt(f.sourceOrdinal());
          w.writeLong(f.localTick());
          w.writeLong(f.durationTick());
          w.writeInt(f.width());
          w.writeInt(f.height());
          string(w, f.pixelSha256());
        }
      }
      w.writeBoolean(win.audio() != null);
      if (win.audio() != null) {
        w.writeLong(win.audio().startSample());
        w.writeLong(win.audio().endSample());
        binary(w, win.audio().pcm());
      }
    }
    w.writeLong(r.parent().pid());
    w.writeLong(r.parent().started().getEpochSecond());
    w.writeInt(r.parent().started().getNano());
    if (bytes.size() > MAX_REQUEST) {
      throw invalid();
    }
    bytes.writeTo(output);
    output.flush();
  }

  static Request readRequest(InputStream input) throws IOException {
    byte[] packet = input.readNBytes(MAX_REQUEST + 1);
    if (packet.length > MAX_REQUEST) {
      throw invalid();
    }
    var r = new DataInputStream(new ByteArrayInputStream(packet));
    header(r);
    var timeout = Duration.ofNanos(r.readLong());
    var models =
        new GeminiVideoAvEmbeddingModels.Configuration(
            new OpenAiCompatibleModels.Endpoint(
                URI.create(string(r, 4096)), string(r, 256), string(r, 4096)),
            string(r, 160),
            r.readInt(),
            string(r, 200),
            Duration.ofNanos(r.readLong()),
            r.readInt(),
            flag(r));
    var visual = projection(r);
    var audio = projection(r);
    var actor = new Actor(string(r, 800), string(r, 800));
    String document = string(r, 128),
        revision = string(r, 128),
        filename = string(r, 1024),
        type = string(r, 16),
        mime = string(r, 128),
        sha = string(r, 64);
    byte[] source = binary(r, 20 * 1024 * 1024);
    var original =
        new DocumentOriginal(document, revision, filename, type, mime, sha, source.length, source);
    var targets = new VideoAvTargets(target(r), target(r));
    String generation = string(r, 128), analysis = string(r, 200), decoder = string(r, 200);
    int chunk = r.readInt();
    String profile = string(r, 64);
    var epoch = new VideoAvEpoch(r.readLong(), r.readLong(), r.readLong(), r.readLong());
    long duration = r.readLong();
    boolean hasAudio = flag(r);
    int count = bound(r.readInt(), 1, 1201);
    var windows = new ArrayList<VideoAvWindow>();
    long mediaBytes = 0;
    for (int i = 0; i < count; i++) {
      String id = string(r, 128);
      int ordinal = r.readInt();
      long start = r.readLong(), end = r.readLong();
      VideoAvClip clip = null;
      if (flag(r)) {
        byte[] content = binary(r, 8 * 1024 * 1024);
        mediaBytes += content.length;
        if (mediaBytes > 64L * 1024 * 1024) {
          throw invalid();
        }
        String clipSha = string(r, 64);
        long first = r.readLong(), last = r.readLong();
        String framesSha = string(r, 64);
        int frames = bound(r.readInt(), 1, 1000000);
        var timings = new ArrayList<VideoAvFrameTiming>();
        for (int j = 0; j < frames; j++) {
          timings.add(
              new VideoAvFrameTiming(
                  r.readInt(),
                  r.readLong(),
                  r.readLong(),
                  r.readInt(),
                  r.readInt(),
                  string(r, 64)));
        }
        clip = new VideoAvClip(content, clipSha, first, last, timings, framesSha);
      }
      AudioWaveform wave = null;
      if (flag(r)) {
        long first = r.readLong(), last = r.readLong();
        wave = new AudioWaveform(sha, decoder, first, last, binary(r, 960000));
      }
      windows.add(new VideoAvWindow(id, ordinal, start, end, clip, wave));
    }
    var parent =
        new IndexWorkerLifetime.Parent(
            r.readLong(), Instant.ofEpochSecond(r.readLong(), r.readInt()));
    if (r.available() != 0) {
      throw invalid();
    }
    var compilation = new VideoAvCompilation(sha, decoder, epoch, duration, hasAudio, windows);
    var request =
        new Request(
            models,
            visual,
            audio,
            timeout,
            new VideoAvBuildClaim(
                actor,
                original,
                targets,
                generation,
                analysis,
                decoder,
                chunk,
                compilation,
                profile),
            parent);
    validate(request);
    return request;
  }

  static byte[] encode(VideoAvReceipt receipt) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var w = new DataOutputStream(bytes);
    w.writeInt(MAGIC);
    w.writeInt(VERSION);
    w.writeByte(0);
    w.writeInt(receipt.entries().size());
    for (var e : receipt.entries()) {
      string(w, e.windowId());
      w.writeByte(e.route().ordinal());
      string(w, e.physicalSegmentId());
      string(w, e.entrySha256());
      w.writeInt(e.vector().size());
      for (double x : e.vector()) {
        w.writeFloat((float) x);
      }
    }
    receipt(w, receipt.visualReceipt());
    receipt(w, receipt.audioReceipt());
    if (bytes.size() > MAX_OUTPUT) {
      throw invalid();
    }
    return bytes.toByteArray();
  }

  static VideoAvReceipt decode(byte[] bytes, Request request) throws IOException {
    if (bytes.length > MAX_OUTPUT) {
      throw invalid();
    }
    var r = new DataInputStream(new ByteArrayInputStream(bytes));
    header(r);
    int status = r.readUnsignedByte();
    if (status != 0) {
      if (status > 2 || r.available() != 0) {
        throw invalid();
      }
      throw new ProcessVideoAvIndexer.Failure(
          status == 2 ? "video_av_index_timeout" : "video_av_index_failed");
    }
    int count = bound(r.readInt(), 1, 2402);
    var entries = new ArrayList<VideoAvReceipt.Entry>();
    for (int i = 0; i < count; i++) {
      String id = string(r, 128);
      int role = bound(r.readUnsignedByte(), 0, 1);
      String physical = string(r, 128), digest = string(r, 64);
      int dimensions = bound(r.readInt(), 2, 3072);
      var vector = new ArrayList<Double>();
      for (int j = 0; j < dimensions; j++) {
        vector.add((double) r.readFloat());
      }
      entries.add(
          new VideoAvReceipt.Entry(id, VideoAvRoute.values()[role], physical, vector, digest));
    }
    var value =
        new VideoAvReceipt(
            entries, receipt(r, VideoAvRoute.VISUAL), receipt(r, VideoAvRoute.AUDIO));
    if (r.available() != 0) {
      throw invalid();
    }
    verify(request.claim(), value);
    return value;
  }

  static void verify(VideoAvBuildClaim claim, VideoAvReceipt receipt) {
    if (receipt == null) {
      throw invalid();
    }
    var byKey = new HashMap<String, VideoAvReceipt.Entry>();
    for (var entry : receipt.entries()) {
      if (byKey.put(entry.route() + ":" + entry.windowId(), entry) != null) {
        throw invalid();
      }
    }
    int total = 0;
    for (var route : VideoAvRoute.values()) {
      var digests = new TreeMap<String, String>();
      var target = claim.targets().target(route);
      for (var window : claim.compilation().windows()) {
        String media =
            route == VideoAvRoute.VISUAL
                ? (window.video() == null ? null : window.video().sha256())
                : (window.audio() == null ? null : window.audio().pcmSha256());
        if (media == null) {
          continue;
        }
        var entry = byKey.get(route + ":" + window.id());
        if (entry == null) {
          throw invalid();
        }
        String physical =
            VideoAvProfile.physicalSegmentId(claim.generationId(), route, window.id());
        var projected =
            new RetrievalProjection.Entry(
                physical,
                claim.actor().workspaceId(),
                claim.original().documentId(),
                claim.generationId(),
                media,
                entry.vector());
        String digest = RetrievalProjection.entryDigest(projected);
        if (!physical.equals(entry.physicalSegmentId())
            || !digest.equals(entry.entrySha256())
            || entry.vector().size() != target.dimensions()) {
          throw invalid();
        }
        digests.put(physical, digest);
        total++;
      }
      var saved = route == VideoAvRoute.VISUAL ? receipt.visualReceipt() : receipt.audioReceipt();
      String manifest =
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
      if (saved.route() != route
          || saved.count() != digests.size()
          || !saved.manifestSha256().equals(manifest)
          || (!digests.isEmpty()
              && !saved.verified().projectionIdentity().equals(target.projectionIdentity()))) {
        throw invalid();
      }
    }
    if (total != receipt.entries().size()) {
      throw invalid();
    }
  }

  static byte[] failure(int code) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var w = new DataOutputStream(bytes);
    w.writeInt(MAGIC);
    w.writeInt(VERSION);
    w.writeByte(code);
    return bytes.toByteArray();
  }

  private static void receipt(DataOutputStream w, VideoAvRouteReceipt r) throws IOException {
    w.writeInt(r.count());
    string(w, r.manifestSha256());
    if (r.verified() != null) {
      string(w, r.verified().projectionIdentity());
    }
  }

  private static VideoAvRouteReceipt receipt(DataInputStream r, VideoAvRoute role)
      throws IOException {
    int count = bound(r.readInt(), 0, 1201);
    String sha = string(r, 64);
    return new VideoAvRouteReceipt(
        role, count, sha, count == 0 ? null : new VerifiedRevision(string(r, 64), sha, count));
  }

  private static void projection(DataOutputStream w, MilvusRestProjection.Settings p)
      throws IOException {
    for (String s :
        List.of(
            p.endpoint().toString(),
            p.token(),
            p.database(),
            p.collection(),
            p.workspaceId(),
            p.embeddingIdentity())) {
      string(w, s);
    }
    w.writeInt(p.dimension());
    w.writeLong(p.timeout().toNanos());
    w.writeInt(p.maxResponseBytes());
    w.writeBoolean(p.allowLoopbackHttp());
  }

  private static MilvusRestProjection.Settings projection(DataInputStream r) throws IOException {
    return new MilvusRestProjection.Settings(
        URI.create(string(r, 4096)),
        string(r, 4096),
        string(r, 128),
        string(r, 128),
        string(r, 128),
        string(r, 200),
        r.readInt(),
        Duration.ofNanos(r.readLong()),
        r.readInt(),
        flag(r));
  }

  private static void target(DataOutputStream w, IndexTarget t) throws IOException {
    string(w, t.embeddingIdentity());
    string(w, t.projectionIdentity());
    string(w, t.modelRevision());
    w.writeInt(t.dimensions());
  }

  private static IndexTarget target(DataInputStream r) throws IOException {
    return new IndexTarget(string(r, 200), string(r, 64), string(r, 200), r.readInt());
  }

  private static void binary(DataOutputStream w, byte[] bytes) throws IOException {
    w.writeInt(bytes.length);
    w.write(bytes);
  }

  private static byte[] binary(DataInputStream r, int max) throws IOException {
    int n = bound(r.readInt(), 1, max);
    if (n > r.available()) {
      throw invalid();
    }
    return r.readNBytes(n);
  }

  private static void string(DataOutputStream w, String value) throws IOException {
    byte[] b = value.getBytes(StandardCharsets.UTF_8);
    w.writeInt(b.length);
    w.write(b);
  }

  private static String string(DataInputStream r, int max) throws IOException {
    int n = bound(r.readInt(), 0, max);
    if (n > r.available()) {
      throw invalid();
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(r.readNBytes(n)))
          .toString();
    } catch (java.nio.charset.CharacterCodingException invalid) {
      throw invalid();
    }
  }

  private static boolean flag(DataInputStream r) throws IOException {
    int x = r.readUnsignedByte();
    if (x > 1) {
      throw invalid();
    }
    return x == 1;
  }

  private static int bound(int x, int min, int max) {
    if (x < min || x > max) {
      throw invalid();
    }
    return x;
  }

  private static void header(DataInputStream r) throws IOException {
    if (r.readInt() != MAGIC || r.readInt() != VERSION) {
      throw invalid();
    }
  }

  static ProcessVideoAvIndexer.Failure invalid() {
    return new ProcessVideoAvIndexer.Failure("video_av_index_output_invalid");
  }
}
