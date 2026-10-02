package com.evidence.rag.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.QueryAttachment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryAttachmentCommandTest {
  private static final AnswerCommand ANSWER =
      new AnswerCommand(" 原问题\n", DocumentSelection.selected(List.of()));
  private static final QueryAttachment IMAGE =
      new QueryAttachment("private.png", "image/png", new byte[] {1});

  @Test
  void directCallersMustSupplyExplicitQuestionModeAndBoundedNonNullAttachments() {
    assertThrows(
        ApplicationException.class,
        () -> new QueryAttachmentCommand(null, QueryAnswerMode.TEXT, List.of()));
    assertThrows(
        ApplicationException.class, () -> new QueryAttachmentCommand(ANSWER, null, List.of()));
    assertThrows(
        ApplicationException.class,
        () -> new QueryAttachmentCommand(ANSWER, QueryAnswerMode.TEXT, null));
    assertThrows(
        ApplicationException.class,
        () ->
            new QueryAttachmentCommand(
                ANSWER, QueryAnswerMode.TEXT, Arrays.asList((QueryAttachment) null)));
    assertThrows(
        ApplicationException.class,
        () ->
            new QueryAttachmentCommand(
                ANSWER, QueryAnswerMode.TEXT, List.of(IMAGE, IMAGE, IMAGE, IMAGE)));
  }

  @Test
  void decodedLimitAppliesToTotalOriginalBytesNotPerAttachmentOrBase64Length() {
    var tenMiB = new QueryAttachment("part.wav", "audio/wav", new byte[10 * 1024 * 1024]);
    var command =
        new QueryAttachmentCommand(ANSWER, QueryAnswerMode.AUDIO, List.of(tenMiB, tenMiB));
    assertEquals(2, command.attachments().size());
    assertThrows(
        ApplicationException.class,
        () ->
            new QueryAttachmentCommand(
                ANSWER, QueryAnswerMode.AUDIO, List.of(tenMiB, tenMiB, IMAGE)));
  }

  @Test
  void callerListMutationCannotChangeTheQuestionScopeMediaOrExposeThemInToString() {
    var inputs = new ArrayList<>(List.of(IMAGE));
    var command = new QueryAttachmentCommand(ANSWER, QueryAnswerMode.VIDEO_JOINT, inputs);
    inputs.clear();
    assertEquals(ANSWER, command.answer());
    assertEquals(List.of(IMAGE), command.attachments());
    assertThrows(UnsupportedOperationException.class, () -> command.attachments().clear());
    assertEquals("QueryAttachmentCommand[redacted]", command.toString());
    assertTrue(
        new QueryAttachmentCommand(ANSWER, QueryAnswerMode.TEXT, List.of())
            .attachments()
            .isEmpty());
  }
}
