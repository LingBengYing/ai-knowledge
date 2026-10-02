package com.evidence.rag.client.model;

import java.nio.charset.StandardCharsets;

/** Audio transcription seam; callers own authorization, source identity and sample-based times. */
public interface AudioModels extends AutoCloseable {
  String revision();

  Transcript transcribe(byte[] wav);

  @Override
  void close();

  record Transcript(String text) {
    public Transcript {
      if (text == null
          || text.length() > 8192
          || text.codePointCount(0, text.length()) > 4096
          || text.getBytes(StandardCharsets.UTF_8).length > 16_384) {
        throw new TextModels.Failure("model_invalid_response");
      }
      for (int index = 0; index < text.length(); index++) {
        char value = text.charAt(index);
        if (Character.isHighSurrogate(value)) {
          if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
            throw new TextModels.Failure("model_invalid_response");
          }
        } else if (Character.isLowSurrogate(value)
            || Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t') {
          throw new TextModels.Failure("model_invalid_response");
        }
      }
    }

    @Override
    public String toString() {
      return "Transcript[redacted]";
    }
  }
}
