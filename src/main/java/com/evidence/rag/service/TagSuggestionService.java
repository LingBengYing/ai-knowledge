package com.evidence.rag.service;

import static com.evidence.rag.model.domain.ModelValues.invalid;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.dto.DocumentResult;
import com.evidence.rag.model.dto.TagSuggestionApplyCommand;
import com.evidence.rag.model.dto.TagSuggestionResult;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.tool.synopsis.TagSuggestionCompiler;
import java.util.ArrayList;
import java.util.Objects;

/** Current synopsis suggestions and explicit tag merging; neither operation invokes a model. */
public final class TagSuggestionService {
  private final SqliteAuthorityStore store;
  private final SynopsisLibraryService synopsis;
  private final ManagementService management;

  public TagSuggestionService(
      SqliteAuthorityStore store, SynopsisLibraryService synopsis, ManagementService management) {
    this.store = Objects.requireNonNull(store);
    this.synopsis = Objects.requireNonNull(synopsis);
    this.management = Objects.requireNonNull(management);
  }

  public TagSuggestionResult get(Actor actor, String documentId) {
    return store.transaction(
        () -> {
          var document = management.authorizedDocumentInTransaction(actor, documentId, false);
          var current = synopsis.currentSynopsisInTransaction(actor, documentId);
          var suggestions = TagSuggestionCompiler.compile(current.synopsisId(), current.synopsis());
          return new TagSuggestionResult(suggestions, document.tags(), document.canEdit());
        });
  }

  public DocumentResult apply(Actor actor, String documentId, TagSuggestionApplyCommand command) {
    if (command == null) {
      throw invalid();
    }
    return store.transaction(
        () -> {
          management.authorizedDocumentInTransaction(actor, documentId, true);
          var current = synopsis.currentSynopsisInTransaction(actor, documentId);
          var suggestions = TagSuggestionCompiler.compile(current.synopsisId(), current.synopsis());
          if (!suggestions.suggestionFingerprint().equals(command.suggestionFingerprint())) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "tag_suggestions_changed", "标签建议已变化，请刷新后重新选择。");
          }
          var tags = new ArrayList<String>();
          for (int ordinal : command.ordinals()) {
            if (ordinal < 1 || ordinal > suggestions.candidates().size()) {
              throw invalid();
            }
            tags.add(suggestions.candidates().get(ordinal - 1).tag());
          }
          return management.appendTagsInTransaction(actor, documentId, tags);
        });
  }
}
