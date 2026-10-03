package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.GeminiSoundEmbeddingModels;
import com.evidence.rag.client.model.GeminiSoundModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.SoundBuildClaim;
import com.evidence.rag.model.domain.SoundInputSpan;
import com.evidence.rag.model.domain.SoundReceipt;
import com.evidence.rag.model.domain.VerifiedRevision;
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
import java.util.List;
import java.util.TreeMap;

/** Independent complete original-sound protocol; all old protocols remain unchanged. */
final class SoundIndexProtocol {
  static final int MAGIC = 0x52414753;
  static final int VERSION = 1;
  static final int MAX_REQUEST = 40 * 1024 * 1024;
  static final int MAX_OUTPUT = 40 * 1024 * 1024;

  private SoundIndexProtocol() {}

  record Request(
      GeminiSoundModels.Configuration sounds,
      GeminiSoundEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout,
      SoundBuildClaim claim,
      IndexWorkerLifetime.Parent parent) {
    @Override
    public String toString() {
      return "SoundIndexRequest[redacted]";
    }
  }

  static Request request(
      GeminiSoundModels.Configuration sounds,
      GeminiSoundEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout,
      SoundBuildClaim claim) {
    var request =
        new Request(
            sounds, models, projection, timeout, claim, IndexWorkerLifetime.Parent.current());
    validate(request);
    return request;
  }

  static void validateSettings(
      GeminiSoundModels.Configuration sounds,
      GeminiSoundEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout) {
    if (sounds == null
        || models == null
        || projection == null
        || timeout == null
        || timeout.compareTo(Duration.ofMillis(10)) < 0
        || timeout.compareTo(Duration.ofMillis(120000)) > 0
        || models.dimensions() != projection.dimension()) {
      throw invalid();
    }
  }

  private static void validate(Request request) {
    validateSettings(request.sounds(), request.models(), request.projection(), request.timeout());
    var claim = request.claim();
    if (claim == null || request.parent() == null) {
      throw invalid();
    }
    var models = request.models();
    if (!request.sounds().revision().equals(claim.soundModelRevision())
        || !request.projection().workspaceId().equals(claim.actor().workspaceId())
        || !request.projection().embeddingIdentity().equals(claim.target().embeddingIdentity())
        || !models.revision().equals(claim.target().embeddingIdentity())
        || !models.revision().equals(claim.target().modelRevision())
        || !request.projection().identity().equals(claim.target().projectionIdentity())
        || models.dimensions() != claim.target().dimensions()
        || !models.decoderRevision().equals(claim.decoderRevision())) {
      throw invalid();
    }
  }

  static void writeRequest(OutputStream output, Request request) throws IOException {
    validate(request);
    var bytes = new ByteArrayOutputStream();
    var writer = new DataOutputStream(bytes);
    writer.writeInt(MAGIC);
    writer.writeInt(VERSION);
    writer.writeLong(request.timeout().toNanos());
    var sound = request.sounds();
    string(writer, sound.endpoint().baseUrl().toString());
    string(writer, sound.endpoint().model());
    string(writer, sound.endpoint().apiKey());
    string(writer, sound.modelRevision());
    writer.writeLong(sound.deadline().toNanos());
    writer.writeInt(sound.maxResponseBytes());
    writer.writeBoolean(sound.allowLoopbackHttp());
    var model = request.models();
    string(writer, model.endpoint().baseUrl().toString());
    string(writer, model.endpoint().model());
    string(writer, model.endpoint().apiKey());
    string(writer, model.modelRevision());
    writer.writeInt(model.dimensions());
    string(writer, model.decoderRevision());
    writer.writeLong(model.deadline().toNanos());
    writer.writeInt(model.maxResponseBytes());
    writer.writeBoolean(model.allowLoopbackHttp());
    var projection = request.projection();
    for (var value :
        List.of(
            projection.endpoint().toString(),
            projection.token(),
            projection.database(),
            projection.collection(),
            projection.workspaceId(),
            projection.embeddingIdentity())) {
      string(writer, value);
    }
    writer.writeInt(projection.dimension());
    writer.writeLong(projection.timeout().toNanos());
    writer.writeInt(projection.maxResponseBytes());
    writer.writeBoolean(projection.allowLoopbackHttp());
    var claim = request.claim();
    string(writer, claim.actor().workspaceId());
    string(writer, claim.actor().principalId());
    var original = claim.original();
    for (String value :
        List.of(
            original.documentId(),
            original.revisionId(),
            original.filename(),
            original.documentType(),
            original.mediaType(),
            original.sourceSha256())) {
      string(writer, value);
    }
    byte[] source = original.content();
    writer.writeInt(source.length);
    writer.write(source);
    target(writer, claim.target());
    string(writer, claim.generationId());
    string(writer, claim.soundModelRevision());
    string(writer, claim.decoderRevision());
    writer.writeInt(claim.chunkSeconds());
    string(writer, claim.profileFingerprint());
    writer.writeInt(claim.spans().size());
    for (var span : claim.spans()) {
      string(writer, span.id());
      writer.writeInt(span.ordinal());
      var waveform = span.waveform();
      string(writer, waveform.sourceSha256());
      string(writer, waveform.decoderRevision());
      writer.writeLong(waveform.startSample());
      writer.writeLong(waveform.endSample());
      byte[] pcm = waveform.pcm();
      writer.writeInt(pcm.length);
      writer.write(pcm);
    }
    writer.writeLong(request.parent().pid());
    writer.writeLong(request.parent().started().getEpochSecond());
    writer.writeInt(request.parent().started().getNano());
    if (bytes.size() > MAX_REQUEST) {
      throw invalid();
    }
    bytes.writeTo(output);
    output.flush();
  }

  static Request readRequest(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_REQUEST + 1);
    if (bytes.length > MAX_REQUEST) {
      throw invalid();
    }
    var reader = new DataInputStream(new ByteArrayInputStream(bytes));
    header(reader);
    var timeout = Duration.ofNanos(reader.readLong());
    var sounds =
        new GeminiSoundModels.Configuration(
            new OpenAiCompatibleModels.Endpoint(
                URI.create(string(reader, 4096)), string(reader, 256), string(reader, 4096)),
            string(reader, 200),
            Duration.ofNanos(reader.readLong()),
            reader.readInt(),
            flag(reader));
    var model =
        new GeminiSoundEmbeddingModels.Configuration(
            new OpenAiCompatibleModels.Endpoint(
                URI.create(string(reader, 4096)), string(reader, 256), string(reader, 4096)),
            string(reader, 160),
            reader.readInt(),
            string(reader, 800),
            Duration.ofNanos(reader.readLong()),
            reader.readInt(),
            flag(reader));
    var projection =
        new MilvusRestProjection.Settings(
            URI.create(string(reader, 4096)),
            string(reader, 4096),
            string(reader, 128),
            string(reader, 128),
            string(reader, 800),
            string(reader, 128),
            reader.readInt(),
            Duration.ofNanos(reader.readLong()),
            reader.readInt(),
            flag(reader));
    var actor = new Actor(string(reader, 800), string(reader, 800));
    String document = string(reader, 128),
        revision = string(reader, 128),
        filename = string(reader, 1024),
        type = string(reader, 16),
        mime = string(reader, 128),
        sha = string(reader, 64);
    int sourceSize = bounded(reader.readInt(), 1, 20 * 1024 * 1024);
    byte[] source = reader.readNBytes(sourceSize);
    if (source.length != sourceSize) {
      throw invalid();
    }
    var original =
        new DocumentOriginal(document, revision, filename, type, mime, sha, sourceSize, source);
    var target = target(reader);
    String generation = string(reader, 36),
        soundRevision = string(reader, 800),
        decoder = string(reader, 800);
    int chunkSeconds = bounded(reader.readInt(), 1, 30);
    String profile = string(reader, 64);
    int count = bounded(reader.readInt(), 1, 600);
    var spans = new ArrayList<SoundInputSpan>(count);
    for (int i = 0; i < count; i++) {
      String spanId = string(reader, 128);
      int ordinal = reader.readInt();
      String waveSource = string(reader, 64), waveDecoder = string(reader, 800);
      long start = reader.readLong(), end = reader.readLong();
      int size = bounded(reader.readInt(), 2, 960000);
      byte[] pcm = reader.readNBytes(size);
      if (pcm.length != size) {
        throw invalid();
      }
      spans.add(
          new SoundInputSpan(
              spanId, ordinal, new AudioWaveform(waveSource, waveDecoder, start, end, pcm)));
    }
    var parent =
        new IndexWorkerLifetime.Parent(
            reader.readLong(),
            Instant.ofEpochSecond(reader.readLong(), bounded(reader.readInt(), 0, 999999999)));
    if (reader.read() != -1) {
      throw invalid();
    }
    var request =
        new Request(
            sounds,
            model,
            projection,
            timeout,
            new SoundBuildClaim(
                actor,
                original,
                target,
                generation,
                soundRevision,
                decoder,
                chunkSeconds,
                spans,
                profile),
            parent);
    validate(request);
    return request;
  }

  static byte[] encode(SoundReceipt receipt) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var writer = new DataOutputStream(bytes);
    writer.writeInt(MAGIC);
    writer.writeInt(VERSION);
    writer.writeInt(0);
    writer.writeInt(receipt.entries().size());
    for (var entry : receipt.entries()) {
      string(writer, entry.spanId());
      string(writer, entry.physicalSegmentId());
      string(writer, entry.recallText());
      writer.writeInt(entry.vector().size());
      for (double value : entry.vector()) {
        writer.writeFloat((float) value);
      }
      string(writer, entry.entrySha256());
    }
    string(writer, receipt.verified().projectionIdentity());
    string(writer, receipt.verified().manifestSha256());
    writer.writeInt(receipt.verified().segmentCount());
    if (bytes.size() > MAX_OUTPUT) {
      throw invalid();
    }
    return bytes.toByteArray();
  }

  static byte[] failure(int code) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var writer = new DataOutputStream(bytes);
    writer.writeInt(MAGIC);
    writer.writeInt(VERSION);
    writer.writeInt(code);
    return bytes.toByteArray();
  }

  static SoundReceipt decode(byte[] bytes, Request request) throws IOException {
    if (bytes == null || bytes.length > MAX_OUTPUT) {
      throw invalid();
    }
    var reader = new DataInputStream(new ByteArrayInputStream(bytes));
    header(reader);
    int status = reader.readInt();
    if (status != 0) {
      if (reader.read() != -1 || (status != 1 && status != 2)) {
        throw invalid();
      }
      throw new ProcessSoundIndexer.Failure(
          status == 2 ? "sound_index_timeout" : "sound_index_unavailable");
    }
    int count = bounded(reader.readInt(), 1, 600);
    if (count != request.claim().spans().size()) {
      throw invalid();
    }
    var entries = new ArrayList<SoundReceipt.Entry>(count);
    try {
      for (int i = 0; i < count; i++) {
        String spanId = string(reader, 128),
            id = string(reader, 128),
            recall = string(reader, 8192);
        int dimensions = bounded(reader.readInt(), 2, 3072);
        if (dimensions != request.claim().target().dimensions()) {
          throw invalid();
        }
        var vector = new ArrayList<Double>(dimensions);
        for (int j = 0; j < dimensions; j++) {
          vector.add((double) reader.readFloat());
        }
        entries.add(new SoundReceipt.Entry(spanId, id, recall, vector, string(reader, 64)));
      }
      var verified = new VerifiedRevision(string(reader, 64), string(reader, 64), reader.readInt());
      if (reader.read() != -1) {
        throw invalid();
      }
      var receipt = new SoundReceipt(entries, verified);
      verify(request.claim(), receipt);
      return receipt;
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  static void verify(SoundBuildClaim claim, SoundReceipt receipt) {
    if (receipt == null || receipt.entries().size() != claim.spans().size()) {
      throw invalid();
    }
    var digests = new TreeMap<String, String>();
    for (int i = 0; i < claim.spans().size(); i++) {
      var span = claim.spans().get(i);
      var saved = receipt.entries().get(i);
      String id = RetrievalProjection.physicalSegmentId(claim.generationId(), span.id());
      var entry =
          new RetrievalProjection.Entry(
              id,
              claim.actor().workspaceId(),
              claim.original().documentId(),
              claim.generationId(),
              span.waveform().pcmSha256(),
              saved.vector());
      String digest = RetrievalProjection.entryDigest(entry);
      if (!span.id().equals(saved.spanId())
          || !id.equals(saved.physicalSegmentId())
          || saved.vector().size() != claim.target().dimensions()
          || !digest.equals(saved.entrySha256())) {
        throw invalid();
      }
      digests.put(id, digest);
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.actor().workspaceId(),
            claim.original().documentId(),
            claim.generationId(),
            digests);
    if (!receipt.verified().projectionIdentity().equals(claim.target().projectionIdentity())
        || !manifest.sha256().equals(receipt.verified().manifestSha256())
        || receipt.verified().segmentCount() != claim.spans().size()) {
      throw invalid();
    }
  }

  private static void target(DataOutputStream writer, IndexTarget target) throws IOException {
    string(writer, target.embeddingIdentity());
    string(writer, target.projectionIdentity());
    string(writer, target.modelRevision());
    writer.writeInt(target.dimensions());
  }

  private static IndexTarget target(DataInputStream reader) throws IOException {
    return new IndexTarget(
        string(reader, 128), string(reader, 128), string(reader, 160), reader.readInt());
  }

  private static void header(DataInputStream reader) throws IOException {
    if (reader.readInt() != MAGIC || reader.readInt() != VERSION) {
      throw invalid();
    }
  }

  private static boolean flag(DataInputStream reader) throws IOException {
    return bounded(reader.readUnsignedByte(), 0, 1) == 1;
  }

  private static int bounded(int value, int minimum, int maximum) {
    if (value < minimum || value > maximum) {
      throw invalid();
    }
    return value;
  }

  private static String string(DataInputStream reader, int maximum) throws IOException {
    int size = bounded(reader.readInt(), 0, maximum);
    byte[] bytes = reader.readNBytes(size);
    if (bytes.length != size) {
      throw invalid();
    }
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static void string(DataOutputStream writer, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 8192) {
      throw invalid();
    }
    writer.writeInt(bytes.length);
    writer.write(bytes);
  }

  static ProcessSoundIndexer.Failure invalid() {
    return new ProcessSoundIndexer.Failure("sound_index_output_invalid");
  }
}
