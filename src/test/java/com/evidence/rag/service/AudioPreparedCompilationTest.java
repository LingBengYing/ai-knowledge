package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.model.domain.AudioPreparedCompilation;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryAttachmentManifest;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioPreparedCompilationTest {
  @Test
  void retainedWaveformsUseTheSameDecodeAndEveryAsrSliceIncludingSilenceAndOddMillisecondTail() {
    byte[] source = {1, 3, 5};
    byte[] pcm = new byte[64_002];
    Arrays.fill(pcm, (byte) 4);
    pcm[64_000] = 19;
    var received = new ArrayList<byte[]>();
    int[] decodes = {0};
    var decoder = decoder(pcm, decodes);
    var models = models(received);
    var compiler = new AudioCompilationService(decoder, models, 1, Duration.ofSeconds(10));
    var prepared = compiler.compileWithWaveforms("query.wav", "audio/wav", source, () -> true);
    assertEquals(1, decodes[0]);
    assertEquals(3, received.size());
    assertEquals(
        List.of(0L, 16_000L, 32_000L),
        prepared.waveforms().stream().map(AudioWaveform::startSample).toList());
    assertEquals(
        List.of(16_000L, 32_000L, 32_001L),
        prepared.waveforms().stream().map(AudioWaveform::endSample).toList());
    assertEquals("", prepared.compilation().spans().get(1).text());
    assertEquals(2001, prepared.compilation().durationMs());
    for (int i = 0; i < received.size(); i++) {
      assertArrayEquals(received.get(i), prepared.waveforms().get(i).wav());
      assertEquals(ModelValues.sha256(source), prepared.waveforms().get(i).sourceSha256());
    }
    assertEquals(compiler.revision(), prepared.compilation().compilerRevision());
    assertThrows(
        RuntimeException.class,
        () ->
            new AudioPreparedCompilation(
                prepared.compilation(), prepared.waveforms().subList(0, 2)));
    assertThrows(
        RuntimeException.class,
        () ->
            new AudioPreparedCompilation(
                prepared.compilation(),
                List.of(prepared.waveforms().getFirst(), prepared.waveforms().getLast())));
    assertTrue(prepared.toString().contains("redacted"));
  }

  @Test
  void legacyCompilationRemainsIdenticalAndWaveformManifestBindsActualSamplesNotTranscript() {
    byte[] source = {1, 3, 5};
    byte[] pcm = new byte[64_002];
    Arrays.fill(pcm, (byte) 3);
    var compiler =
        new AudioCompilationService(
            decoder(pcm, new int[1]), models(new ArrayList<>()), 1, Duration.ofSeconds(10));
    var prepared = compiler.compileWithWaveforms("query.wav", "audio/wav", source, () -> true);
    var legacyCompiler =
        new AudioCompilationService(
            decoder(pcm, new int[1]), models(new ArrayList<>()), 1, Duration.ofSeconds(10));
    assertEquals(
        prepared.compilation(),
        legacyCompiler.compile("query.wav", "audio/wav", source, () -> true));
    var attachments =
        List.of(
            new QueryAttachmentManifest(
                0,
                ModelValues.sha256(source),
                QueryAttachment.Kind.AUDIO,
                compiler.revision(),
                ModelValues.sha256(new byte[] {8}),
                4,
                0,
                List.of(),
                false));
    var legacy = new PreparedQuery("问题？", "问题？\n事实。", List.of(), attachments, "preparation-v1");
    assertEquals(
        legacy.manifestSha256(),
        new PreparedQuery("问题？", "问题？\n事实。", List.of(), attachments, "preparation-v1", List.of())
            .manifestSha256());
    var retained =
        new PreparedQuery(
            "问题？", "问题？\n事实。", List.of(), attachments, "preparation-v1", prepared.waveforms());
    assertNotEquals(legacy.manifestSha256(), retained.manifestSha256());
    var changed = new ArrayList<>(prepared.waveforms());
    var tail = changed.getLast();
    changed.set(
        changed.size() - 1,
        new AudioWaveform(
            tail.sourceSha256(),
            tail.decoderRevision(),
            tail.startSample(),
            tail.endSample(),
            new byte[] {9, 2}));
    assertNotEquals(
        retained.manifestSha256(),
        new PreparedQuery("问题？", "问题？\n事实。", List.of(), attachments, "preparation-v1", changed)
            .manifestSha256());
    assertThrows(
        RuntimeException.class,
        () ->
            new PreparedQuery(
                "问题？", "问题？\n事实。", List.of(), attachments, "preparation-v1", List.of(tail)));
    assertTrue(legacy.queryAudio().isEmpty());
  }

  private static AudioDecoder decoder(byte[] pcm, int[] calls) {
    return new AudioDecoder() {
      public String revision() {
        return "decoder-v1";
      }

      public DecodedAudio decode(String filename, String mediaType, byte[] source) {
        calls[0]++;
        return new DecodedAudio(ModelValues.sha256(source), revision(), pcm);
      }

      public void close() {}
    };
  }

  private static AudioModels models(List<byte[]> received) {
    return new AudioModels() {
      public String revision() {
        return "asr-v1";
      }

      public Transcript transcribe(byte[] wav) {
        received.add(wav.clone());
        return new Transcript(received.size() == 2 ? "" : "事实。");
      }

      public void close() {}
    };
  }
}
