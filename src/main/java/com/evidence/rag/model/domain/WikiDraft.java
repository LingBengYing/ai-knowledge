package com.evidence.rag.model.domain;

/** Unverified user-authored text; it has no original-source or publication identity. */
public record WikiDraft(
    String id, String title, String body, long version, long createdAt, long updatedAt) {
  public WikiDraft {
    ModelValues.identifier(id, 128);
    title = ModelValues.label(title, 200);
    if (body == null
        || body.codePointCount(0, body.length()) > 100_000
        || body.codePoints()
            .anyMatch(
                c ->
                    (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                        || (c >= 0xD800 && c <= 0xDFFF))
        || version < 1
        || createdAt < 0
        || updatedAt < createdAt) {
      throw ModelValues.invalid();
    }
  }
}
