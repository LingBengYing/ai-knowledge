package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a revision's segments into embedding requests. The parent reserves exactly as many
 * requests as this plan has and the child sends exactly these, so both sides call the same code.
 *
 * <p>One request holds at most {@link #PROVIDER_MAX_INPUTS} inputs (SiliconFlow /v1/embeddings
 * accepts an input array of at most 32 strings), never more than the response-size bound allows
 * (each returned coordinate is budgeted at 128 JSON bytes against the model's max response bytes,
 * and each row against the projection's upsert/readback bound), and never more input JSON than
 * {@link #MAX_INPUT_JSON_BYTES}, which keeps the whole request under the model transport's 1 MiB
 * request bound. With the default 4 MiB response bound and 1024 dimensions that is 31 segments.
 */
final class EmbeddingBatches {
  static final int PROVIDER_MAX_INPUTS = 32;
  static final int MAX_INPUT_JSON_BYTES = OpenAiCompatibleModels.MAX_REQUEST_BYTES * 3 / 4;

  private EmbeddingBatches() {}

  /** The most segments one request may hold, or less than 1 when not even one fits. */
  static int countLimit(int dimension, int modelMaxResponseBytes, int projectionMaxResponseBytes) {
    int modelBatch = (modelMaxResponseBytes - 1024) / (dimension * 128 + 1024);
    int projectionBatch =
        (projectionMaxResponseBytes - 1024)
            / (RetrievalProjection.MAX_TEXT_BYTES * 6 + dimension * 32 + 2048);
    return Math.min(PROVIDER_MAX_INPUTS, Math.min(modelBatch, projectionBatch));
  }

  /** Consecutive [from, to) ranges in segment order. */
  static List<int[]> plan(List<String> texts, int countLimit) {
    if (countLimit < 1) {
      throw new ProcessTextIndexer.Failure("indexing_failed");
    }
    var ranges = new ArrayList<int[]>();
    int start = 0;
    long bytes = 0;
    for (int i = 0; i < texts.size(); i++) {
      long size = jsonBytes(texts.get(i));
      if (i > start && (i - start >= countLimit || bytes + size > MAX_INPUT_JSON_BYTES)) {
        ranges.add(new int[] {start, i});
        start = i;
        bytes = 0;
      }
      bytes += size;
    }
    if (start < texts.size()) {
      ranges.add(new int[] {start, texts.size()});
    }
    return List.copyOf(ranges);
  }

  /** An upper bound of the serialized JSON string, quotes and separator included. */
  static long jsonBytes(String text) {
    long bytes = 3;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' || c == '\\') {
        bytes += 2;
      } else if (c < 0x20) {
        bytes += 6;
      } else if (c < 0x80) {
        bytes += 1;
      } else if (c < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(c)) {
        bytes += 4;
        i++;
      } else {
        bytes += 3;
      }
    }
    return bytes;
  }

  /**
   * A conservative token estimate for rate limiting: one token per three UTF-8 bytes (about one per
   * CJK character, fewer than real tokens for Latin text).
   */
  static long estimatedTokens(List<String> texts) {
    long tokens = 0;
    for (var text : texts) {
      tokens += (text.getBytes(StandardCharsets.UTF_8).length + 2) / 3;
    }
    return Math.max(1, tokens);
  }
}
