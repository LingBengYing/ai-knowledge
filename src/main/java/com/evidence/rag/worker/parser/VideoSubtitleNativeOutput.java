package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.model.domain.VideoSubtitleCue;
import com.evidence.rag.model.domain.VideoSubtitleTrack;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Complete, bounded text subtitle packets from FFprobe; no timing reconstruction or rendering. */
final class VideoSubtitleNativeOutput {
  private static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private VideoSubtitleNativeOutput() {}

  static VideoSubtitleCompilation read(
      byte[] raw, VideoNativeOutput.Streams expected, VideoNativeOutput.Timeline video) {
    try {
      JsonNode root = JSON.readTree(raw);
      if (root == null || !root.isObject()) {
        throw invalid();
      }
      JsonNode streams = root.path("streams");
      JsonNode packets = root.path("packets");
      if (!streams.isArray()
          || streams.size() != expected.subtitles().size()
          || !packets.isArray()
          || packets.size() > 2048) {
        throw invalid();
      }
      var entries = new LinkedHashMap<Integer, VideoNativeOutput.SubtitleStream>();
      var cues = new LinkedHashMap<Integer, List<VideoSubtitleCue>>();
      for (JsonNode stream : streams) {
        var entry = VideoNativeOutput.subtitleStream(stream);
        if (!expected.subtitles().contains(entry) || entries.put(entry.index(), entry) != null) {
          throw invalid();
        }
        cues.put(entry.index(), new ArrayList<>());
      }
      int totalBytes = 0;
      for (JsonNode packet : packets) {
        long streamIndex = integer(packet.path("stream_index"));
        if (streamIndex < 0 || streamIndex > Integer.MAX_VALUE) {
          throw invalid();
        }
        var stream = entries.get((int) streamIndex);
        if (stream == null) {
          throw invalid();
        }
        String sizeText = packet.path("size").stringValue();
        if (sizeText == null || !sizeText.matches("0|[1-9][0-9]{0,6}")) {
          throw invalid();
        }
        int size = Integer.parseInt(sizeText);
        if (size > MAX_PAYLOAD_BYTES - totalBytes) {
          throw invalid();
        }
        totalBytes += size;
        byte[] bytes = hexDump(packet.path("data"), size);
        String text = text(bytes, stream.codec());
        var trackCues = cues.get(stream.index());
        trackCues.add(
            new VideoSubtitleCue(
                trackCues.size(),
                integer(packet.path("pts")),
                integer(packet.path("duration")),
                text,
                ModelValues.sha256(bytes)));
      }
      var tracks = new ArrayList<VideoSubtitleTrack>();
      for (var stream : expected.subtitles()) {
        tracks.add(
            new VideoSubtitleTrack(
                stream.index(),
                stream.codec(),
                stream.timeBase().numerator(),
                stream.timeBase().denominator(),
                stream.language(),
                cues.get(stream.index())));
      }
      return new VideoSubtitleCompilation(
          video.firstPts(), video.timeBase().numerator(), video.timeBase().denominator(), tracks);
    } catch (RuntimeException malformed) {
      // Domain, JSON and byte-protocol failures must not disclose raw packet text or upstream
      // details.
      throw invalid();
    }
  }

  private static byte[] hexDump(JsonNode node, int size) {
    if (!node.isString()) {
      throw invalid();
    }
    String data = node.stringValue();
    if (size == 0 && (data.isEmpty() || data.equals("\n"))) {
      return new byte[0];
    }
    String[] lines = data.split("\n", -1);
    if (lines.length != (size + 15) / 16 + 2
        || !lines[0].isEmpty()
        || !lines[lines.length - 1].isEmpty()) {
      throw invalid();
    }
    var result = new byte[size];
    for (int offset = 0, row = 1; offset < size; offset += 16, row++) {
      String line = lines[row];
      int count = Math.min(16, size - offset);
      if (line.length() != 51 + count
          || !line.substring(0, 8).matches("[a-fA-F0-9]{8}")
          || Long.parseLong(line.substring(0, 8), 16) != offset
          || !line.substring(8, 10).equals(": ")
          || line.charAt(50) != ' ') {
        throw invalid();
      }
      for (int column = 0; column < 16; column++) {
        int position = 10 + column * 2 + column / 2;
        if (column < count) {
          int high = Character.digit(line.charAt(position), 16);
          int low = Character.digit(line.charAt(position + 1), 16);
          if (high < 0
              || low < 0
              || line.charAt(position) > 127
              || line.charAt(position + 1) > 127) {
            throw invalid();
          }
          int value = high * 16 + low;
          if (line.charAt(51 + column) != (value >= 32 && value <= 126 ? (char) value : '.')) {
            throw invalid();
          }
          result[offset + column] = (byte) value;
        } else if (line.charAt(position) != ' ' || line.charAt(position + 1) != ' ') {
          throw invalid();
        }
        if (column % 2 == 1 && line.charAt(position + 2) != ' ') {
          throw invalid();
        }
      }
    }
    return result;
  }

  private static String text(byte[] bytes, String codec) {
    int offset = 0;
    int length = bytes.length;
    if (codec.equals("mov_text")) {
      if (bytes.length < 2) {
        throw invalid();
      }
      offset = 2;
      length = Short.toUnsignedInt(ByteBuffer.wrap(bytes).getShort());
      if (length > bytes.length - offset) {
        throw invalid();
      }
      int cursor = offset + length;
      while (cursor < bytes.length) {
        int remaining = bytes.length - cursor;
        if (remaining < 8) {
          throw invalid();
        }
        long atomSize = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, cursor, 4).getInt());
        if (atomSize < 8 || atomSize > remaining) {
          throw invalid();
        }
        cursor += (int) atomSize;
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes, offset, length))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw invalid();
    }
  }

  private static long integer(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw invalid();
    }
    return node.longValue();
  }

  private static TextParser.Failure invalid() {
    return new TextParser.Failure("parser_invalid_output");
  }
}
