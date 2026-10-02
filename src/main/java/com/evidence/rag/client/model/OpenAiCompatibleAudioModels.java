package com.evidence.rag.client.model;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Explicit audio transcription Adapter; construction never invokes a provider. */
public final class OpenAiCompatibleAudioModels implements AudioModels {
  private static final int WAV_HEADER_BYTES = 44;
  private static final int MAX_PCM_BYTES = 960_000;
  private static final int MAX_REQUEST_BYTES = 1_048_576;
  private static final String PROTOCOL = "multipart-pcm-wav-16000-mono-s16le-text-only-v1";

  public record Configuration(
      OpenAiCompatibleModels.Endpoint endpoint,
      Duration deadline,
      int maxResponseBytes,
      boolean allowLoopbackHttp) {
    public Configuration {
      if (deadline == null
          || deadline.compareTo(Duration.ofMillis(10)) < 0
          || deadline.compareTo(Duration.ofSeconds(60)) > 0
          || maxResponseBytes < 1024
          || maxResponseBytes > 1_048_576) {
        throw new TextModels.Failure("model_invalid_configuration");
      }
      ModelHttpTransport.validateEndpoint(endpoint, allowLoopbackHttp);
    }

    @Override
    public String toString() {
      return "Configuration[redacted]";
    }
  }

  private final Configuration configuration;
  private final ModelHttpTransport transport;
  private final String revision;

  public OpenAiCompatibleAudioModels(Configuration configuration) {
    if (configuration == null) {
      throw new TextModels.Failure("model_invalid_configuration");
    }
    this.configuration = configuration;
    transport =
        new ModelHttpTransport(
            configuration.deadline(), configuration.maxResponseBytes(), MAX_REQUEST_BYTES);
    revision = revisionOf(configuration.endpoint());
  }

  @Override
  public String revision() {
    return revision;
  }

  @Override
  public Transcript transcribe(byte[] wav) {
    byte[] validated = validateWav(wav);
    // ISO-8859-1 preserves every byte, so this tests the boundary against the entire binary file.
    String binary = new String(validated, StandardCharsets.ISO_8859_1);
    String model = configuration.endpoint().model();
    String boundary;
    do {
      boundary = "audio-" + UUID.randomUUID().toString();
    } while (binary.contains(boundary) || model.contains(boundary));
    var request = new ByteArrayOutputStream(validated.length + 1024);
    request.writeBytes(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                + model
                + "\r\n--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\""
                + "\r\nContent-Type: audio/wav\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    request.writeBytes(validated);
    request.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    var result =
        transport.post(
            configuration.endpoint(),
            "audio/transcriptions",
            request.toByteArray(),
            "multipart/form-data; boundary=" + boundary);
    var text = result.path("text");
    if (!text.isString()) {
      throw new TextModels.Failure("model_invalid_response");
    }
    return new Transcript(text.stringValue());
  }

  @Override
  public void close() {
    transport.close();
  }

  private static byte[] validateWav(byte[] wav) {
    if (wav == null
        || wav.length < WAV_HEADER_BYTES + 2
        || wav.length > WAV_HEADER_BYTES + MAX_PCM_BYTES
        || (wav.length - WAV_HEADER_BYTES) % 2 != 0) {
      throw new TextModels.Failure("model_invalid_input");
    }
    byte[] snapshot = wav.clone();
    var header = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
    if (header.getInt() != 0x46464952
        || header.getInt() != snapshot.length - 8
        || header.getInt() != 0x45564157
        || header.getInt() != 0x20746d66
        || header.getInt() != 16
        || header.getShort() != 1
        || header.getShort() != 1
        || header.getInt() != 16_000
        || header.getInt() != 32_000
        || header.getShort() != 2
        || header.getShort() != 16
        || header.getInt() != 0x61746164
        || header.getInt() != snapshot.length - WAV_HEADER_BYTES) {
      throw new TextModels.Failure("model_invalid_input");
    }
    return snapshot;
  }

  private static String revisionOf(OpenAiCompatibleModels.Endpoint endpoint) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String value : List.of(endpoint.baseUrl().toString(), endpoint.model(), PROTOCOL)) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return "java-audio-models-v1-" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException unavailable) {
      throw new TextModels.Failure("model_invalid_configuration");
    }
  }
}
