package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ChineseIndexProtocolTest {
  @Test
  void subprocessReceivesTheChineseProfileWhileLegacyRequestBytesKeepVersionThree()
      throws Exception {
    try (var server = new IndexingTestServer()) {
      var old =
          IndexProtocol.request(
              server.settings().models(),
              server.settings().projection(),
              Duration.ofSeconds(10),
              server.claim(1));
      var legacyBytes = new ByteArrayOutputStream();
      IndexProtocol.writeRequest(legacyBytes, old);
      assertEquals(3, ByteBuffer.wrap(legacyBytes.toByteArray()).getInt(4));
      assertEquals(
          "standard",
          IndexProtocol.readRequest(new ByteArrayInputStream(legacyBytes.toByteArray()))
              .projection()
              .analyzer());
      var p = old.projection();
      var projection =
          new MilvusRestProjection.Settings(
              p.endpoint(),
              p.token(),
              p.database(),
              p.collection(),
              p.workspaceId(),
              p.embeddingIdentity(),
              p.dimension(),
              p.timeout(),
              p.maxResponseBytes(),
              p.allowLoopbackHttp(),
              "chinese");
      var target =
          new IndexTarget(
              old.target().embeddingIdentity(),
              projection.identity(),
              old.target().modelRevision(),
              old.target().dimensions());
      var request =
          new IndexProtocol.Request(
              old.models(),
              projection,
              old.timeout(),
              old.workspaceId(),
              old.documentId(),
              old.revisionId(),
              target,
              old.items(),
              old.projectionGenerationId(),
              old.parent());
      var bytes = new ByteArrayOutputStream();
      IndexProtocol.writeRequest(bytes, request);
      assertEquals(4, ByteBuffer.wrap(bytes.toByteArray()).getInt(4));
      var actual = IndexProtocol.readRequest(new ByteArrayInputStream(bytes.toByteArray()));
      assertEquals("chinese", actual.projection().analyzer());
      assertEquals(request, actual);
    }
  }
}
