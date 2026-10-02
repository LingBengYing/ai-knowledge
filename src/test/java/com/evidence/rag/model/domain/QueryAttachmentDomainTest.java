package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.dto.AnswerCommand;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryAttachmentDomainTest {
  private static final String SHA = "a".repeat(64);

  @Test
  void originalAttachmentBytesAreImmutableAndItsKindIsTheExplicitCanonicalMediaType() {
    byte[] original = {1, 2, 3};
    var attachment = new QueryAttachment("private.wav", "audio/wav", original);
    original[0] = 9;
    attachment.content()[1] = 9;
    assertArrayEquals(new byte[] {1, 2, 3}, attachment.content());
    assertEquals(QueryAttachment.Kind.AUDIO, attachment.kind());
    assertEquals(ModelValues.sha256(new byte[] {1, 2, 3}), attachment.sha256());
    assertEquals("QueryAttachment[redacted]", attachment.toString());
    assertEquals(
        QueryAttachment.Kind.VIDEO, new QueryAttachment("x.mp4", "video/mp4", original).kind());
    assertEquals(
        QueryAttachment.Kind.IMAGE, new QueryAttachment("x.png", "image/png", original).kind());
    assertThrows(
        ApplicationException.class,
        () -> new QueryAttachment("x.png", "application/octet-stream", original));
    assertThrows(
        ApplicationException.class, () -> new QueryAttachment("../x.png", "image/png", original));
  }

  @Test
  void plainQuestionKeepsItsOriginalBytesAndHasNoInventedAttachmentMaterial() {
    String question = "  原问题😀\n保留尾部条件？\t";
    var query = PreparedQuery.text(question);
    assertArrayEquals(
        question.getBytes(StandardCharsets.UTF_8),
        query.originalQuestion().getBytes(StandardCharsets.UTF_8));
    assertEquals(question, query.retrievalText());
    assertTrue(query.queryImages().isEmpty());
    assertTrue(query.attachments().isEmpty());
    assertEquals(query.manifestSha256(), PreparedQuery.text(question).manifestSha256());
    assertTrue(query.manifestSha256().matches("[0-9a-f]{64}"));
    assertEquals("PreparedQuery[redacted]", query.toString());
    assertThrows(ApplicationException.class, () -> PreparedQuery.text("字".repeat(1366)));
    assertThrows(ApplicationException.class, () -> PreparedQuery.text("\uD800"));
  }

  @Test
  void textOnlyPreparationPreservesC1CharactersAlreadyAcceptedByTheExistingQuestionContract() {
    String question = "原问题\u0085保留原字节";
    var accepted =
        assertDoesNotThrow(() -> new AnswerCommand(question, DocumentSelection.allDocuments()));
    var prepared = assertDoesNotThrow(() -> PreparedQuery.text(accepted.question()));
    assertEquals(question, prepared.originalQuestion());
    assertEquals(question, prepared.retrievalText());
  }

  @Test
  void hashOnlyManifestCopiesItsSelectionsAndPreparedQueryCopiesBothLists() {
    var image = new VisualImage("image/png", new byte[] {1});
    var selected = new ArrayList<>(List.of(image.sha256()));
    var manifest = manifest(selected, false);
    selected.clear();
    assertEquals(List.of(image.sha256()), manifest.selectedImageSha256());
    assertEquals("QueryAttachmentManifest[redacted]", manifest.toString());
    var images = new ArrayList<>(List.of(image));
    var attachments = new ArrayList<>(List.of(manifest));
    var query = new PreparedQuery("问题", "问题\n附件线索", images, attachments, "preparation-v1");
    images.clear();
    attachments.clear();
    assertEquals(1, query.queryImages().size());
    assertEquals(1, query.attachments().size());
    assertThrows(UnsupportedOperationException.class, () -> query.attachments().clear());
    assertThrows(UnsupportedOperationException.class, () -> manifest.selectedImageSha256().clear());
  }

  @Test
  void completePreparationHashChangesForQuestionRetrievalContentAndFrozenProfile() {
    var image = new VisualImage("image/png", new byte[] {1});
    var attachments = List.of(manifest(List.of(image.sha256()), false));
    var base = new PreparedQuery("原问题", "检索正文", List.of(image), attachments, "p-v1");
    assertNotEquals(
        base.manifestSha256(),
        new PreparedQuery("尾部问题", "检索正文", List.of(image), attachments, "p-v1").manifestSha256());
    assertNotEquals(
        base.manifestSha256(),
        new PreparedQuery("原问题", "检索尾部", List.of(image), attachments, "p-v1").manifestSha256());
    assertNotEquals(
        base.manifestSha256(),
        new PreparedQuery("原问题", "检索正文", List.of(image), attachments, "p-v2").manifestSha256());
    assertThrows(
        ApplicationException.class,
        () -> new PreparedQuery("原问题", "x".repeat(8193), List.of(image), attachments, "p-v1"));
    assertThrows(
        ApplicationException.class,
        () -> new PreparedQuery("原问题", "正文", List.of(image, image), attachments, "p-v1"));
  }

  @Test
  void samplingAndOrdinalMetadataCannotDisguiseMissingVisualsOrPlainQuestionChanges() {
    assertThrows(ApplicationException.class, () -> manifest(List.of(), false));
    assertThrows(
        ApplicationException.class,
        () ->
            new QueryAttachmentManifest(
                3, SHA, QueryAttachment.Kind.AUDIO, "audio-v1", SHA, 1, 0, List.of(), false));
    assertThrows(
        ApplicationException.class,
        () -> new PreparedQuery("原问题", "改写问题", List.of(), List.of(), "p-v1"));
    assertThrows(
        ApplicationException.class,
        () ->
            new QueryAttachmentManifest(
                0, SHA, QueryAttachment.Kind.AUDIO, "audio-v1", SHA, 1, 1, List.of(SHA), false));
  }

  private static QueryAttachmentManifest manifest(List<String> selected, boolean sampled) {
    return new QueryAttachmentManifest(
        0, SHA, QueryAttachment.Kind.IMAGE, "image-v1", SHA, 4, 1, selected, sampled);
  }
}
