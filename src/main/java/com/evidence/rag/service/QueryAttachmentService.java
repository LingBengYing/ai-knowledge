package com.evidence.rag.service;

import com.evidence.rag.client.model.AudioEmbeddingModels;
import com.evidence.rag.client.model.ImageEmbeddingModels;
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
import java.util.function.Function;

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
  private final ImageEmbeddingModels imageModels;
  private final RetrievalProjection imageProjection;
  private final IndexTarget imageTarget;
  private final AudioEmbeddingModels audioModels;
  private final RetrievalProjection audioProjection;
  private final IndexTarget audioTarget;
  private final String audioDecoderRevision;

  public QueryAttachmentService(
      QueryPreparationService preparation,
      QueryRankingModels ranking,
      TextModels text,
      RetrievalProjection projection,
      IndexTarget target) {
    this(preparation, ranking, text, projection, target, null, null, null);
  }

  public QueryAttachmentService(
      QueryPreparationService preparation,
      QueryRankingModels ranking,
      TextModels text,
      RetrievalProjection projection,
      IndexTarget target,
      ImageEmbeddingModels imageModels,
      RetrievalProjection imageProjection,
      IndexTarget imageTarget) {
    this(
        preparation,
        ranking,
        text,
        projection,
        target,
        imageModels,
        imageProjection,
        imageTarget,
        null,
        null,
        null);
  }

  public QueryAttachmentService(
      QueryPreparationService preparation,
      QueryRankingModels ranking,
      TextModels text,
      RetrievalProjection projection,
      IndexTarget target,
      ImageEmbeddingModels imageModels,
      RetrievalProjection imageProjection,
      IndexTarget imageTarget,
      AudioEmbeddingModels audioModels,
      RetrievalProjection audioProjection,
      IndexTarget audioTarget) {
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
    if ((imageModels == null || imageProjection == null || imageTarget == null)
        && (imageModels != null || imageProjection != null || imageTarget != null)) {
      throw ModelValues.invalid();
    }
    this.imageModels = imageModels;
    this.imageProjection = imageProjection;
    this.imageTarget = imageTarget;
    if (imageTarget != null && !imageConfigurationCurrent()) {
      throw ModelValues.invalid();
    }
    if ((audioModels == null || audioProjection == null || audioTarget == null)
        && (audioModels != null || audioProjection != null || audioTarget != null)) {
      throw ModelValues.invalid();
    }
    this.audioModels = audioModels;
    this.audioProjection = audioProjection;
    this.audioTarget = audioTarget;
    this.audioDecoderRevision =
        audioModels == null ? null : ModelValues.identifier(audioModels.decoderRevision(), 200);
    if (audioTarget != null && !audioConfigurationCurrent()) {
      throw ModelValues.invalid();
    }
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

  /** Every route is authority-mapped in full before reciprocal-rank fusion. */
  public List<RetrievalProjection.Candidate> searchImages(
      PreparedQuery query,
      RetrievalProjection.AuthorizedScope scope,
      Runnable current,
      Function<List<String>, List<String>> validatedBaseIds) {
    if (query == null
        || query.queryImages().isEmpty()
        || scope == null
        || current == null
        || validatedBaseIds == null
        || imageTarget == null) {
      throw ModelValues.invalid();
    }
    checkImages(current);
    if (scope.documentRevisions().isEmpty()) {
      return List.of();
    }
    imageProjection.prepareSearch();
    checkImages(current);
    var fused = new LinkedHashMap<String, Double>();
    for (var image : query.queryImages()) {
      checkImages(current);
      var vector = imageModels.embed(image);
      checkImages(current);
      if (vector == null || vector.size() != imageTarget.dimensions()) {
        throw invalid();
      }
      var candidates =
          imageProjection.search(
              new RetrievalProjection.Query(
                  image.sha256(),
                  vector,
                  scope,
                  MAX_CANDIDATES,
                  RetrievalProjection.SearchMode.DENSE_ONLY));
      checkImages(current);
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
      var baseIds = validatedBaseIds.apply(List.copyOf(ids));
      checkImages(current);
      if (baseIds == null
          || baseIds.size() != candidates.size()
          || baseIds.stream().anyMatch(value -> value == null || value.isBlank())
          || new HashSet<>(baseIds).size() != baseIds.size()) {
        throw invalid();
      }
      var mapped = new ArrayList<RetrievalProjection.Candidate>();
      for (int index = 0; index < candidates.size(); index++) {
        mapped.add(
            new RetrievalProjection.Candidate(baseIds.get(index), candidates.get(index).score()));
      }
      mapped.sort(
          Comparator.comparingDouble(RetrievalProjection.Candidate::score)
              .reversed()
              .thenComparing(RetrievalProjection.Candidate::segmentId));
      for (int index = 0; index < mapped.size(); index++) {
        fused.merge(mapped.get(index).segmentId(), 1.0 / (61 + index), Double::sum);
      }
    }
    return fused.entrySet().stream()
        .map(entry -> new RetrievalProjection.Candidate(entry.getKey(), entry.getValue()))
        .sorted(
            Comparator.comparingDouble(RetrievalProjection.Candidate::score)
                .reversed()
                .thenComparing(RetrievalProjection.Candidate::segmentId))
        .limit(MAX_CANDIDATES)
        .toList();
  }

  /** Every route is authority-mapped in full before reciprocal-rank fusion. */
  public List<RetrievalProjection.Candidate> searchAudio(
      PreparedQuery query,
      RetrievalProjection.AuthorizedScope scope,
      Runnable current,
      Function<List<String>, List<String>> validatedBaseIds) {
    if (query == null
        || query.queryAudio().isEmpty()
        || scope == null
        || current == null
        || validatedBaseIds == null
        || audioTarget == null) {
      throw ModelValues.invalid();
    }
    checkAudio(current);
    if (scope.documentRevisions().isEmpty()) {
      return List.of();
    }
    audioProjection.prepareSearch();
    checkAudio(current);
    var fused = new LinkedHashMap<String, Double>();
    for (var waveform : query.queryAudio()) {
      checkAudio(current);
      if (!audioDecoderRevision.equals(waveform.decoderRevision())) {
        throw new ApplicationException(FailureKind.CONFLICT, "configuration_changed", "音频解码配置已变化。");
      }
      var vector = audioModels.embed(waveform.wav());
      checkAudio(current);
      if (vector == null || vector.size() != audioTarget.dimensions()) {
        throw invalid();
      }
      var candidates =
          audioProjection.search(
              new RetrievalProjection.Query(
                  waveform.pcmSha256(),
                  vector,
                  scope,
                  MAX_CANDIDATES,
                  RetrievalProjection.SearchMode.DENSE_ONLY));
      checkAudio(current);
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
      var baseIds = validatedBaseIds.apply(List.copyOf(ids));
      checkAudio(current);
      if (baseIds == null
          || baseIds.size() != candidates.size()
          || baseIds.stream().anyMatch(value -> value == null || value.isBlank())
          || new HashSet<>(baseIds).size() != baseIds.size()) {
        throw invalid();
      }
      var mapped = new ArrayList<RetrievalProjection.Candidate>();
      for (int index = 0; index < candidates.size(); index++) {
        mapped.add(
            new RetrievalProjection.Candidate(baseIds.get(index), candidates.get(index).score()));
      }
      mapped.sort(
          Comparator.comparingDouble(RetrievalProjection.Candidate::score)
              .reversed()
              .thenComparing(RetrievalProjection.Candidate::segmentId));
      for (int index = 0; index < mapped.size(); index++) {
        fused.merge(mapped.get(index).segmentId(), 1.0 / (61 + index), Double::sum);
      }
    }
    return fused.entrySet().stream()
        .map(entry -> new RetrievalProjection.Candidate(entry.getKey(), entry.getValue()))
        .sorted(
            Comparator.comparingDouble(RetrievalProjection.Candidate::score)
                .reversed()
                .thenComparing(RetrievalProjection.Candidate::segmentId))
        .limit(MAX_CANDIDATES)
        .toList();
  }

  public boolean usesAudioVectors(PreparedQuery query) {
    return audioTarget != null && query != null && !query.queryAudio().isEmpty();
  }

  public IndexTarget audioTarget() {
    return audioTarget;
  }

  public String audioDecoderRevision() {
    return audioDecoderRevision;
  }

  public boolean audioConfigurationCurrent() {
    try {
      return audioTarget != null
          && audioTarget.embeddingIdentity().equals(audioModels.revision())
          && audioTarget.modelRevision().equals(audioModels.revision())
          && audioTarget.dimensions() == audioModels.dimensions()
          && audioDecoderRevision.equals(audioModels.decoderRevision())
          && audioTarget.projectionIdentity().equals(audioProjection.identity());
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private void checkAudio(Runnable current) {
    check(current);
    if (!audioConfigurationCurrent()) {
      throw new ApplicationException(FailureKind.CONFLICT, "configuration_changed", "模型配置已变化。");
    }
  }

  public IndexTarget imageTarget() {
    return imageTarget;
  }

  public boolean imageConfigurationCurrent() {
    try {
      return imageTarget != null
          && imageTarget.embeddingIdentity().equals(imageModels.revision())
          && imageTarget.modelRevision().equals(imageModels.revision())
          && imageTarget.dimensions() == imageModels.dimensions()
          && imageTarget.projectionIdentity().equals(imageProjection.identity());
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private void checkImages(Runnable current) {
    check(current);
    if (!imageConfigurationCurrent()) {
      throw new ApplicationException(FailureKind.CONFLICT, "configuration_changed", "模型配置已变化。");
    }
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
