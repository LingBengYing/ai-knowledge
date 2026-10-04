package com.evidence.rag.model.domain;

/** Persisted hash-only locator, compared with freshly authorized authority on every read. */
public record KnowledgeCitationLocator(
    int citationId, String publicationId, KnowledgeEvidence.Key key, int start, int end,
    String contextSha256, String quoteSha256, Integer page, Long startUs, Long endUs) {
  public static KnowledgeCitationLocator from(KnowledgeReference reference) {
    var source = reference.evidence().source();
    return new KnowledgeCitationLocator(reference.citationId(), source.publication().publicationId(),
        reference.evidence().key(), reference.start(), reference.end(),
        reference.evidence().context().contextSha256(), reference.quoteSha256(),
        source.page(), source.startUs(), source.endUs());
  }
}
