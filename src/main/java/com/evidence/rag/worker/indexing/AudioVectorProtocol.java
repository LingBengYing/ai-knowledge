package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.GeminiAudioEmbeddingModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioVectorBuildClaim;
import com.evidence.rag.model.domain.AudioVectorReceipt;
import com.evidence.rag.model.domain.AudioVectorSpan;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
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

/** Independent complete-original-audio PCM protocol; text and image protocols stay unchanged. */
final class AudioVectorProtocol {
  static final int MAGIC = 0x52414741;
  static final int VERSION = 1;
  static final int MAX_REQUEST = 40 * 1024 * 1024;
  static final int MAX_OUTPUT = 8 * 1024 * 1024;

  private AudioVectorProtocol() {}

  record Request(
      GeminiAudioEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout,
      AudioVectorBuildClaim claim,
      IndexWorkerLifetime.Parent parent) {
    @Override
    public String toString() {
      return "AudioVectorRequest[redacted]";
    }
  }

  static Request request(
      GeminiAudioEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout,
      AudioVectorBuildClaim claim) {
    var request =
        new Request(models, projection, timeout, claim, IndexWorkerLifetime.Parent.current());
    validate(request);
    return request;
  }

  static void validateSettings(
      GeminiAudioEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout) {
    if (models == null
        || projection == null
        || timeout == null
        || timeout.compareTo(Duration.ofMillis(10)) < 0
        || timeout.compareTo(Duration.ofMillis(120000)) > 0
        || models.dimensions() != projection.dimension()) {
      throw invalid();
    }
  }

  private static void validate(Request request) {
    validateSettings(request.models(), request.projection(), request.timeout());
    var claim = request.claim();
    if (claim == null || request.parent() == null) {
      throw invalid();
    }
    for (var span : claim.spans()) {
      if (!RetrievalProjection.physicalSegmentId(
              claim.basePublication().projectionGenerationId(), span.audioEvidenceId())
          .equals(span.basePhysicalSegmentId())) {
        throw invalid();
      }
    }
    try (var models = new GeminiAudioEmbeddingModels(request.models())) {
      if (!request.projection().workspaceId().equals(claim.actor().workspaceId())
          || !request.projection().embeddingIdentity().equals(claim.target().embeddingIdentity())
          || !models.revision().equals(claim.target().embeddingIdentity())
          || !models.revision().equals(claim.target().modelRevision())
          || !request.projection().identity().equals(claim.target().projectionIdentity())
          || models.dimensions() != claim.target().dimensions()
          || !models.decoderRevision().equals(claim.decoderRevision())) {
        throw invalid();
      }
    }
  }

  static void writeRequest(OutputStream output, Request request) throws IOException {
    validate(request);
    var bytes = new ByteArrayOutputStream();
    var writer = new DataOutputStream(bytes);
    writer.writeInt(MAGIC);
    writer.writeInt(VERSION);
    writer.writeLong(request.timeout().toNanos());
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
    var base = claim.basePublication();
    for (var value :
        List.of(
            base.documentId(),
            base.publicationId(),
            base.sourceRevisionId(),
            base.projectionGenerationId(),
            base.sourceSha256(),
            base.parserRevision())) {
      string(writer, value);
    }
    target(writer, base.target());
    string(writer, base.manifestSha256());
    writer.writeInt(base.segmentCount());
    target(writer, claim.target());
    string(writer, claim.vectorGenerationId());
    string(writer, claim.decoderRevision());
    writer.writeInt(claim.spans().size());
    for (var span : claim.spans()) {
      string(writer, span.audioEvidenceId());
      string(writer, span.basePhysicalSegmentId());
      writer.writeInt(span.ordinal());
      writer.writeLong(span.startMs());
      writer.writeLong(span.endMs());
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
    var model =
        new GeminiAudioEmbeddingModels.Configuration(
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
    var base =
        new PublicationVersion(
            string(reader, 400),
            string(reader, 512),
            string(reader, 512),
            string(reader, 144),
            string(reader, 64),
            string(reader, 640),
            target(reader),
            string(reader, 64),
            reader.readInt());
    var target = target(reader);
    String generation = string(reader, 36), decoder = string(reader, 800);
    int count = bounded(reader.readInt(), 1, 600);
    var spans = new ArrayList<AudioVectorSpan>(count);
    for (int i = 0; i < count; i++) {
      String evidenceId = string(reader, 512), baseId = string(reader, 128);
      int ordinal = reader.readInt();
      long startMs = reader.readLong(), endMs = reader.readLong();
      String source = string(reader, 64), waveDecoder = string(reader, 800);
      long start = reader.readLong(), end = reader.readLong();
      int size = bounded(reader.readInt(), 2, 960000);
      byte[] pcm = reader.readNBytes(size);
      if (pcm.length != size) {
        throw invalid();
      }
      spans.add(
          new AudioVectorSpan(
              evidenceId,
              baseId,
              ordinal,
              startMs,
              endMs,
              new AudioWaveform(source, waveDecoder, start, end, pcm)));
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
            model,
            projection,
            timeout,
            new AudioVectorBuildClaim(actor, base, target, generation, decoder, spans),
            parent);
    validate(request);
    return request;
  }

  static byte[] encode(AudioVectorReceipt receipt) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var writer = new DataOutputStream(bytes);
    writer.writeInt(MAGIC);
    writer.writeInt(VERSION);
    writer.writeInt(0);
    writer.writeInt(receipt.entries().size());
    for (var entry : receipt.entries()) {
      string(writer, entry.physicalSegmentId());
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

  static AudioVectorReceipt decode(byte[] bytes, Request request) throws IOException {
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
      throw new ProcessAudioVectorIndexer.Failure(
          status == 2 ? "audio_vector_timeout" : "audio_vector_unavailable");
    }
    int count = bounded(reader.readInt(), 1, 600);
    if (count != request.claim().spans().size()) {
      throw invalid();
    }
    var entries = new ArrayList<AudioVectorReceipt.Entry>(count);
    try {
      for (int i = 0; i < count; i++) {
        String id = string(reader, 128);
        int dimensions = bounded(reader.readInt(), 2, 3072);
        if (dimensions != request.claim().target().dimensions()) {
          throw invalid();
        }
        var vector = new ArrayList<Double>(dimensions);
        for (int j = 0; j < dimensions; j++) {
          vector.add((double) reader.readFloat());
        }
        entries.add(new AudioVectorReceipt.Entry(id, vector, string(reader, 64)));
      }
      var verified = new VerifiedRevision(string(reader, 64), string(reader, 64), reader.readInt());
      if (reader.read() != -1) {
        throw invalid();
      }
      var receipt = new AudioVectorReceipt(entries, verified);
      verify(request.claim(), receipt);
      return receipt;
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  static void verify(AudioVectorBuildClaim claim, AudioVectorReceipt receipt) {
    if (receipt == null || receipt.entries().size() != claim.spans().size()) {
      throw invalid();
    }
    var digests = new TreeMap<String, String>();
    for (int i = 0; i < claim.spans().size(); i++) {
      var span = claim.spans().get(i);
      var saved = receipt.entries().get(i);
      String id =
          RetrievalProjection.physicalSegmentId(claim.vectorGenerationId(), span.audioEvidenceId());
      var entry =
          new RetrievalProjection.Entry(
              id,
              claim.actor().workspaceId(),
              claim.basePublication().documentId(),
              claim.vectorGenerationId(),
              span.waveform().pcmSha256(),
              saved.vector());
      String digest = RetrievalProjection.entryDigest(entry);
      if (!id.equals(saved.physicalSegmentId())
          || saved.vector().size() != claim.target().dimensions()
          || !digest.equals(saved.entrySha256())) {
        throw invalid();
      }
      digests.put(id, digest);
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.actor().workspaceId(),
            claim.basePublication().documentId(),
            claim.vectorGenerationId(),
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
    if (bytes.length > 4096) {
      throw invalid();
    }
    writer.writeInt(bytes.length);
    writer.write(bytes);
  }

  static ProcessAudioVectorIndexer.Failure invalid() {
    return new ProcessAudioVectorIndexer.Failure("audio_vector_output_invalid");
  }
}
