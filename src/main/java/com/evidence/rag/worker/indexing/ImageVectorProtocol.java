package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.SiliconFlowImageEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ImageVectorBuildClaim;
import com.evidence.rag.model.domain.ImageVectorReceipt;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualImage;
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
import java.util.Map;

/** Independent single-original-image protocol; the v3 text protocol remains unchanged. */
final class ImageVectorProtocol {
  static final int MAGIC = 0x52414756;
  static final int VERSION = 1;
  static final int MAX_REQUEST = 11 * 1024 * 1024;
  static final int MAX_OUTPUT = 64 * 1024;

  private ImageVectorProtocol() {}

  record Request(
      SiliconFlowImageEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout,
      ImageVectorBuildClaim claim,
      IndexWorkerLifetime.Parent parent) {
    @Override
    public String toString() {
      return "ImageVectorRequest[redacted]";
    }
  }

  static Request request(
      SiliconFlowImageEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout,
      ImageVectorBuildClaim claim) {
    var request =
        new Request(models, projection, timeout, claim, IndexWorkerLifetime.Parent.current());
    validate(request);
    return request;
  }

  static void validateSettings(
      SiliconFlowImageEmbeddingModels.Configuration models,
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
    if (claim == null
        || request.parent() == null
        || !RetrievalProjection.physicalSegmentId(
                claim.basePublication().projectionGenerationId(), claim.imageEvidenceId())
            .equals(claim.basePhysicalSegmentId())) {
      throw invalid();
    }
    try (var models = new SiliconFlowImageEmbeddingModels(request.models())) {
      if (!request.projection().workspaceId().equals(claim.actor().workspaceId())
          || !request.projection().embeddingIdentity().equals(claim.target().embeddingIdentity())
          || !models.revision().equals(claim.target().embeddingIdentity())
          || !models.revision().equals(claim.target().modelRevision())
          || !request.projection().identity().equals(claim.target().projectionIdentity())
          || models.dimensions() != claim.target().dimensions()) {
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
    string(writer, claim.imageEvidenceId());
    string(writer, claim.basePhysicalSegmentId());
    target(writer, claim.target());
    string(writer, claim.vectorGenerationId());
    string(writer, claim.original().mediaType());
    byte[] original = claim.original().content();
    writer.writeInt(original.length);
    writer.write(original);
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
        new SiliconFlowImageEmbeddingModels.Configuration(
            new OpenAiCompatibleModels.Endpoint(
                URI.create(string(reader, 4096)), string(reader, 256), string(reader, 4096)),
            string(reader, 160),
            reader.readInt(),
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
    String imageId = string(reader, 512), baseId = string(reader, 512);
    var target = target(reader);
    String generation = string(reader, 36), mime = string(reader, 32);
    int size = bounded(reader.readInt(), 1, 10 * 1024 * 1024);
    byte[] original = reader.readNBytes(size);
    if (original.length != size) {
      throw invalid();
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
            new ImageVectorBuildClaim(
                actor, base, imageId, baseId, target, generation, new VisualImage(mime, original)),
            parent);
    validate(request);
    return request;
  }

  static byte[] encode(ImageVectorReceipt receipt) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var writer = new DataOutputStream(bytes);
    writer.writeInt(MAGIC);
    writer.writeInt(VERSION);
    writer.writeInt(0);
    string(writer, receipt.physicalSegmentId());
    writer.writeInt(receipt.vector().size());
    for (double value : receipt.vector()) {
      writer.writeFloat((float) value);
    }
    string(writer, receipt.entrySha256());
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

  static ImageVectorReceipt decode(byte[] bytes, Request request) throws IOException {
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
      throw new ProcessImageVectorIndexer.Failure(
          status == 2 ? "image_vector_timeout" : "image_vector_unavailable");
    }
    String id = string(reader, 128);
    int count = bounded(reader.readInt(), 2, 8192);
    var vector = new ArrayList<Double>(count);
    for (int i = 0; i < count; i++) {
      vector.add((double) reader.readFloat());
    }
    String digest = string(reader, 64),
        projection = string(reader, 64),
        manifest = string(reader, 64);
    int entries = reader.readInt();
    if (reader.read() != -1 || count != request.claim().target().dimensions()) {
      throw invalid();
    }
    try {
      var receipt =
          new ImageVectorReceipt(
              id, vector, digest, new VerifiedRevision(projection, manifest, entries));
      verify(request.claim(), receipt);
      return receipt;
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  static void verify(ImageVectorBuildClaim claim, ImageVectorReceipt receipt) {
    var id =
        RetrievalProjection.physicalSegmentId(claim.vectorGenerationId(), claim.imageEvidenceId());
    var entry =
        new RetrievalProjection.Entry(
            id,
            claim.actor().workspaceId(),
            claim.basePublication().documentId(),
            claim.vectorGenerationId(),
            claim.basePublication().sourceSha256(),
            receipt.vector());
    String digest = RetrievalProjection.entryDigest(entry);
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.actor().workspaceId(),
            claim.basePublication().documentId(),
            claim.vectorGenerationId(),
            Map.of(id, digest));
    if (!id.equals(receipt.physicalSegmentId())
        || receipt.vector().size() != claim.target().dimensions()
        || !digest.equals(receipt.entrySha256())
        || !receipt.verified().projectionIdentity().equals(claim.target().projectionIdentity())
        || !manifest.sha256().equals(receipt.verified().manifestSha256())
        || receipt.verified().segmentCount() != 1) {
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

  static ProcessImageVectorIndexer.Failure invalid() {
    return new ProcessImageVectorIndexer.Failure("image_vector_output_invalid");
  }
}
