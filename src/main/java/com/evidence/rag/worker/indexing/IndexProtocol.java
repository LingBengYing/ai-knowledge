package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.IndexingResult;
import com.evidence.rag.model.domain.ProjectionItem;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Internal, versioned, bounded protocol. No authority token, database path, or inherited settings.
 */
final class IndexProtocol {
  static final int MAGIC = 0x52414749;
  static final int VERSION = 3;
  static final int MAX_REQUEST = 8 * 1024 * 1024;
  static final int MAX_OUTPUT = 1024 * 1024;
  static final int MAX_SEGMENTS = 4096;

  private IndexProtocol() {}

  record Request(
      OpenAiCompatibleModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout,
      String workspaceId,
      String documentId,
      String revisionId,
      IndexTarget target,
      List<ProjectionItem> items,
      String projectionGenerationId,
      IndexWorkerLifetime.Parent parent) {
    Request {
      items = List.copyOf(items);
    }

    @Override
    public String toString() {
      return "IndexRequest[redacted]";
    }
  }

  static Request request(
      OpenAiCompatibleModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout,
      IndexClaim claim) {
    if (claim == null) {
      throw invalid();
    }
    var request =
        new Request(
            models,
            projection,
            timeout,
            claim.workspaceId(),
            claim.documentId(),
            claim.revisionId(),
            claim.target(),
            claim.items(),
            claim.projectionGenerationId(),
            IndexWorkerLifetime.Parent.current());
    validate(request);
    return request;
  }

  static void validateSettings(
      OpenAiCompatibleModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout) {
    if (models == null
        || projection == null
        || models.embeddingDimensions() != projection.dimension()
        || timeout == null
        || timeout.compareTo(Duration.ofMillis(10)) < 0
        || timeout.compareTo(Duration.ofSeconds(600)) > 0
        || models.maxResponseBytes() > 4 * 1024 * 1024
        || models.embeddingDimensions() < 2) {
      throw invalid();
    }
  }

  private static void validate(Request request) {
    validateSettings(request.models(), request.projection(), request.timeout());
    requireId(request.workspaceId());
    requireId(request.documentId());
    requireId(request.revisionId());
    requireId(request.projectionGenerationId());
    if (request.parent() == null) {
      throw invalid();
    }
    if (request.target() == null
        || request.items().isEmpty()
        || request.items().size() > MAX_SEGMENTS) {
      throw invalid();
    }
    var configuration = request.models();
    var projection = request.projection();
    try (var models = new OpenAiCompatibleModels(configuration)) {
      if (!projection.workspaceId().equals(request.workspaceId())
          || !projection.embeddingIdentity().equals(request.target().embeddingIdentity())
          || !projection.identity().equals(request.target().projectionIdentity())
          || !models.revision().equals(request.target().modelRevision())
          || configuration.embeddingDimensions() != request.target().dimensions()) {
        throw invalid();
      }
    }
    var seen = new HashSet<String>();
    int total = 0;
    for (int i = 0; i < request.items().size(); i++) {
      var segment = request.items().get(i);
      if (segment == null || segment.ordinal() != i || !seen.add(segment.evidenceId())) {
        throw invalid();
      }
      total += segment.recallText().codePointCount(0, segment.recallText().length());
      if (total > 1_500_000) {
        throw invalid();
      }
    }
  }

  static void writeRequest(OutputStream output, Request request) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var writer = boundedWriter(bytes, MAX_REQUEST);
    writer.writeInt(MAGIC);
    writer.writeInt("standard".equals(request.projection().analyzer()) ? VERSION : 4);
    writer.writeLong(request.timeout().toNanos());
    var models = request.models();
    for (var endpoint : List.of(models.embedding(), models.rerank(), models.generation())) {
      string(writer, endpoint.baseUrl().toString());
      string(writer, endpoint.model());
      string(writer, endpoint.apiKey());
    }
    writer.writeInt(models.embeddingDimensions());
    writer.writeLong(models.deadline().toNanos());
    writer.writeInt(models.maxResponseBytes());
    writer.writeBoolean(models.allowLoopbackHttp());
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
    if (!"standard".equals(projection.analyzer())) {
      string(writer, projection.analyzer());
    }
    for (var value :
        List.of(
            request.workspaceId(),
            request.documentId(),
            request.revisionId(),
            request.target().embeddingIdentity(),
            request.target().projectionIdentity(),
            request.target().modelRevision())) {
      string(writer, value);
    }
    writer.writeInt(request.target().dimensions());
    writer.writeInt(request.items().size());
    for (var segment : request.items()) {
      string(writer, segment.evidenceId());
      writer.writeInt(segment.ordinal());
      string(writer, segment.recallText());
      string(writer, segment.recallTextSha256());
    }
    string(writer, request.projectionGenerationId());
    writer.writeLong(request.parent().pid());
    writer.writeLong(request.parent().started().getEpochSecond());
    writer.writeInt(request.parent().started().getNano());
    bytes.writeTo(output);
    output.flush();
  }

  static Request readRequest(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_REQUEST + 1);
    if (bytes.length > MAX_REQUEST) {
      throw invalid();
    }
    var reader = new DataInputStream(new ByteArrayInputStream(bytes));
    if (reader.readInt() != MAGIC) {
      throw invalid();
    }
    int version = reader.readInt();
    if (version != VERSION && version != 4) {
      throw invalid();
    }
    var timeout = Duration.ofNanos(reader.readLong());
    var endpoints = new ArrayList<OpenAiCompatibleModels.Endpoint>();
    for (int i = 0; i < 3; i++) {
      endpoints.add(
          new OpenAiCompatibleModels.Endpoint(
              URI.create(string(reader, 4096)), string(reader, 256), string(reader, 4096)));
    }
    var models =
        new OpenAiCompatibleModels.Configuration(
            endpoints.get(0),
            endpoints.get(1),
            endpoints.get(2),
            reader.readInt(),
            Duration.ofNanos(reader.readLong()),
            reader.readInt(),
            flag(reader));
    var projection =
        new MilvusRestProjection.Settings(
            URI.create(string(reader, 4096)),
            string(reader, 4096),
            string(reader, 64),
            string(reader, 128),
            string(reader, 128),
            string(reader, 160),
            reader.readInt(),
            Duration.ofNanos(reader.readLong()),
            reader.readInt(),
            flag(reader),
            version == 4 ? string(reader, 16) : "standard");
    String workspace = string(reader, 128),
        document = string(reader, 128),
        revision = string(reader, 128);
    var target =
        new IndexTarget(
            string(reader, 160), string(reader, 160), string(reader, 160), reader.readInt());
    int count = bounded(reader.readInt(), 1, MAX_SEGMENTS);
    var segments = new ArrayList<ProjectionItem>(count);
    for (int i = 0; i < count; i++) {
      segments.add(
          new ProjectionItem(
              string(reader, 128),
              reader.readInt(),
              string(reader, ProjectionItem.MAX_TEXT_BYTES),
              string(reader, 64)));
    }
    String generation = string(reader, 128);
    long parentPid = reader.readLong(), parentSeconds = reader.readLong();
    int parentNanos = bounded(reader.readInt(), 0, 999_999_999);
    var parent =
        new IndexWorkerLifetime.Parent(
            parentPid, java.time.Instant.ofEpochSecond(parentSeconds, parentNanos));
    if (reader.read() != -1) {
      throw invalid();
    }
    var request =
        new Request(
            models,
            projection,
            timeout,
            workspace,
            document,
            revision,
            target,
            segments,
            generation,
            parent);
    validate(request);
    return request;
  }

  static byte[] encode(IndexingResult result) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var writer = boundedWriter(bytes, MAX_OUTPUT);
    writer.writeInt(MAGIC);
    writer.writeInt(VERSION);
    writer.writeInt(0);
    writer.writeInt(result.entryDigests().size());
    for (var entry : new TreeMap<>(result.entryDigests()).entrySet()) {
      string(writer, entry.getKey());
      string(writer, entry.getValue());
    }
    string(writer, result.verified().projectionIdentity());
    string(writer, result.verified().manifestSha256());
    writer.writeInt(result.verified().segmentCount());
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

  static IndexingResult decode(byte[] response, Request request) throws IOException {
    if (response.length > MAX_OUTPUT) {
      throw invalid();
    }
    var reader = new DataInputStream(new ByteArrayInputStream(response));
    header(reader);
    int status = reader.readInt();
    if ((status == 1 || status == 2) && reader.read() == -1) {
      throw new ProcessTextIndexer.Failure(status == 1 ? "indexing_failed" : "indexing_timeout");
    }
    if (status != 0) {
      throw invalid();
    }
    int count = bounded(reader.readInt(), 1, MAX_SEGMENTS);
    var digests = new TreeMap<String, String>();
    for (int i = 0; i < count; i++) {
      String id = string(reader, 128), digest = string(reader, 64);
      requireId(id);
      requireHash(digest);
      if (digests.putIfAbsent(id, digest) != null) {
        throw invalid();
      }
    }
    String projectionIdentity = string(reader, 160), manifestHash = string(reader, 64);
    int segmentCount = reader.readInt();
    if (reader.read() != -1) {
      throw invalid();
    }
    var expectedIds = new HashSet<String>();
    request
        .items()
        .forEach(
            segment ->
                expectedIds.add(
                    RetrievalProjection.physicalSegmentId(
                        request.projectionGenerationId(), segment.evidenceId())));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            request.workspaceId(), request.documentId(), request.projectionGenerationId(), digests);
    if (segmentCount != count
        || !digests.keySet().equals(expectedIds)
        || !projectionIdentity.equals(request.target().projectionIdentity())
        || !manifestHash.equals(manifest.sha256())) {
      throw invalid();
    }
    return new IndexingResult(
        digests, new VerifiedRevision(projectionIdentity, manifestHash, count));
  }

  private static DataOutputStream boundedWriter(ByteArrayOutputStream bytes, int cap) {
    return new DataOutputStream(
        new OutputStream() {
          @Override
          public void write(int value) throws IOException {
            if (bytes.size() >= cap) {
              throw new IOException();
            }
            bytes.write(value);
          }

          @Override
          public void write(byte[] value, int offset, int length) throws IOException {
            if (length > cap - bytes.size()) {
              throw new IOException();
            }
            bytes.write(value, offset, length);
          }
        });
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
    int count = bounded(reader.readInt(), 0, maximum);
    byte[] bytes = reader.readNBytes(count);
    if (bytes.length != count) {
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
    writer.writeInt(bytes.length);
    writer.write(bytes);
  }

  private static void requireId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw invalid();
    }
  }

  private static void requireHash(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw invalid();
    }
  }

  static ProcessTextIndexer.Failure invalid() {
    return new ProcessTextIndexer.Failure("indexing_output_invalid");
  }
}
