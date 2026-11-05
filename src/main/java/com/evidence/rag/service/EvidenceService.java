package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.KnowledgeEvidence;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProductHelpEvidence;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.PublishedVideoCandidate;
import com.evidence.rag.model.domain.PublishedVideoOcrEvidence;
import com.evidence.rag.model.domain.PublishedVideoSubtitleEvidence;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoTraceEvidence;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Current-ACL evidence authority and atomic final trace decision. Remote work is excluded. */
@Service
public final class EvidenceService {

  /** Classify every authorized physical hit before filtering; unknown hits never disappear. */
  private VideoCandidates classifiedVideo(EvidenceScope scope, List<String> ids) {
    var video = new HashMap<String, PublishedVideoCandidate>();
    var ocr = new HashMap<String, PublishedVideoOcrEvidence>();
    var subtitles = new HashMap<String, PublishedVideoSubtitleEvidence>();
    var seen = new HashSet<String>();
    for (var item : evidence.findPublishedVideoCandidates(scope, ids)) {
      if (!scope.publications().contains(item.publication())
          || !ids.contains(item.physicalSegmentId())
          || !seen.add(item.physicalSegmentId())) {
        throw ModelValues.invalid();
      }
      video.put(item.physicalSegmentId(), item);
    }
    for (var item : evidence.findPublishedVideoOcrEvidence(scope, ids)) {
      if (!scope.publications().contains(item.publication())
          || !ids.contains(item.physicalSegmentId())
          || !seen.add(item.physicalSegmentId())) {
        throw ModelValues.invalid();
      }
      ocr.put(item.physicalSegmentId(), item);
    }
    for (var item : evidence.findPublishedVideoSubtitleEvidence(scope, ids)) {
      if (!scope.publications().contains(item.publication())
          || !ids.contains(item.physicalSegmentId())
          || !seen.add(item.physicalSegmentId())) {
        throw ModelValues.invalid();
      }
      subtitles.put(item.physicalSegmentId(), item);
    }
    if (seen.size() != ids.size()) {
      throw ModelValues.invalid();
    }
    return new VideoCandidates(
        ids.stream().filter(video::containsKey).map(video::get).toList(),
        ids.stream().filter(ocr::containsKey).map(ocr::get).toList(),
        ids.stream().filter(subtitles::containsKey).map(subtitles::get).toList());
  }

  private record VideoCandidates(
      List<PublishedVideoCandidate> video,
      List<PublishedVideoOcrEvidence> ocr,
      List<PublishedVideoSubtitleEvidence> subtitles) {}

  private static final long MAX_PAGE_BYTES = 8L * 1024 * 1024;
  private final SqliteAuthorityStore store;
  private final EvidenceRepository evidence;
  private final ImageVectorRepository imageVectors;
  private final AudioVectorRepository audioVectors;
  private final IngestionRepository ingestion;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;

  public EvidenceService(
      SqliteAuthorityStore store,
      EvidenceRepository evidence,
      ManagementRepository management,
      DocumentPermissionPolicy permissions) {
    this.store = store;
    this.evidence = evidence;
    this.imageVectors = new ImageVectorRepository(store);
    this.audioVectors = new AudioVectorRepository(store);
    this.ingestion = new IngestionRepository(store);
    this.management = management;
    this.permissions = permissions;
  }

  public LibraryOperationGate operationGate() {
    return store.operationGate();
  }

  public EvidenceScope snapshot(Actor actor, DocumentSelection selection, IndexTarget target) {
    if (actor == null || selection == null || target == null) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          var publications = evidence.findActivePublications(actor, selection);
          if (!selection.all() && publications.size() != selection.documentIds().size()) {
            throw ModelValues.notFound();
          }
          for (var publication : publications) {
            permissions.require(management.currentRole(actor, publication.documentId()), false);
            if (!publication.target().equals(target)) {
              throw ModelValues.notFound();
            }
          }
          return new EvidenceScope(actor, selection, publications);
        });
  }

  /**
   * Final read-only decision: re-read the original complete selection and all candidate metadata.
   */
  public List<PublishedEvidence> finishRetrieval(
      EvidenceScope scope,
      List<String> physicalIds,
      IndexTarget target,
      BooleanSupplier configurationCurrent) {
    requireScope(scope);
    if (target == null || configurationCurrent == null) {
      throw ModelValues.invalid();
    }
    var ids = candidateIds(physicalIds);
    return store.transaction(
        () -> {
          if (!configurationCurrent.getAsBoolean()) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "configuration_changed", "文字模型配置发生变化，请重新检索。");
          }
          var publications = evidence.findActivePublications(scope.actor(), scope.selection());
          if (!new HashSet<>(publications).equals(new HashSet<>(scope.publications()))) {
            throw changed();
          }
          for (var publication : publications) {
            if (!permissions.canRead(
                    management.currentRole(scope.actor(), publication.documentId()))
                || !publication.target().equals(target)) {
              throw changed();
            }
          }
          var material = hydrated(scope, ids);
          if (!configurationCurrent.getAsBoolean()) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "configuration_changed", "文字模型配置发生变化，请重新检索。");
          }
          return material;
        });
  }

  /** The complete scope is retained while each product-help category gets its own search. */
  public List<PublicationVersion> productHelpPublications(EvidenceScope scope, boolean video) {
    requireScope(scope);
    return whileCurrent(
        scope,
        () -> {
          var publications =
              video
                  ? evidence.findVideoTextPublications(scope)
                  : evidence.findTextPublications(scope);
          if (!scope.publications().containsAll(publications)) {
            throw ModelValues.invalid();
          }
          return publications;
        });
  }

  /** Read-only final authority decision for product-help snippets, including non-matching scope. */
  public List<ProductHelpEvidence> finishProductHelp(
      EvidenceScope scope,
      List<String> physicalIds,
      boolean video,
      IndexTarget target,
      BooleanSupplier configurationCurrent) {
    requireScope(scope);
    if (target == null || configurationCurrent == null) {
      throw ModelValues.invalid();
    }
    var ids = candidateIds(physicalIds);
    return store.transaction(
        () -> {
          requireProductHelpCurrent(scope, target, configurationCurrent);
          var material = video ? productHelpVideo(scope, ids) : productHelpDocuments(scope, ids);
          requireProductHelpCurrent(scope, target, configurationCurrent);
          return material;
        });
  }

  private void requireProductHelpCurrent(
      EvidenceScope scope, IndexTarget target, BooleanSupplier configurationCurrent) {
    if (!configurationCurrent.getAsBoolean()) {
      throw new ApplicationException(
          FailureKind.CONFLICT, "configuration_changed", "文字模型配置发生变化，请重新检索。");
    }
    var publications = evidence.findActivePublications(scope.actor(), scope.selection());
    if (!new HashSet<>(publications).equals(new HashSet<>(scope.publications()))) {
      throw changed();
    }
    for (var publication : publications) {
      if (!permissions.canRead(management.currentRole(scope.actor(), publication.documentId()))
          || !publication.target().equals(target)) {
        throw changed();
      }
    }
  }

  private List<ProductHelpEvidence> productHelpDocuments(EvidenceScope scope, List<String> ids) {
    var result = new ArrayList<ProductHelpEvidence>();
    for (var source : hydrated(scope, ids)) {
      var publication = source.publication();
      var document =
          management
              .findAuthorizedDocument(scope.actor(), publication.documentId(), false)
              .orElseThrow(ModelValues::notFound);
      if (!document.registrationRevisionId().equals(publication.sourceRevisionId())
          || !document.sourceSha256().equals(publication.sourceSha256())
          || !document.filename().equals(source.filename())) {
        throw changed();
      }
      var segment = source.segment();
      result.add(
          new ProductHelpEvidence(
              publication,
              source.physicalSegmentId(),
              source.filename(),
              document.mimeType(),
              ProductHelpEvidence.Kind.DOCUMENT_TEXT,
              segment.text(),
              segment.page(),
              segment.start(),
              segment.end(),
              null,
              null,
              document.mimeType().startsWith("image/")
                      || publication.parserRevision().contains("ocr")
                  ? "machine_ocr"
                  : "source_text"));
    }
    return List.copyOf(result);
  }

  private List<ProductHelpEvidence> productHelpVideo(EvidenceScope scope, List<String> ids) {
    // Validate every physical hit, including captions, before excluding non-text material.
    var classified = classifiedVideo(scope, ids);
    var eligible = new HashSet<>(evidence.findVideoTextPublications(scope));
    var sources = new HashMap<String, ProductHelpEvidence>();
    for (var candidate : classified.video()) {
      if (!eligible.contains(candidate.publication())) {
        throw ModelValues.invalid();
      }
      if (candidate.kind() != VideoTraceEvidence.Kind.TRANSCRIPT) {
        continue;
      }
      var source = evidence.findVideoTranscriptSpan(candidate.publication(), candidate.sourceId());
      if (source.indexOrdinal() == null || !source.span().text().equals(candidate.recallText())) {
        throw ModelValues.invalid();
      }
      sources.put(
          candidate.physicalSegmentId(),
          new ProductHelpEvidence(
              candidate.publication(),
              candidate.physicalSegmentId(),
              candidate.filename(),
              candidate.mediaType(),
              ProductHelpEvidence.Kind.VIDEO_TRANSCRIPT,
              source.span().text(),
              null,
              null,
              null,
              source.span().startMs() * 1000,
              source.span().endMs() * 1000,
              "machine_asr"));
    }
    for (var source : classified.ocr()) {
      sources.put(
          source.physicalSegmentId(),
          new ProductHelpEvidence(
              source.publication(),
              source.physicalSegmentId(),
              source.filename(),
              source.mediaType(),
              ProductHelpEvidence.Kind.VIDEO_FRAME_OCR,
              source.grounding().snippet(),
              null,
              null,
              null,
              source.framePresentationUs(),
              source.framePresentationUs() + source.frameDurationUs(),
              "machine_ocr"));
    }
    for (var source : classified.subtitles()) {
      sources.put(
          source.physicalSegmentId(),
          new ProductHelpEvidence(
              source.publication(),
              source.physicalSegmentId(),
              source.filename(),
              source.mediaType(),
              ProductHelpEvidence.Kind.VIDEO_SUBTITLE,
              source.grounding().snippet(),
              null,
              null,
              null,
              source.source().startUs(),
              source.source().endUs(),
              "embedded_subtitle"));
    }
    return ids.stream().filter(sources::containsKey).map(sources::get).toList();
  }

  public List<KnowledgeEvidence> knowledgeEvidence(
      EvidenceScope scope, List<KnowledgeEvidence.Key> keys) {
    return store.transaction(() -> knowledgeEvidenceInTransaction(scope, keys));
  }

  /** Package-local commit/read operation; the caller already owns the authority transaction. */
  void requireKnowledgeScopeInTransaction(EvidenceScope scope) {
    requireScope(scope);
    if (!current(scope)) {
      throw changed();
    }
  }

  List<KnowledgeEvidence> knowledgeEvidenceInTransaction(
      EvidenceScope scope, List<KnowledgeEvidence.Key> keys) {
    requireKnowledgeScopeInTransaction(scope);
    if (keys == null || keys.size() > 64 || keys.stream().anyMatch(Objects::isNull)) {
      throw ModelValues.invalid();
    }
    candidateIds(keys.stream().map(KnowledgeEvidence.Key::physicalId).toList());
    var textIds =
        keys.stream()
            .filter(key -> key.kind() == ProductHelpEvidence.Kind.DOCUMENT_TEXT)
            .map(KnowledgeEvidence.Key::physicalId)
            .toList();
    var videoIds =
        keys.stream()
            .filter(key -> key.kind() != ProductHelpEvidence.Kind.DOCUMENT_TEXT)
            .map(KnowledgeEvidence.Key::physicalId)
            .toList();
    var material = new HashMap<String, ProductHelpEvidence>();
    productHelpDocuments(scope, textIds)
        .forEach(source -> material.put(source.physicalId(), source));
    productHelpVideo(scope, videoIds).forEach(source -> material.put(source.physicalId(), source));
    var contexts = new HashMap<String, GroundingText>();
    for (var source : hydrated(scope, textIds)) {
      contexts.put(
          source.physicalSegmentId(),
          new GroundingText(
              source.physicalSegmentId(),
              source.publication().publicationId() + "/page/" + source.page().number(),
              source.page().text(),
              source.pageSha256(),
              source.segment().start(),
              source.segment().end()));
    }
    var videos = classifiedVideo(scope, videoIds);
    var compilations = new HashMap<String, VideoCompilation>();
    for (var candidate : videos.video()) {
      if (candidate.kind() != VideoTraceEvidence.Kind.TRANSCRIPT) {
        continue;
      }
      var publication = candidate.publication();
      var compilation =
          compilations.computeIfAbsent(
              publication.sourceRevisionId(),
              revision ->
                  ingestion.findVideoCompilation(revision).orElseThrow(ModelValues::invalid));
      if (compilation.audio() == null
          || !compilation.sourceSha256().equals(publication.sourceSha256())
          || !compilation.compilerRevision().equals(publication.parserRevision())) {
        throw ModelValues.invalid();
      }
      var selected = evidence.findVideoTranscriptSpan(publication, candidate.sourceId());
      String body =
          compilation.audio().spans().stream()
              .map(AudioTranscriptSpan::text)
              .collect(Collectors.joining("\n"));
      int start = 0;
      for (var span : compilation.audio().spans()) {
        if (span.ordinal() >= selected.span().ordinal()) {
          break;
        }
        start += span.text().codePointCount(0, span.text().length()) + 1;
      }
      contexts.put(
          candidate.physicalSegmentId(),
          new GroundingText(
              candidate.physicalSegmentId(),
              publication.publicationId() + ":video-transcript",
              body,
              ModelValues.sha256(body.getBytes(StandardCharsets.UTF_8)),
              start,
              start + selected.span().text().codePointCount(0, selected.span().text().length())));
    }
    videos.ocr().forEach(source -> contexts.put(source.physicalSegmentId(), source.grounding()));
    videos
        .subtitles()
        .forEach(source -> contexts.put(source.physicalSegmentId(), source.grounding()));
    var result = new ArrayList<KnowledgeEvidence>();
    var uniqueContexts = new HashSet<String>();
    long bytes = 0;
    for (var key : keys) {
      var source = material.get(key.physicalId());
      var context = contexts.get(key.physicalId());
      if (source == null || source.kind() != key.kind() || context == null) {
        throw ModelValues.invalid();
      }
      if (uniqueContexts.add(context.contextId())) {
        bytes += context.contextText().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PAGE_BYTES) {
          throw new ApplicationException(
              FailureKind.CAPACITY_EXCEEDED, "evidence_capacity_exceeded", "文字证据超过处理上限，请缩小资料范围。");
        }
      }
      result.add(new KnowledgeEvidence(source, context));
    }
    return List.copyOf(result);
  }

  public List<PublicationVersion> textPublications(EvidenceScope snapshot) {
    return scopedPublications(snapshot, evidence::findTextPublications);
  }

  private List<PublicationVersion> scopedPublications(
      EvidenceScope scope, Function<EvidenceScope, List<PublicationVersion>> find) {
    requireScope(scope);
    return whileCurrent(
        scope,
        () -> {
          var publications = find.apply(scope);
          if (!scope.publications().containsAll(publications)) {
            throw ModelValues.invalid();
          }
          return publications;
        });
  }

  /** One authority transaction that first proves the scope is still current and readable. */
  private <T> T whileCurrent(EvidenceScope scope, Supplier<T> read) {
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          return read.get();
        });
  }

  private boolean current(EvidenceScope scope) {
    var selected =
        DocumentSelection.selected(
            scope.publications().stream().map(PublicationVersion::documentId).toList());
    var current = evidence.findActivePublications(scope.actor(), selected);
    if (!new HashSet<>(current).equals(new HashSet<>(scope.publications()))) {
      return false;
    }
    for (var publication : scope.publications()) {
      if (!permissions.canRead(management.currentRole(scope.actor(), publication.documentId()))) {
        return false;
      }
    }
    return true;
  }

  private List<PublishedEvidence> hydrated(EvidenceScope scope, List<String> ids) {
    if (evidence.publishedPageBytes(scope, ids) > MAX_PAGE_BYTES) {
      throw new ApplicationException(
          FailureKind.CAPACITY_EXCEEDED, "evidence_capacity_exceeded", "引用资料页超过处理上限，请缩小资料范围。");
    }
    return inRequestedOrder(
        scope,
        ids,
        evidence.findPublishedEvidence(scope, ids),
        PublishedEvidence::publication,
        PublishedEvidence::physicalSegmentId);
  }

  /** Exactly one in-scope item per requested id, in request order. */
  private static <T> List<T> inRequestedOrder(
      EvidenceScope scope,
      List<String> ids,
      List<T> items,
      Function<T, PublicationVersion> publication,
      Function<T, String> physicalId) {
    if (items.size() != ids.size()) {
      throw ModelValues.invalid();
    }
    var byId = new HashMap<String, T>();
    for (var item : items) {
      if (!scope.publications().contains(publication.apply(item))
          || byId.put(physicalId.apply(item), item) != null) {
        throw ModelValues.invalid();
      }
    }
    return ids.stream().map(byId::get).toList();
  }

  private static List<String> candidateIds(List<String> physicalIds) {
    if (physicalIds == null || physicalIds.size() > 64) {
      throw ModelValues.invalid();
    }
    var seen = new HashSet<String>();
    for (String id : physicalIds) {
      ModelValues.identifier(id, 128);
      if (!seen.add(id)) {
        throw ModelValues.invalid();
      }
    }
    return List.copyOf(physicalIds);
  }

  private static void requireScope(EvidenceScope scope) {
    if (scope == null) {
      throw ModelValues.invalid();
    }
  }

  private static ApplicationException changed() {
    return new ApplicationException(FailureKind.CONFLICT, "scope_changed", "资料授权或已发布证据发生变化，请重新查询。");
  }
}
