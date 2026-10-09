package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.dto.WikiCatalogResult;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.WikiCatalogRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Complete organization file discovery; filter first, count and paginate afterwards. */
public final class WikiCatalogService {
  private final SqliteAuthorityStore store;
  private final WikiCatalogRepository repository;
  private final EvidenceRepository evidence;
  private final String workspaceId;

  public WikiCatalogService(
      SqliteAuthorityStore store,
      WikiCatalogRepository repository,
      EvidenceRepository evidence,
      String workspaceId) {
    this.store = Objects.requireNonNull(store);
    this.repository = Objects.requireNonNull(repository);
    this.evidence = Objects.requireNonNull(evidence);
    this.workspaceId = ModelValues.identifier(workspaceId, 200);
  }

  public WikiCatalogResult list(Actor actor, int offset, int limit, String query, String kind) {
    if (actor == null || !workspaceId.equals(actor.workspaceId())) {
      throw new ApplicationException(
          FailureKind.FORBIDDEN, "wiki_workspace_forbidden", "不能访问其他组织的资料。");
    }
    if (offset < 0 || limit < 1 || limit > 100) {
      throw ModelValues.invalid();
    }
    String q = query == null ? "" : ModelValues.bounded(query.strip(), 200);
    String type = kind == null ? "" : kind;
    if (!Set.of("", "document", "image", "audio", "video").contains(type)) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          var publications = new HashMap<String, PublicationVersion>();
          evidence
              .findActivePublications(actor, DocumentSelection.allDocuments())
              .forEach(publication -> publications.put(publication.documentId(), publication));
          var matches = new ArrayList<WikiCatalogResult.Item>();
          for (var document : repository.documents(actor, type)) {
            var publication = publications.get(document.documentId());
            List<String> texts =
                publication == null ? List.of() : repository.publishedText(actor, publication);
            long count = 0;
            String excerpt = "";
            for (String text : texts) {
              int first = indexOf(text, q, 0);
              if (q.isEmpty()) {
                if (excerpt.isEmpty()) {
                  excerpt = excerpt(text, 0);
                }
              } else if (first >= 0) {
                if (excerpt.isEmpty()) {
                  excerpt = excerpt(text, first);
                }
                for (int at = first; at >= 0; at = indexOf(text, q, at + q.length())) {
                  count++;
                }
              }
            }
            boolean named =
                !q.isEmpty()
                    && (indexOf(document.filename(), q, 0) >= 0
                        || indexOf(document.displayName(), q, 0) >= 0);
            if (!q.isEmpty() && count == 0 && !named) {
              continue;
            }
            matches.add(
                new WikiCatalogResult.Item(
                    document.documentId(),
                    document.filename(),
                    document.displayName(),
                    document.mediaType(),
                    document.kind(),
                    publication == null ? document.state() : "indexed",
                    publication != null && !texts.isEmpty(),
                    document.sourceRevisionId(),
                    document.sourceSha256(),
                    excerpt,
                    count == 0 && named ? 1 : count));
          }
          int start = Math.min(offset, matches.size());
          int end = (int) Math.min((long) start + limit, matches.size());
          return new WikiCatalogResult(matches.subList(start, end), matches.size(), offset, limit);
        });
  }

  private static int indexOf(String text, String query, int from) {
    if (query.isEmpty()) {
      return -1;
    }
    for (int at = from; at <= text.length() - query.length(); at++) {
      if (text.regionMatches(true, at, query, 0, query.length())) {
        return at;
      }
    }
    return -1;
  }

  private static String excerpt(String text, int match) {
    int before = text.codePointCount(0, match);
    int start = text.offsetByCodePoints(0, Math.max(0, before - 40));
    int end =
        text.offsetByCodePoints(start, Math.min(240, text.codePointCount(start, text.length())));
    return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
  }
}
