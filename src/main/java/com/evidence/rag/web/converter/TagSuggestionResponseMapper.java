package com.evidence.rag.web.converter;

import com.evidence.rag.model.dto.TagSuggestionResult;
import com.evidence.rag.model.vo.TagSuggestionResponse;

/** Explicit tag-suggestion HTTP whitelist, separate from persistence and source evidence. */
public final class TagSuggestionResponseMapper {
  private TagSuggestionResponseMapper() {}

  public static TagSuggestionResponse response(TagSuggestionResult result) {
    var suggestions = result.suggestions();
    var publication = suggestions.publication();
    return new TagSuggestionResponse(
        publication.documentId(),
        publication.publicationId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        suggestions.synopsisId(),
        suggestions.inputFingerprint(),
        suggestions.modelRevision(),
        suggestions.synopsisPolicyRevision(),
        suggestions.policyRevision(),
        suggestions.suggestionFingerprint(),
        result.existingTags(),
        result.canApply(),
        suggestions.candidates().stream()
            .map(item -> new TagSuggestionResponse.Candidate(item.ordinal(), item.tag()))
            .toList());
  }
}
