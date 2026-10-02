package com.evidence.rag.web;

import com.evidence.rag.model.domain.SourceAudio;
import java.util.List;
import org.springframework.http.ResponseEntity;

/** Single byte-range HTTP response for an already-authorized original audio object. */
public final class AudioContentResponse {
  private AudioContentResponse() {}

  public static ResponseEntity<byte[]> create(SourceAudio source, List<String> ranges) {
    return MediaContentResponse.create(source.mimeType(), source.content(), ranges);
  }
}
