package com.evidence.rag.model.domain;

/**
 * Embedding throughput of one indexing attempt: up to {@code concurrency} embedding requests of the
 * same document in flight, all paced by one token bucket shared by every indexing process of this
 * user and embedding endpoint ({@code requestsPerMinute} requests and an estimated {@code
 * tokensPerMinute} input tokens; 0 disables that limit).
 */
public record EmbeddingThroughput(int concurrency, int requestsPerMinute, int tokensPerMinute) {
  /** One request at a time and no shared limit: the behaviour of an unconfigured processor. */
  public static final EmbeddingThroughput SERIAL = new EmbeddingThroughput(1, 0, 0);

  public EmbeddingThroughput {
    if (concurrency < 1
        || concurrency > 8
        || requestsPerMinute < 0
        || requestsPerMinute > 100000
        || tokensPerMinute < 0
        || tokensPerMinute > 100000000) {
      throw new IllegalArgumentException("Invalid embedding throughput");
    }
  }

  public boolean limited() {
    return requestsPerMinute > 0 || tokensPerMinute > 0;
  }
}
