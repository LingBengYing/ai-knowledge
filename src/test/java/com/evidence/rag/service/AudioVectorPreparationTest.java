package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.tool.parser.TextParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioVectorPreparationTest {
  @Test
  void optInRetainsEverySamePassWaveformIncludingSilenceAcrossRepeatedAttachments() {
    var fixture = new AudioVectorQueryFixture();
    var service = fixture.preparation(true);
    var attachment = fixture.attachment();
    var query =
        service.prepare(
            AudioVectorQueryFixture.QUESTION, List.of(attachment, attachment), () -> true);
    assertEquals(2, fixture.decodes);
    assertEquals(6, fixture.asr.size());
    assertEquals(6, query.queryAudio().size());
    assertEquals(
        List.of(0L, 16000L, 32000L, 0L, 16000L, 32000L),
        query.queryAudio().stream().map(AudioWaveform::startSample).toList());
    for (int index = 0; index < fixture.asr.size(); index++) {
      assertArrayEquals(fixture.asr.get(index), query.queryAudio().get(index).wav());
    }
    assertEquals(2, query.attachments().size());
    assertTrue(query.retrievalText().contains(AudioVectorQueryFixture.FACT));
    var old = new AudioVectorQueryFixture();
    var legacy =
        old.preparation(false)
            .prepare(AudioVectorQueryFixture.QUESTION, List.of(attachment, attachment), () -> true);
    assertTrue(legacy.queryAudio().isEmpty());
    assertEquals(legacy.attachments(), query.attachments());
    assertEquals(legacy.retrievalText(), query.retrievalText());
    assertNotEquals(legacy.preparationRevision(), query.preparationRevision());
    assertNotEquals(legacy.manifestSha256(), query.manifestSha256());
  }

  @Test
  void retentionDoesNotRelaxCompleteTranscriptLimitOrCompileAQuestionWithoutAttachments() {
    var fixture = new AudioVectorQueryFixture();
    var service = fixture.preparation(true);
    var plain = service.prepare(AudioVectorQueryFixture.QUESTION, List.of(), () -> true);
    assertTrue(plain.queryAudio().isEmpty());
    assertEquals(0, fixture.decodes);
    fixture.longText = true;
    assertEquals(
        "query_text_limit",
        assertThrows(
                TextParser.Failure.class,
                () ->
                    service.prepare(
                        AudioVectorQueryFixture.QUESTION,
                        List.of(fixture.attachment()),
                        () -> true))
            .code());
    assertEquals(1, fixture.decodes);
    assertEquals(
        3,
        fixture.asr.size(),
        "Complete same-pass ASR remains bounded before publication of a query");
  }
}
