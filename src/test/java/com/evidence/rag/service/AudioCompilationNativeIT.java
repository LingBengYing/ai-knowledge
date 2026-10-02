package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleAudioModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.ProcessAudioDecoder;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Native decode and local HTTP contract acceptance, not a real ASR quality evaluation. */
class AudioCompilationNativeIT {
  @Test
  void actualDecodeAndLocalAsrHttpPreserveEverySampleAndCompleteTranscriptIdentity()
      throws Exception {
    if (!"true".equals(System.getenv("RAG_AUDIO_DECODER_IT_ENABLED"))) {
      throw new IllegalArgumentException("Explicit native audio decoder IT opt-in is required");
    }
    Path ffmpeg = configuredPath("RAG_AUDIO_DECODER_IT_FFMPEG");
    Path ffprobe = configuredPath("RAG_AUDIO_DECODER_IT_FFPROBE");
    byte[] pcm = new byte[64_002];
    var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < pcm.length / 2; index++) {
      samples.putShort((short) (index * 31 + 17));
    }
    byte[] source = AudioPcm.wav(pcm, 0, pcm.length);
    List<String> transcripts = List.of("首段合成转录。", "", "末尾样本合成转录。");
    var requests = new LinkedBlockingQueue<Request>();
    var count = new AtomicInteger();
    var json = JsonMapper.builder().build();
    String key = UUID.randomUUID().toString();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.setExecutor(executor);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              requests.add(
                  new Request(
                      exchange.getRequestMethod(),
                      exchange.getRequestURI().toString(),
                      exchange.getRequestHeaders().getFirst("Authorization"),
                      exchange.getRequestHeaders().getFirst("Content-Type"),
                      exchange.getRequestBody().readNBytes(1_048_577)));
              int ordinal = count.getAndIncrement();
              boolean expected = ordinal < transcripts.size();
              byte[] response =
                  json.writeValueAsBytes(
                      Map.of(
                          "text",
                          expected ? transcripts.get(ordinal) : "unexpected request",
                          "start",
                          99999,
                          "end",
                          999999,
                          "segments",
                          List.of(Map.of("text", "not evidence", "start", 88888))));
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(expected ? 200 : 500, response.length);
              exchange.getResponseBody().write(response);
            }
          });
      server.start();
      var endpoint =
          new OpenAiCompatibleModels.Endpoint(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
              "synthetic-native-it-asr",
              key);
      try (var decoder = new ProcessAudioDecoder(ffmpeg, ffprobe, Duration.ofSeconds(10));
          var models =
              new OpenAiCompatibleAudioModels(
                  new OpenAiCompatibleAudioModels.Configuration(
                      endpoint, Duration.ofSeconds(5), 65536, true))) {
        var service = new AudioCompilationService(decoder, models, 1, Duration.ofSeconds(30));
        var result = service.compile("synthetic.wav", "audio/wav", source, () -> true);

        assertEquals(2001, result.durationMs());
        assertEquals(
            List.of(0, 1, 2), result.spans().stream().map(AudioTranscriptSpan::ordinal).toList());
        assertEquals(
            List.of(0L, 1000L, 2000L),
            result.spans().stream().map(AudioTranscriptSpan::startMs).toList());
        assertEquals(
            List.of(1000L, 2000L, 2001L),
            result.spans().stream().map(AudioTranscriptSpan::endMs).toList());
        assertEquals(transcripts, result.spans().stream().map(AudioTranscriptSpan::text).toList());
        assertEquals(ModelValues.sha256(source), result.sourceSha256());
        assertEquals(decoder.revision(), result.decoderRevision());
        assertEquals(models.revision(), result.modelRevision());
        assertEquals(service.revision(), result.compilerRevision());
        assertTrue(result.decoderRevision().matches("java-audio-decoder-v1:[a-f0-9]{64}"));
        assertTrue(result.modelRevision().matches("java-audio-models-v1-[a-f0-9]{64}"));
        assertEquals(3, count.get());
        assertChunkRequest(requests.poll(), key, pcm, 0, 32_000);
        assertChunkRequest(requests.poll(), key, pcm, 32_000, 64_000);
        assertChunkRequest(requests.poll(), key, pcm, 64_000, 64_002);
        assertTrue(requests.isEmpty());
      }
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private static void assertChunkRequest(
      Request request, String key, byte[] pcm, int from, int to) {
    assertNotNull(request);
    assertEquals("POST", request.method());
    assertEquals("/v1/audio/transcriptions", request.path());
    assertEquals("Bearer " + key, request.authorization());
    assertNotNull(request.contentType());
    String prefix = "multipart/form-data; boundary=";
    assertTrue(request.contentType().startsWith(prefix));
    String boundary = request.contentType().substring(prefix.length());
    String[] parts =
        new String(request.body(), StandardCharsets.ISO_8859_1)
            .split(Pattern.quote("--" + boundary), -1);
    assertEquals(4, parts.length);
    assertEquals("", parts[0]);
    assertEquals("--\r\n", parts[3]);
    assertEquals(
        "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nsynthetic-native-it-asr\r\n",
        parts[1]);
    String fileHeader =
        "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\""
            + "\r\nContent-Type: audio/wav\r\n\r\n";
    assertTrue(parts[2].startsWith(fileHeader));
    assertTrue(parts[2].endsWith("\r\n"));
    byte[] wav =
        parts[2]
            .substring(fileHeader.length(), parts[2].length() - 2)
            .getBytes(StandardCharsets.ISO_8859_1);
    assertEquals(44 + to - from, wav.length);
    var header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x46464952, header.getInt());
    assertEquals(wav.length - 8, header.getInt());
    assertEquals(0x45564157, header.getInt());
    assertEquals(0x20746d66, header.getInt());
    assertEquals(16, header.getInt());
    assertEquals(1, header.getShort());
    assertEquals(1, header.getShort());
    assertEquals(16_000, header.getInt());
    assertEquals(32_000, header.getInt());
    assertEquals(2, header.getShort());
    assertEquals(16, header.getShort());
    assertEquals(0x61746164, header.getInt());
    assertEquals(to - from, header.getInt());
    assertArrayEquals(Arrays.copyOfRange(pcm, from, to), Arrays.copyOfRange(wav, 44, wav.length));
  }

  private static Path configuredPath(String variable) {
    String value = System.getenv(variable);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Explicit native audio decoder paths are required");
    }
    return Path.of(value);
  }

  private record Request(
      String method, String path, String authorization, String contentType, byte[] body) {}
}
