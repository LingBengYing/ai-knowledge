package com.evidence.rag.service;

import com.evidence.rag.client.model.FactTextModels;
import com.evidence.rag.client.model.FactVisionModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.PublishedVideoCandidate;
import com.evidence.rag.model.domain.PublishedVideoEvidence;
import com.evidence.rag.model.domain.PublishedVideoGroup;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.VideoAnswerProposal;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VideoSourceEvidence;
import com.evidence.rag.model.domain.VideoTraceEvidence;
import com.evidence.rag.model.domain.VideoTraceFact;
import com.evidence.rag.model.domain.VideoTraceProof;
import com.evidence.rag.model.domain.VisualImage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Video retrieval and same-group proof, under the caller's complete scope and total budget. */
public final class VideoAnswerProposalService {
  public static final String PROMPT_REVISION = "java-video-answer-v1";
  private static final int MAX_CANDIDATES = 64;
  private static final String REFUSAL = "当前授权资料不足以可靠回答该问题。";
  private final EvidenceService evidence;
  private final TextModels text;
  private final FactTextModels factText;
  private final FactVisionModels vision;
  private final RetrievalProjection projection;
  private final IndexTarget target;
  private final VideoAssessmentService assessment;
  private final String textRevision;
  private final String visionRevision;
  private final String modelRevision;

  public VideoAnswerProposalService(
      EvidenceService evidence,
      TextModels text,
      FactTextModels factText,
      FactVisionModels vision,
      RetrievalProjection projection,
      IndexTarget target,
      Duration assessmentBudget) {
    if (evidence == null
        || text == null
        || factText == null
        || vision == null
        || projection == null
        || target == null
        || !target.modelRevision().equals(text.revision())
        || !target.projectionIdentity().equals(projection.identity())) {
      throw ModelValues.invalid();
    }
    this.evidence = evidence;
    this.text = text;
    this.factText = factText;
    this.vision = vision;
    this.projection = projection;
    this.target = target;
    assessment = new VideoAssessmentService(factText, vision, assessmentBudget);
    textRevision = ModelValues.identifier(factText.revision(), 200);
    visionRevision = ModelValues.identifier(vision.revision(), 200);
    modelRevision =
        "java-video-models-v1:"
            + sha(target.modelRevision() + "\n" + textRevision + "\n" + visionRevision);
  }

  public VideoAnswerProposal propose(
      EvidenceScope scope, String question, VideoAssessment.Mode mode, Runnable requireCurrent) {
    return propose(scope, question, null, mode, null, requireCurrent);
  }

  public VideoAnswerProposal propose(
      EvidenceScope scope,
      PreparedQuery query,
      VideoAssessment.Mode mode,
      QueryAttachmentService queries,
      Runnable requireCurrent) {
    if (query == null || (!query.attachments().isEmpty() && queries == null)) {
      throw ModelValues.invalid();
    }
    return propose(
        scope,
        query.originalQuestion(),
        query.attachments().isEmpty() ? null : query,
        mode,
        queries,
        requireCurrent);
  }

  private VideoAnswerProposal propose(
      EvidenceScope scope,
      String question,
      PreparedQuery query,
      VideoAssessment.Mode mode,
      QueryAttachmentService queries,
      Runnable requireCurrent) {
    if (scope == null || question == null || mode == null || requireCurrent == null) {
      throw ModelValues.invalid();
    }
    Runnable check =
        () -> {
          requireCurrent.run();
          if (!configurationCurrent() || (query != null && !queries.configurationCurrent())) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "configuration_changed", "问答配置已变化。");
          }
          evidence.hydrateVideo(scope, List.of());
        };
    check.run();
    var publications = evidence.videoPublications(scope);
    if (publications.isEmpty()) {
      return refused(question, "no_evidence");
    }
    var generations = new LinkedHashMap<String, String>();
    publications.forEach(p -> generations.put(p.documentId(), p.projectionGenerationId()));
    var authorized =
        new RetrievalProjection.AuthorizedScope(scope.actor().workspaceId(), generations);
    var candidates =
        query == null
            ? search(question, authorized, check)
            : queries.search(query, authorized, check, ids -> evidence.hydrateVideo(scope, ids));
    check.run();
    if (candidates == null || candidates.size() > MAX_CANDIDATES) {
      throw ModelValues.invalid();
    }
    var scores = new HashMap<String, Double>();
    for (var candidate : candidates) {
      if (candidate == null
          || !Double.isFinite(candidate.score())
          || scores.putIfAbsent(candidate.segmentId(), candidate.score()) != null) {
        throw ModelValues.invalid();
      }
    }
    if (candidates.isEmpty()) {
      return refused(question, "no_evidence");
    }
    var ids = candidates.stream().map(RetrievalProjection.Candidate::segmentId).toList();
    var sources = evidence.hydrateVideo(scope, ids);
    check.run();
    if (sources.isEmpty()) {
      return refused(question, "no_evidence");
    }
    var rankings =
        query == null
            ? text.rerank(
                question, sources.stream().map(PublishedVideoCandidate::recallText).toList())
            : rank(scope, query, sources, queries, check);
    check.run();
    if (rankings == null || rankings.size() != sources.size()) {
      throw ModelValues.invalid();
    }
    var rankScores = new HashMap<String, Double>();
    var seen = new HashSet<Integer>();
    for (var ranking : rankings) {
      if (ranking == null
          || ranking.index() < 0
          || ranking.index() >= sources.size()
          || !Double.isFinite(ranking.score())
          || !seen.add(ranking.index())) {
        throw ModelValues.invalid();
      }
      rankScores.put(sources.get(ranking.index()).physicalSegmentId(), ranking.score());
    }
    var currentSources = evidence.hydrateVideo(scope, ids);
    if (!sources.equals(currentSources)) {
      throw ModelValues.invalid();
    }
    var groups =
        evidence
            .videoGroups(
                scope, sources.stream().map(PublishedVideoCandidate::physicalSegmentId).toList())
            .stream()
            .filter(
                g ->
                    mode == VideoAssessment.Mode.VISUAL
                        ? g.framePhysicalSegmentId() != null
                        : mode == VideoAssessment.Mode.TRANSCRIPT
                            ? g.transcriptPhysicalSegmentId() != null
                            : g.framePhysicalSegmentId() != null
                                && g.transcriptPhysicalSegmentId() != null)
            .sorted(
                Comparator.<PublishedVideoGroup>comparingDouble(g -> groupScore(g, rankScores))
                    .reversed()
                    .thenComparing(g -> g.publication().publicationId())
                    .thenComparingInt(g -> g.group().ordinal()))
            .toList();
    String reason = "incomplete_evidence";
    for (var group : groups) {
      check.run();
      var material =
          evidence.videoEvidence(scope, group.publication().publicationId(), group.group().id());
      if (!material.source().equals(group)) {
        throw ModelValues.invalid();
      }
      var result =
          assessment.assess(
              question,
              material.proofInput(),
              mode,
              () -> {
                check.run();
                return true;
              });
      // The parent owns the whole request budget; preserve its exact reason after proof callbacks.
      check.run();
      if (result.supported()) {
        return answered(
            question, material, result, groupScore(group, scores), groupScore(group, rankScores));
      }
      if (!List.of("incomplete_evidence", "conflicting_evidence", "invalid_quote")
          .contains(result.refusalReason())) {
        return refused(question, result.refusalReason());
      }
      if (!"conflicting_evidence".equals(reason)) {
        reason = result.refusalReason();
      }
    }
    return refused(question, reason);
  }

  public boolean configurationCurrent() {
    return target.modelRevision().equals(text.revision())
        && target.projectionIdentity().equals(projection.identity())
        && textRevision.equals(factText.revision())
        && visionRevision.equals(vision.revision());
  }

  public String modelRevision() {
    return modelRevision;
  }

  private List<RetrievalProjection.Candidate> search(
      String question, RetrievalProjection.AuthorizedScope scope, Runnable current) {
    projection.prepareSearch();
    current.run();
    var vectors = text.embed(List.of(question));
    current.run();
    if (vectors == null
        || vectors.size() != 1
        || vectors.getFirst() == null
        || vectors.getFirst().size() != target.dimensions()
        || vectors.getFirst().stream().anyMatch(v -> v == null || !Double.isFinite(v))) {
      throw ModelValues.invalid();
    }
    return projection.search(
        new RetrievalProjection.Query(question, vectors.getFirst(), scope, MAX_CANDIDATES));
  }

  private List<TextModels.Ranked> rank(
      EvidenceScope scope,
      PreparedQuery query,
      List<PublishedVideoCandidate> sources,
      QueryAttachmentService queries,
      Runnable current) {
    var frames = new HashMap<String, PublishedVideoGroup>();
    if (!query.queryImages().isEmpty()) {
      current.run();
      for (var group :
          evidence.videoGroups(
              scope, sources.stream().map(PublishedVideoCandidate::physicalSegmentId).toList())) {
        if (group.framePhysicalSegmentId() != null) {
          frames.putIfAbsent(group.framePhysicalSegmentId(), group);
        }
      }
    }
    var candidates = new ArrayList<QueryRankCandidate>();
    for (var source : sources) {
      current.run();
      VisualImage image = null;
      if (!query.queryImages().isEmpty() && source.kind() == VideoTraceEvidence.Kind.VISUAL) {
        var group = frames.get(source.physicalSegmentId());
        if (group == null
            || !group.publication().equals(source.publication())
            || !group.group().frameId().equals(source.sourceId())) {
          throw ModelValues.invalid();
        }
        var material =
            evidence.videoEvidence(scope, group.publication().publicationId(), group.group().id());
        if (!material.source().equals(group)) {
          throw ModelValues.invalid();
        }
        image = material.proofInput().frame().image();
      }
      candidates.add(new QueryRankCandidate(source.recallText(), image));
    }
    return queries.rank(query, candidates, current);
  }

  private VideoAnswerProposal answered(
      String question,
      PublishedVideoEvidence source,
      VideoAssessment result,
      double retrieval,
      double rerank) {
    var group = source.source();
    if (!result.group().equals(group.group())
        || !result.sourceSha256().equals(group.publication().sourceSha256())
        || !result.manifestSha256().equals(group.manifestSha256())
        || !result.questionSha256().equals(sha(question))
        || !result.textModelRevision().equals(textRevision)
        || !result.visionModelRevision().equals(visionRevision)) {
      throw ModelValues.invalid();
    }
    var citations = new ArrayList<VideoSourceEvidence>();
    var facts = new ArrayList<VideoTraceFact>();
    var answer = new LinkedHashSet<String>();
    for (var proof : result.proofs()) {
      facts.add(
          new VideoTraceFact(
              facts.size(), proof.factId(), proof.visualSupport(), proof.transcriptSupport()));
      if (proof.visualSupport() == 1) {
        citations.add(
            new VideoSourceEvidence(
                source,
                new VideoTraceEvidence(
                    citations.size() + 1,
                    VideoTraceEvidence.Kind.VISUAL,
                    group.framePhysicalSegmentId(),
                    null,
                    null,
                    retrieval,
                    rerank,
                    proof.factId())));
        answer.addAll(proof.visualClaims());
      }
      for (var quote : proof.transcriptQuotes()) {
        if (!quote.physicalId().equals(group.group().transcriptSpanId())) {
          throw ModelValues.invalid();
        }
        var citation =
            new VideoSourceEvidence(
                source,
                new VideoTraceEvidence(
                    citations.size() + 1,
                    VideoTraceEvidence.Kind.TRANSCRIPT,
                    group.transcriptPhysicalSegmentId(),
                    quote.start(),
                    quote.end(),
                    retrieval,
                    rerank,
                    proof.factId()));
        if (!citation.quote().equals(quote.quote())) {
          throw ModelValues.invalid();
        }
        citations.add(citation);
        answer.add(quote.quote());
      }
      if (citations.size() > 32) {
        return refused(question, "evidence_capacity_exceeded");
      }
    }
    String rendered = String.join("\n", answer);
    var proof =
        new VideoTraceProof(
            group.publication().publicationId(),
            group.group().id(),
            result.mode(),
            facts,
            textRevision,
            visionRevision,
            result.policyRevision());
    return new VideoAnswerProposal(
        rendered,
        citations,
        new TraceDraft(
            sha(question),
            sha(rendered),
            "answered",
            null,
            modelRevision,
            PROMPT_REVISION,
            VideoAssessmentService.POLICY_REVISION,
            List.of(),
            List.of(),
            List.of(),
            citations.stream().map(VideoSourceEvidence::trace).toList(),
            proof));
  }

  private VideoAnswerProposal refused(String question, String reason) {
    return new VideoAnswerProposal(
        REFUSAL,
        List.of(),
        new TraceDraft(
            sha(question),
            null,
            "abstained",
            reason,
            modelRevision,
            PROMPT_REVISION,
            VideoAssessmentService.POLICY_REVISION,
            List.of()));
  }

  private static double groupScore(PublishedVideoGroup group, Map<String, Double> scores) {
    Double frame = scores.get(group.framePhysicalSegmentId());
    Double transcript = scores.get(group.transcriptPhysicalSegmentId());
    if (frame == null && transcript == null) {
      throw ModelValues.invalid();
    }
    return frame == null ? transcript : transcript == null ? frame : Math.max(frame, transcript);
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
