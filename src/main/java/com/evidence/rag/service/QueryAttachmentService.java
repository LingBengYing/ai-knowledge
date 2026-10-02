package com.evidence.rag.service;

import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.tool.parser.TextParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/** Bounded attachment preparation, complete query-part retrieval and actual-image ranking. */
public final class QueryAttachmentService {
  private static final int MAX_CANDIDATES = 64;
  private final QueryPreparationService preparation;
  private final QueryRankingModels ranking;
  private final TextModels text;
  private final RetrievalProjection projection;
  private final IndexTarget target;
  private final String preparationRevision;
  private final String rankingRevision;

  public QueryAttachmentService(
      QueryPreparationService preparation,
      QueryRankingModels ranking,
      TextModels text,
      RetrievalProjection projection,
      IndexTarget target) {
    if (preparation == null
        || ranking == null
        || text == null
        || projection == null
        || target == null
        || !target.modelRevision().equals(text.revision())
        || !target.projectionIdentity().equals(projection.identity())) {
      throw ModelValues.invalid();
    }
    this.preparation = preparation;
    this.ranking = ranking;
    this.text = text;
    this.projection = projection;
    this.target = target;
    preparationRevision = ModelValues.identifier(preparation.revision(), 200);
    rankingRevision = ModelValues.identifier(ranking.revision(), 200);
  }

  public PreparedQuery prepare(
      String question, List<QueryAttachment> attachments, Runnable current) {
    if (current == null || attachments == null) {
      throw ModelValues.invalid();
    }
    current.run();
    if (attachments.isEmpty()) {
      return PreparedQuery.text(question);
    }
    check(current);
    try {
      var result =
          preparation.prepare(
              question,
              attachments,
              () -> {
                check(current);
                return true;
              });
      check(current);
      return result;
    } catch (TextParser.Failure failure) {
      // Native compilers deliberately sanitize callbacks; restore the enclosing scope/budget
      // reason.
      check(current);
      throw failure;
    }
  }

  public List<RetrievalProjection.Candidate> search(
      PreparedQuery query,
      RetrievalProjection.AuthorizedScope scope,
      Runnable current,
      Consumer<List<String>> validateCandidates) {
    if (query == null || scope == null || current == null || validateCandidates == null) {
      throw ModelValues.invalid();
    }
    check(current);
    var parts = retrievalParts(query.retrievalText());
    projection.prepareSearch();
    check(current);
    var vectors = text.embed(parts);
    check(current);
    if (vectors == null || vectors.size() != parts.size()) {
      throw invalid();
    }
    var found = new LinkedHashMap<String, RetrievalProjection.Candidate>();
    for (int index = 0; index < parts.size(); index++) {
      if (vectors.get(index) == null || vectors.get(index).size() != target.dimensions()) {
        throw invalid();
      }
      check(current);
      var candidates =
          projection.search(
              new RetrievalProjection.Query(
                  parts.get(index), vectors.get(index), scope, MAX_CANDIDATES));
      check(current);
      if (candidates == null || candidates.size() > MAX_CANDIDATES) {
        throw invalid();
      }
      var ids = new ArrayList<String>();
      var unique = new HashSet<String>();
      for (var candidate : candidates) {
        if (candidate == null
            || !Double.isFinite(candidate.score())
            || !unique.add(candidate.segmentId())) {
          throw invalid();
        }
        ids.add(candidate.segmentId());
      }
      // Validate every returned candidate, including those later removed by top-K fusion.
      validateCandidates.accept(List.copyOf(ids));
      check(current);
      candidates.forEach(
          candidate ->
              found.merge(
                  candidate.segmentId(),
                  candidate,
                  (old, added) -> added.score() > old.score() ? added : old));
    }
    return found.values().stream()
        .sorted(Comparator.comparingDouble(RetrievalProjection.Candidate::score).reversed())
        .limit(MAX_CANDIDATES)
        .toList();
  }

  public List<TextModels.Ranked> rank(
      PreparedQuery query, List<QueryRankCandidate> candidates, Runnable current) {
    if (query == null
        || candidates == null
        || candidates.isEmpty()
        || candidates.size() > MAX_CANDIDATES
        || candidates.stream().anyMatch(value -> value == null)
        || current == null) {
      throw ModelValues.invalid();
    }
    check(current);
    boolean images = !query.queryImages().isEmpty();
    int batchSize = images ? 20 : MAX_CANDIDATES;
    var complete = new ArrayList<TextModels.Ranked>();
    for (int offset = 0; offset < candidates.size(); offset += batchSize) {
      check(current);
      var batch = candidates.subList(offset, Math.min(offset + batchSize, candidates.size()));
      var ranked =
          images
              ? ranking.rank(query, List.copyOf(batch))
              : text.rerank(
                  query.retrievalText(), batch.stream().map(QueryRankCandidate::text).toList());
      check(current);
      if (ranked == null || ranked.size() != batch.size()) {
        throw invalid();
      }
      var seen = new HashSet<Integer>();
      for (var rank : ranked) {
        if (rank == null
            || rank.index() < 0
            || rank.index() >= batch.size()
            || !seen.add(rank.index())
            || !Double.isFinite(rank.score())
            || (images && (rank.score() < 0 || rank.score() > 1))) {
          throw invalid();
        }
        complete.add(new TextModels.Ranked(offset + rank.index(), rank.score()));
      }
    }
    return List.copyOf(complete);
  }

  public boolean configurationCurrent() {
    try {
      return preparation.configurationCurrent()
          && preparationRevision.equals(preparation.revision())
          && rankingRevision.equals(ranking.revision())
          && target.modelRevision().equals(text.revision())
          && target.projectionIdentity().equals(projection.identity());
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  public String preparationRevision() {
    return preparationRevision;
  }

  public String rankingRevision() {
    return rankingRevision;
  }

  private void check(Runnable current) {
    current.run();
    if (!configurationCurrent()) {
      throw new ApplicationException(FailureKind.CONFLICT, "configuration_changed", "模型配置已变化。");
    }
  }

  /** All nonblank material is retained, with no split inside a Unicode scalar. */
  private static List<String> retrievalParts(String value) {
    var parts = new ArrayList<String>();
    int start = 0;
    int bytes = 0;
    for (int index = 0; index < value.length(); ) {
      int codePoint = value.codePointAt(index);
      int width = codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
      if (bytes + width > RetrievalProjection.MAX_QUERY_BYTES) {
        String part = value.substring(start, index);
        if (!part.isBlank()) {
          parts.add(part);
        }
        start = index;
        bytes = 0;
      }
      bytes += width;
      index += Character.charCount(codePoint);
    }
    String last = value.substring(start);
    if (!last.isBlank()) {
      parts.add(last);
    }
    return List.copyOf(parts);
  }

  private static TextModels.Failure invalid() {
    return new TextModels.Failure("query_matching_invalid");
  }
}
