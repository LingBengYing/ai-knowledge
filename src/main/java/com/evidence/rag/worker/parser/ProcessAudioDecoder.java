package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.tool.parser.AudioInput;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Audio-only native protocol over the shared bounded native lifecycle. */
public final class ProcessAudioDecoder implements AudioDecoder {
  private static final int MAX_PROBE_BYTES = 64 * 1024;
  private static final String FORMATS = "wav,mp3,flac,ogg,mov,matroska,webm";
  private static final String PROTOCOL = "java-audio-decoder-v1:16khz-mono-s16le:single-audio";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final NativeMediaSession session;
  private final String decoderRevision;

  public ProcessAudioDecoder(Path ffmpeg, Path ffprobe, Duration deadline) {
    this(ffmpeg, ffprobe, deadline, null, List.of());
  }

  // Trusted test seam: real children still receive fixed native arguments and identical controls.
  ProcessAudioDecoder(
      Path ffmpeg, Path ffprobe, Duration deadline, String fixtureMain, List<String> fixtureArgs) {
    session = new NativeMediaSession(ffmpeg, ffprobe, deadline, "audio", fixtureMain, fixtureArgs);
    decoderRevision =
        "java-audio-decoder-v1:"
            + ModelValues.sha256(
                (PROTOCOL + "\u0000" + session.ffmpegHash() + "\u0000" + session.ffprobeHash())
                    .getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String revision() {
    return decoderRevision;
  }

  @Override
  public DecodedAudio decode(String filename, String mime, byte[] source) {
    return session.execute(
        filename,
        source,
        original -> AudioInput.validateEnvelope(filename, mime, original),
        job -> {
          var probe =
              job.probe(
                  "probe",
                  List.of(
                      "-v",
                      "error",
                      "-protocol_whitelist",
                      "file,pipe",
                      "-format_whitelist",
                      FORMATS,
                      "-show_entries",
                      "stream=index,codec_type:format=format_name",
                      "-of",
                      "json",
                      job.input.toString()),
                  MAX_PROBE_BYTES);
          validateProbe(probe, AudioInput.canonicalMime(filename));
          job.checkCancelled();
          var pcm =
              job.decode(
                      "decode",
                      List.of(
                          "-nostdin",
                          "-v",
                          "error",
                          "-xerror",
                          "-protocol_whitelist",
                          "file,pipe",
                          "-format_whitelist",
                          FORMATS,
                          "-threads",
                          "1",
                          "-i",
                          job.input.toString(),
                          "-map",
                          "0:a:0",
                          "-vn",
                          "-sn",
                          "-dn",
                          "-ac",
                          "1",
                          "-ar",
                          "16000",
                          "-c:a",
                          "pcm_s16le",
                          "-f",
                          "s16le",
                          "pipe:1"),
                      DecodedAudio.MAX_BYTES,
                      0)
                  .stdout();
          if (pcm.length < 2 || pcm.length % 2 != 0) {
            throw failure("parser_invalid_output");
          }
          job.checkCancelled();
          return new DecodedAudio(ModelValues.sha256(job.source), decoderRevision, pcm);
        });
  }

  @Override
  public void close() {
    session.close();
  }

  private static void validateProbe(byte[] response, String mime) {
    try {
      JsonNode root = JSON.readTree(response);
      JsonNode streams = root == null ? null : root.get("streams");
      JsonNode format = root == null ? null : root.get("format");
      if (streams == null
          || !streams.isArray()
          || streams.size() != 1
          || !"audio".equals(streams.get(0).path("codec_type").stringValue())
          || format == null
          || !format.isObject()) {
        throw failure("unsupported_document");
      }
      String name = format.path("format_name").stringValue();
      boolean supported =
          switch (mime) {
            case "audio/wav" -> name.equals("wav");
            case "audio/mpeg" -> name.equals("mp3");
            case "audio/flac" -> name.equals("flac");
            case "audio/ogg" -> name.equals("ogg");
            case "audio/mp4" -> Arrays.asList(name.split(",", -1)).contains("mov");
            case "audio/webm" -> Arrays.asList(name.split(",", -1)).contains("webm");
            default -> false;
          };
      if (!supported) {
        throw failure("unsupported_document");
      }
    } catch (TextParser.Failure safe) {
      throw safe;
    } catch (RuntimeException malformed) {
      throw failure("parser_invalid_output");
    }
  }

  private static TextParser.Failure failure(String code) {
    return new TextParser.Failure(code);
  }
}
