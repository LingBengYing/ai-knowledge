package com.evidence.rag.web;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** A single byte range over original media already authorized and SHA-validated by the Service. */
public final class MediaContentResponse {
  private static final Set<String> INLINE_TYPES =
      Set.of("application/pdf", "text/plain", "text/markdown", "image/png", "image/jpeg");

  private MediaContentResponse() {}

  /** Keep executable originals out of the application origin's rendering context. */
  public static ResponseEntity.BodyBuilder protectDocument(
      ResponseEntity.BodyBuilder response, String mediaType) {
    if (!INLINE_TYPES.contains(mediaType)
        && !mediaType.startsWith("audio/")
        && !mediaType.startsWith("video/")) {
      response
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
          .header("Content-Security-Policy", "sandbox; default-src 'none'");
    }
    return response;
  }

  public static ResponseEntity<byte[]> create(
      String mediaType, byte[] content, List<String> ranges) {
    if (ranges.isEmpty()) {
      return response(200, mediaType).contentLength(content.length).body(content);
    }
    if (ranges.size() != 1 || ranges.getFirst().contains(",")) {
      return unsatisfiable(mediaType, content.length);
    }
    long start;
    long end;
    try {
      var parsed = HttpRange.parseRanges(ranges.getFirst());
      if (parsed.size() != 1) {
        return unsatisfiable(mediaType, content.length);
      }
      start = parsed.getFirst().getRangeStart(content.length);
      end = parsed.getFirst().getRangeEnd(content.length);
    } catch (IllegalArgumentException invalidRange) {
      return unsatisfiable(mediaType, content.length);
    }
    if (start < 0 || start >= content.length || end < start || end >= content.length) {
      return unsatisfiable(mediaType, content.length);
    }
    byte[] selected = Arrays.copyOfRange(content, (int) start, (int) end + 1);
    return response(206, mediaType)
        .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + content.length)
        .contentLength(selected.length)
        .body(selected);
  }

  private static ResponseEntity<byte[]> unsatisfiable(String mediaType, int length) {
    return response(416, mediaType)
        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + length)
        .contentLength(0)
        .build();
  }

  private static ResponseEntity.BodyBuilder response(int status, String mediaType) {
    return protectDocument(ResponseEntity.status(status), mediaType)
        .contentType(MediaType.parseMediaType(mediaType))
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.ACCEPT_RANGES, "bytes");
  }
}
