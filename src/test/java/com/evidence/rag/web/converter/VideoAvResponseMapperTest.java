package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvState;
import com.evidence.rag.model.dto.OriginalUploadResult;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class VideoAvResponseMapperTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void uploadAndMissingIndexExposeOnlyFrozenMetadata() {
    byte[] bytes = new byte[] {1, 2, 3};
    var original =
        new DocumentOriginal(
            "doc",
            "revision",
            "片段.mp4",
            "video",
            "video/mp4",
            ModelValues.sha256(bytes),
            bytes.length,
            bytes);
    var visual = new IndexTarget("embedding-v1", "visual-v1", "embedding-v1", 3);
    var audio = new IndexTarget("embedding-v1", "audio-v1", "embedding-v1", 3);
    var upload = JSON.valueToTree(OriginalUploadResult.from(original));
    assertEquals(
        Set.of("document_id", "source_revision_id", "source_sha256", "size_bytes"),
        new HashSet<>(upload.propertyNames()));
    var result =
        VideoAvResponseMapper.index(
            new VideoAvState(original, visual, audio, null), "profile-v1", "analysis-v1");
    assertEquals("missing", result.status());
    assertNull(result.publicationId());
    assertNull(result.generationId());
    assertNull(result.manifestSha256());
    assertEquals(0, result.windowCount());
    assertEquals(0, result.videoWindowCount());
    assertEquals(0, result.audioWindowCount());
    assertEquals("analysis-v1", result.modelRevision());
    assertEquals("embedding-v1", result.embeddingModelRevision());
    var tree = JSON.valueToTree(result);
    assertEquals(14, tree.size());
    assertFalse(tree.has("filename"));
    assertEquals("VideoAvIndexResult[redacted]", result.toString());
  }
}
