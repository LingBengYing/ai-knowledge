package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioPreparedCompilation;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryAttachmentManifest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AudioVectorPreparationBoundaryTest {
  private static final String FIRST = "a".repeat(64);
  private static final String SECOND = "b".repeat(64);
  private static final String DECODER = AudioVectorQueryFixture.DECODER;

  @ParameterizedTest(name = "same-pass compilation rejects {0}")
  @ValueSource(
      strings = {
        "foreign-source",
        "foreign-decoder",
        "initial-gap",
        "tail-overrun",
        "rounded-interior",
        "missing-tail"
      })
  void samePassCompilationRejectsForeignOrIncompleteWaveforms(String defect) {
    var compilation =
        new AudioCompilation(
            FIRST,
            DECODER,
            "asr-v1",
            "compiler-v1",
            1001,
            List.of(
                new AudioTranscriptSpan(0, 0, 1000, "first"),
                new AudioTranscriptSpan(1, 1000, 1001, "tail")));
    var waveforms =
        new ArrayList<>(
            List.of(wave(FIRST, DECODER, 0, 16000), wave(FIRST, DECODER, 16000, 16001)));
    switch (defect) {
      case "foreign-source" -> waveforms.set(0, wave(SECOND, DECODER, 0, 16000));
      case "foreign-decoder" -> waveforms.set(0, wave(FIRST, "other-decoder", 0, 16000));
      case "initial-gap" -> waveforms.set(0, wave(FIRST, DECODER, 1, 16000));
      case "tail-overrun" -> waveforms.set(1, wave(FIRST, DECODER, 16000, 16017));
      case "rounded-interior" -> waveforms.set(0, wave(FIRST, DECODER, 0, 15999));
      case "missing-tail" -> waveforms.set(1, null);
      default -> throw new AssertionError(defect);
    }
    assertThrows(
        ApplicationException.class, () -> new AudioPreparedCompilation(compilation, waveforms));
    assertEquals(
        16001,
        new AudioPreparedCompilation(
                compilation,
                List.of(wave(FIRST, DECODER, 0, 16000), wave(FIRST, DECODER, 16000, 16001)))
            .waveforms()
            .getLast()
            .endSample());
  }

  @ParameterizedTest(name = "query PCM rejects {0}")
  @ValueSource(
      strings = {
        "swapped-groups",
        "changed-decoder",
        "missing-second-group",
        "unlisted-tail-group",
        "missing-interior"
      })
  void queryManifestRejectsAudioGroupSubstitutionOrIncompleteAttachmentSet(String defect) {
    var manifests = List.of(audio(0, FIRST), audio(1, SECOND));
    var waveforms =
        new ArrayList<>(
            List.of(
                wave(FIRST, DECODER, 0, 16),
                wave(FIRST, DECODER, 16, 32),
                wave(SECOND, DECODER, 0, 16),
                wave(SECOND, DECODER, 16, 32)));
    switch (defect) {
      case "swapped-groups" -> waveforms.set(0, wave(SECOND, DECODER, 0, 16));
      case "changed-decoder" -> waveforms.set(2, wave(SECOND, "changed-decoder", 0, 16));
      case "missing-second-group" -> waveforms.subList(2, 4).clear();
      case "unlisted-tail-group" -> waveforms.add(wave("c".repeat(64), DECODER, 0, 16));
      case "missing-interior" -> waveforms.set(1, null);
      default -> throw new AssertionError(defect);
    }
    assertThrows(ApplicationException.class, () -> prepared(manifests, waveforms));
  }

  @Test
  void completeMixedImageAndAudioPreparationPreservesBothModalityBindings() {
    var image = QueryAttachmentAnswerFixture.image("png");
    var imageManifest =
        new QueryAttachmentManifest(
            0,
            image.sha256(),
            QueryAttachment.Kind.IMAGE,
            "image-compiler-v1",
            FIRST,
            1,
            1,
            List.of(image.sha256()),
            false);
    var waveform = wave(SECOND, DECODER, 0, 16);
    var mixed =
        new PreparedQuery(
            "问题",
            "完整查询提示",
            List.of(image),
            List.of(imageManifest, audio(1, SECOND)),
            "prepared-v2",
            List.of(waveform));
    assertEquals(List.of(image), mixed.queryImages());
    assertEquals(List.of(waveform), mixed.queryAudio());
    assertThrows(
        ApplicationException.class,
        () ->
            new PreparedQuery("问题", "问题", List.of(), List.of(), "prepared-v2", List.of(waveform)));
  }

  @Test
  void waveformCountLimitsRejectOverfullGroupsWithoutTruncation() {
    var oneGroup = IntStream.range(0, 601).mapToObj(i -> wave(FIRST, DECODER, i, i + 1)).toList();
    assertThrows(ApplicationException.class, () -> prepared(List.of(audio(0, FIRST)), oneGroup));
    var overflow = new ArrayList<AudioWaveform>();
    for (int group = 0; group < 3; group++) {
      overflow.addAll(oneGroup);
    }
    assertThrows(
        ApplicationException.class,
        () -> prepared(List.of(audio(0, FIRST), audio(1, FIRST), audio(2, FIRST)), overflow));
    assertTrue(
        new PreparedQuery("问题", "问题", List.of(), List.of(), "prepared-v1").queryAudio().isEmpty());
    assertEquals(
        "98d6cc8a093cfd541be36fa637d305640b17ee5b7e9431c9bb8c58493b15cedf",
        PreparedQuery.text("问题").manifestSha256(),
        "Frozen v1 length-prefixed manifest with no waveforms");
  }

  private static PreparedQuery prepared(
      List<QueryAttachmentManifest> manifests, List<AudioWaveform> waves) {
    return new PreparedQuery("问题", "完整查询提示", List.of(), manifests, "prepared-v2", waves);
  }

  private static QueryAttachmentManifest audio(int ordinal, String source) {
    return new QueryAttachmentManifest(
        ordinal,
        source,
        QueryAttachment.Kind.AUDIO,
        "audio-compiler-v1",
        FIRST,
        3,
        0,
        List.of(),
        false);
  }

  private static AudioWaveform wave(String source, String decoder, long start, long end) {
    return new AudioWaveform(source, decoder, start, end, new byte[(int) ((end - start) * 2)]);
  }
}
