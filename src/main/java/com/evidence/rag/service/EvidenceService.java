package com.evidence.rag.service;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.AudioEvidence;
import com.evidence.rag.model.domain.AudioSourceEvidence;
import com.evidence.rag.model.domain.AudioVectorPublication;
import com.evidence.rag.model.domain.AudioVectorScope;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.ImageVectorScope;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.PublishedAudioEvidence;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.PublishedImageEvidence;
import com.evidence.rag.model.domain.PublishedVideoCandidate;
import com.evidence.rag.model.domain.PublishedVideoEvidence;
import com.evidence.rag.model.domain.PublishedVideoGroup;
import com.evidence.rag.model.domain.PublishedVideoOcrEvidence;
import com.evidence.rag.model.domain.PublishedVideoSubtitleEvidence;
import com.evidence.rag.model.domain.SourceAudio;
import com.evidence.rag.model.domain.SourceEvidence;
import com.evidence.rag.model.domain.SourceImage;
import com.evidence.rag.model.domain.SourceVideo;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.TraceEvidence;
import com.evidence.rag.model.domain.VideoOcrSourceEvidence;
import com.evidence.rag.model.domain.VideoSourceEvidence;
import com.evidence.rag.model.domain.VideoSubtitleSourceEvidence;
import com.evidence.rag.model.domain.VideoTraceEvidence;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.domain.VisualSourceEvidence;
import com.evidence.rag.model.dto.TraceReceipt;
import com.evidence.rag.model.entity.TraceAudioCitationEntity;
import com.evidence.rag.model.entity.TraceCitationEntity;
import com.evidence.rag.model.entity.TraceImageCitationEntity;
import com.evidence.rag.model.entity.TraceVideoCitationEntity;
import com.evidence.rag.model.entity.TraceVideoOcrCitationEntity;
import com.evidence.rag.model.entity.TraceVideoSubtitleCitationEntity;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.AudioInput;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.tool.parser.VideoInput;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Current-ACL evidence authority and atomic final trace decision. Remote work is excluded. */
@Service
public final class EvidenceService {
  public List<PublicationVersion> videoPublications(EvidenceScope scope) {
    requireScope(scope);
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          var publications = evidence.findVideoPublications(scope);
          if (!scope.publications().containsAll(publications)) {
            throw ModelValues.invalid();
          }
          return publications;
        });
  }

  public List<PublishedVideoCandidate> hydrateVideo(EvidenceScope scope, List<String> physicalIds) {
    requireScope(scope);
    var ids = candidateIds(physicalIds);
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          return classifiedVideo(scope, ids).video();
        });
  }

  public List<PublicationVersion> videoOcrPublications(EvidenceScope scope) {
    requireScope(scope);
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          var publications = evidence.findVideoOcrPublications(scope);
          if (!scope.publications().containsAll(publications)) {
            throw ModelValues.invalid();
          }
          return publications;
        });
  }

  public List<PublishedVideoOcrEvidence> hydrateVideoOcr(
      EvidenceScope scope, List<String> physicalIds) {
    requireScope(scope);
    var ids = candidateIds(physicalIds);
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          return classifiedVideo(scope, ids).ocr();
        });
  }

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

  public List<PublicationVersion> videoSubtitlePublications(EvidenceScope scope) {
    requireScope(scope);
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          var publications = evidence.findVideoSubtitlePublications(scope);
          if (!scope.publications().containsAll(publications)) {
            throw ModelValues.invalid();
          }
          return publications;
        });
  }

  public List<PublishedVideoSubtitleEvidence> hydrateVideoSubtitle(
      EvidenceScope scope, List<String> physicalIds) {
    requireScope(scope);
    var ids = candidateIds(physicalIds);
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          return classifiedVideo(scope, ids).subtitles();
        });
  }

  public VideoSubtitleSourceEvidence videoSubtitleExcerpt(
      EvidenceScope scope, TraceEvidence trace) {
    requireScope(scope);
    if (trace == null) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          return subtitleMaterial(scope, trace);
        });
  }

  private VideoSubtitleSourceEvidence subtitleMaterial(EvidenceScope scope, TraceEvidence trace) {
    var sources = classifiedVideo(scope, List.of(trace.physicalSegmentId())).subtitles();
    if (sources.size() != 1) {
      throw ModelValues.notFound();
    }
    return new VideoSubtitleSourceEvidence(sources.getFirst(), trace, null);
  }

  /** Null identifies another source kind only after complete saved scope authorization succeeds. */
  public VideoSubtitleSourceEvidence videoSubtitleSource(Actor actor, String traceId, int ordinal) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(traceId, 128);
    if (ordinal < 1 || ordinal > 32) {
      throw ModelValues.notFound();
    }
    return store.transaction(
        () -> {
          var scope = evidence.findTraceScope(actor, traceId);
          if (scope == null || !current(scope)) {
            throw ModelValues.notFound();
          }
          var saved = evidence.findTraceVideoSubtitleCitation(actor, traceId, ordinal);
          if (saved == null) {
            return null;
          }
          var source = subtitleMaterial(scope, saved.evidence());
          if (!subtitleCitation(source).equals(saved)) {
            throw ModelValues.notFound();
          }
          var publication = source.source().publication();
          byte[] original = evidence.findVideoOriginal(actor, publication, VideoInput.MAX_BYTES);
          if (original == null
              || !ModelValues.sha256(original).equals(publication.sourceSha256())) {
            throw ModelValues.notFound();
          }
          try {
            VideoInput.validateEnvelope(
                source.source().filename(), source.source().mediaType(), original);
          } catch (TextParser.Failure invalid) {
            throw ModelValues.notFound();
          }
          return new VideoSubtitleSourceEvidence(
              source.source(),
              saved.evidence(),
              new SourceVideo(source.source().mediaType(), original));
        });
  }

  private static TraceVideoSubtitleCitationEntity subtitleCitation(
      VideoSubtitleSourceEvidence source) {
    var item = source.source();
    return new TraceVideoSubtitleCitationEntity(
        source.trace(),
        item.publication().publicationId(),
        item.source().id(),
        item.track().id(),
        item.publication().sourceSha256(),
        item.subtitleManifestSha256(),
        item.nativeManifestSha256(),
        item.trackTextSha256(),
        item.source().cue().payloadSha256(),
        ModelValues.sha256(source.quote().getBytes(StandardCharsets.UTF_8)));
  }

  public VideoOcrSourceEvidence videoOcrExcerpt(EvidenceScope scope, TraceEvidence trace) {
    requireScope(scope);
    if (trace == null) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          return ocrMaterial(scope, trace);
        });
  }

  private VideoOcrSourceEvidence ocrMaterial(EvidenceScope scope, TraceEvidence trace) {
    var items = classifiedVideo(scope, List.of(trace.physicalSegmentId())).ocr();
    if (items.size() != 1) {
      throw ModelValues.notFound();
    }
    var source = items.getFirst();
    var frame =
        evidence.findVideoFrame(scope.actor(), source.publication(), source.source().frameId());
    return new VideoOcrSourceEvidence(source, trace, frame, null);
  }

  /** Null means a current authorized trace uses an older video proof kind, not failed authority. */
  public VideoOcrSourceEvidence videoOcrSource(Actor actor, String traceId, int ordinal) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(traceId, 128);
    if (ordinal < 1 || ordinal > 32) {
      throw ModelValues.notFound();
    }
    return store.transaction(
        () -> {
          var scope = evidence.findTraceScope(actor, traceId);
          if (scope == null || !current(scope)) {
            throw ModelValues.notFound();
          }
          var saved = evidence.findTraceVideoOcrCitation(actor, traceId, ordinal);
          if (saved == null) {
            return null;
          }
          var source = ocrMaterial(scope, saved.evidence());
          if (!ocrCitation(source).equals(saved)) {
            throw ModelValues.notFound();
          }
          var publication = source.source().publication();
          byte[] bytes = evidence.findVideoOriginal(actor, publication, VideoInput.MAX_BYTES);
          if (bytes == null || !ModelValues.sha256(bytes).equals(publication.sourceSha256())) {
            throw ModelValues.notFound();
          }
          try {
            VideoInput.validateEnvelope(
                source.source().filename(), source.source().mediaType(), bytes);
          } catch (TextParser.Failure invalid) {
            throw ModelValues.notFound();
          }
          return new VideoOcrSourceEvidence(
              source.source(),
              saved.evidence(),
              source.frame(),
              new SourceVideo(source.source().mediaType(), bytes));
        });
  }

  private static TraceVideoOcrCitationEntity ocrCitation(VideoOcrSourceEvidence source) {
    var item = source.source();
    return new TraceVideoOcrCitationEntity(
        source.trace(),
        item.publication().publicationId(),
        item.source().id(),
        item.source().frameId(),
        item.publication().sourceSha256(),
        item.frame().frameSha256(),
        item.ocrManifestSha256(),
        ModelValues.sha256(item.frame().text().getBytes(StandardCharsets.UTF_8)),
        ModelValues.sha256(source.quote().getBytes(StandardCharsets.UTF_8)));
  }

  public List<PublishedVideoGroup> videoGroups(EvidenceScope scope, List<String> physicalIds) {
    requireScope(scope);
    var ids = candidateIds(physicalIds);
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          var groups = evidence.findPublishedVideoGroups(scope, ids);
          var seen = new HashSet<String>();
          for (var group : groups) {
            if (!scope.publications().contains(group.publication())
                || !seen.add(group.publication().publicationId() + "/" + group.group().id())
                || !(group.framePhysicalSegmentId() != null
                        && ids.contains(group.framePhysicalSegmentId())
                    || group.transcriptPhysicalSegmentId() != null
                        && ids.contains(group.transcriptPhysicalSegmentId()))) {
              throw ModelValues.invalid();
            }
          }
          return List.copyOf(groups);
        });
  }

  public PublishedVideoEvidence videoEvidence(
      EvidenceScope scope, String publicationId, String groupId) {
    requireScope(scope);
    ModelValues.identifier(publicationId, 128);
    ModelValues.identifier(groupId, 128);
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          return videoMaterial(scope, publicationId, groupId);
        });
  }

  public VideoSourceEvidence videoSource(Actor actor, String traceId, int citationOrdinal) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(traceId, 128);
    if (citationOrdinal < 1 || citationOrdinal > 32) {
      throw ModelValues.notFound();
    }
    return store.transaction(
        () -> {
          var scope = evidence.findTraceScope(actor, traceId);
          if (scope == null || !current(scope)) {
            throw ModelValues.notFound();
          }
          var saved = evidence.findTraceVideoCitation(actor, traceId, citationOrdinal);
          var proof = evidence.findTraceVideoProof(actor, traceId);
          if (saved == null
              || proof == null
              || !proof.publicationId().equals(saved.publicationId())
              || !proof.groupId().equals(saved.groupId())) {
            throw ModelValues.notFound();
          }
          var material = videoMaterial(scope, saved.publicationId(), saved.groupId());
          var source = new VideoSourceEvidence(material, saved.evidence());
          if (!videoCitation(source).equals(saved)) {
            throw ModelValues.notFound();
          }
          var publication = material.source().publication();
          byte[] bytes = evidence.findVideoOriginal(actor, publication, VideoInput.MAX_BYTES);
          if (bytes == null || !ModelValues.sha256(bytes).equals(publication.sourceSha256())) {
            throw ModelValues.notFound();
          }
          try {
            VideoInput.validateEnvelope(
                material.source().filename(), material.source().mediaType(), bytes);
          } catch (TextParser.Failure invalid) {
            throw ModelValues.notFound();
          }
          return new VideoSourceEvidence(
              material, saved.evidence(), new SourceVideo(material.source().mediaType(), bytes));
        });
  }

  private PublishedVideoEvidence videoMaterial(
      EvidenceScope scope, String publicationId, String groupId) {
    var material = evidence.findPublishedVideoEvidence(scope, publicationId, groupId);
    if (material == null
        || !scope.publications().contains(material.source().publication())
        || !publicationId.equals(material.source().publication().publicationId())
        || !groupId.equals(material.source().group().id())) {
      throw ModelValues.notFound();
    }
    return material;
  }

  private static TraceVideoCitationEntity videoCitation(VideoSourceEvidence source) {
    var published = source.source();
    var material = published.proofInput();
    boolean visual = source.trace().kind() == VideoTraceEvidence.Kind.VISUAL;
    return new TraceVideoCitationEntity(
        source.trace(),
        published.source().publication().publicationId(),
        material.group().id(),
        visual ? material.group().frameId() : null,
        visual ? null : material.group().transcriptSpanId(),
        material.sourceSha256(),
        material.manifestSha256(),
        visual ? material.frame().image().sha256() : null,
        visual ? null : published.transcriptSpan().span().textSha256(),
        visual ? null : ModelValues.sha256(source.quote().getBytes(StandardCharsets.UTF_8)));
  }

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
          if (publications.size() > 128) {
            throw new ApplicationException(
                FailureKind.CAPACITY_EXCEEDED,
                "scope_capacity_exceeded",
                "当前授权范围超过处理上限，请明确缩小资料范围。");
          }
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

  public List<PublishedEvidence> hydrate(EvidenceScope snapshot, List<String> physicalIds) {
    requireScope(snapshot);
    var ids = candidateIds(physicalIds);
    return store.transaction(
        () -> {
          if (!current(snapshot)) {
            throw changed();
          }
          return hydrated(snapshot, ids);
        });
  }

  public List<PublicationVersion> textPublications(EvidenceScope snapshot) {
    requireScope(snapshot);
    return store.transaction(
        () -> {
          if (!current(snapshot)) {
            throw changed();
          }
          var texts = evidence.findTextPublications(snapshot);
          if (!snapshot.publications().containsAll(texts)) {
            throw ModelValues.invalid();
          }
          return texts;
        });
  }

  public List<PublicationVersion> imagePublications(EvidenceScope snapshot) {
    requireScope(snapshot);
    return store.transaction(
        () -> {
          if (!current(snapshot)) {
            throw changed();
          }
          var images = evidence.findImagePublications(snapshot);
          if (!snapshot.publications().containsAll(images)) {
            throw ModelValues.invalid();
          }
          return images;
        });
  }

  public List<PublicationVersion> audioPublications(EvidenceScope snapshot) {
    requireScope(snapshot);
    return store.transaction(
        () -> {
          if (!current(snapshot)) {
            throw changed();
          }
          var audio = evidence.findAudioPublications(snapshot);
          if (!snapshot.publications().containsAll(audio)) {
            throw ModelValues.invalid();
          }
          return audio;
        });
  }

  public List<PublishedAudioEvidence> hydrateAudio(
      EvidenceScope snapshot, List<String> physicalIds) {
    requireScope(snapshot);
    var ids = candidateIds(physicalIds);
    return store.transaction(
        () -> {
          if (!current(snapshot)) {
            throw changed();
          }
          return hydratedAudio(snapshot, ids);
        });
  }

  public AudioSourceEvidence audioSource(Actor actor, String traceId, int citationOrdinal) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(traceId, 128);
    if (citationOrdinal < 1 || citationOrdinal > 32) {
      throw ModelValues.notFound();
    }
    return store.transaction(
        () -> {
          var scope = evidence.findTraceScope(actor, traceId);
          if (scope == null || !current(scope)) {
            throw ModelValues.notFound();
          }
          var saved = evidence.findTraceAudioCitation(actor, traceId, citationOrdinal);
          if (saved == null) {
            throw ModelValues.notFound();
          }
          var item = hydratedAudio(scope, List.of(saved.evidence().physicalSegmentId())).getFirst();
          var source =
              new AudioSourceEvidence(
                  item, saved.evidence().startCodePoint(), saved.evidence().endCodePoint());
          if (!item.publication().publicationId().equals(saved.publicationId())
              || !item.span().id().equals(saved.audioSpanId())
              || source.startMs() != saved.startMs()
              || source.endMs() != saved.endMs()
              || !item.publication().sourceSha256().equals(saved.sourceSha256())
              || !item.span().textSha256().equals(saved.textSha256())
              || !item.transcript().contextSha256().equals(saved.transcriptSha256())
              || !audioQuoteHash(source).equals(saved.quoteSha256())) {
            throw ModelValues.notFound();
          }
          byte[] original =
              evidence.findAudioOriginal(actor, item.publication(), AudioInput.MAX_BYTES);
          if (original == null
              || !ModelValues.sha256(original).equals(item.publication().sourceSha256())) {
            throw ModelValues.notFound();
          }
          try {
            AudioInput.validateEnvelope(item.filename(), item.mediaType(), original);
            return new AudioSourceEvidence(
                item,
                source.startCodePoint(),
                source.endCodePoint(),
                new SourceAudio(item.mediaType(), original));
          } catch (TextParser.Failure invalid) {
            throw ModelValues.notFound();
          }
        });
  }

  public AudioVectorScope audioVectorScope(
      EvidenceScope scope, IndexTarget audioTarget, String decoderRevision) {
    requireScope(scope);
    if (audioTarget == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(decoderRevision, 200);
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          return new AudioVectorScope(
              scope,
              audioTarget,
              decoderRevision,
              verifiedAudioVectors(scope, audioTarget, decoderRevision));
        });
  }

  public List<PublishedAudioEvidence> hydrateAudioVectors(
      AudioVectorScope scope, List<String> vectorIds) {
    if (scope == null) {
      throw ModelValues.invalid();
    }
    var ids = candidateIds(vectorIds);
    return store.transaction(
        () -> {
          if (!current(scope.base())) {
            throw changed();
          }
          if (!audioVectorsCurrent(scope)) {
            throw audioVectorRequired();
          }
          var byId = new HashMap<String, String>();
          for (var publication : scope.publications()) {
            for (var entry : publication.entries()) {
              byId.put(entry.vectorPhysicalSegmentId(), entry.basePhysicalSegmentId());
            }
          }
          var baseIds = new ArrayList<String>();
          for (String id : ids) {
            String baseId = byId.get(id);
            if (baseId == null) {
              throw ModelValues.invalid();
            }
            baseIds.add(baseId);
          }
          return hydratedAudio(scope.base(), baseIds);
        });
  }

  private List<AudioVectorPublication> verifiedAudioVectors(
      EvidenceScope scope, IndexTarget audioTarget, String decoderRevision) {
    var publications = evidence.findAudioPublications(scope);
    if (!scope.publications().containsAll(publications)) {
      throw ModelValues.invalid();
    }
    var vectors =
        audioVectors.findPublications(scope.actor().workspaceId(), publications, audioTarget);
    if (vectors.size() != publications.size()
        || !new HashSet<>(vectors.stream().map(AudioVectorPublication::basePublication).toList())
            .equals(new HashSet<>(publications))) {
      throw audioVectorRequired();
    }
    for (var vector : vectors) {
      var base = vector.basePublication();
      var compilation =
          ingestion
              .findAudioCompilation(base.sourceRevisionId())
              .orElseThrow(EvidenceService::audioVectorRequired);
      if (!compilation.sourceSha256().equals(base.sourceSha256())
          || !compilation.compilerRevision().equals(base.parserRevision())
          || !compilation.decoderRevision().equals(decoderRevision)
          || !vector.decoderRevision().equals(decoderRevision)
          || !vector.target().equals(audioTarget)) {
        throw audioVectorRequired();
      }
      var expected =
          AudioEvidence.fromCompilation(base.sourceRevisionId(), compilation).stream()
              .filter(span -> span.indexOrdinal() != null)
              .toList();
      if (expected.size() != vector.entries().size()) {
        throw audioVectorRequired();
      }
      var digests = new HashMap<String, String>();
      var ids = new ArrayList<String>();
      for (int index = 0; index < expected.size(); index++) {
        var span = expected.get(index);
        var entry = vector.entries().get(index);
        boolean last = span.ordinal() == compilation.spans().size() - 1;
        if (!entry.audioEvidenceId().equals(span.id())
            || entry.ordinal() != span.ordinal()
            || entry.startSample() != span.startMs() * 16
            || (entry.endSample() + 15) / 16 != span.endMs()
            || (!last && entry.endSample() != span.endMs() * 16)
            || !entry
                .basePhysicalSegmentId()
                .equals(
                    RetrievalProjection.physicalSegmentId(base.projectionGenerationId(), span.id()))
            || !entry
                .vectorPhysicalSegmentId()
                .equals(
                    RetrievalProjection.physicalSegmentId(
                        vector.vectorGenerationId(), span.id()))) {
          throw audioVectorRequired();
        }
        ids.add(entry.basePhysicalSegmentId());
        digests.put(entry.vectorPhysicalSegmentId(), entry.entrySha256());
      }
      var manifest =
          new RetrievalProjection.RevisionManifest(
              scope.actor().workspaceId(), base.documentId(), vector.vectorGenerationId(), digests);
      if (!manifest.sha256().equals(vector.manifestSha256())) {
        throw audioVectorRequired();
      }
      var material = hydratedAudio(scope, ids);
      for (int index = 0; index < expected.size(); index++) {
        if (!material.get(index).publication().equals(base)
            || !material.get(index).span().equals(expected.get(index))) {
          throw audioVectorRequired();
        }
      }
    }
    return vectors;
  }

  private boolean audioVectorsCurrent(AudioVectorScope scope) {
    try {
      return new HashSet<>(
              verifiedAudioVectors(scope.base(), scope.target(), scope.decoderRevision()))
          .equals(new HashSet<>(scope.publications()));
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private static ApplicationException audioVectorRequired() {
    return new ApplicationException(
        FailureKind.CONFLICT, "audio_vector_required", "请为当前范围的全部音频建立完整原声向量。");
  }

  public ImageVectorScope imageVectorScope(EvidenceScope scope, IndexTarget imageTarget) {
    requireScope(scope);
    if (imageTarget == null) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          if (!current(scope)) {
            throw changed();
          }
          return new ImageVectorScope(scope, imageTarget, verifiedImageVectors(scope, imageTarget));
        });
  }

  public List<PublishedImageEvidence> hydrateImageVectors(
      ImageVectorScope scope, List<String> vectorIds) {
    if (scope == null) {
      throw ModelValues.invalid();
    }
    var ids = candidateIds(vectorIds);
    return store.transaction(
        () -> {
          if (!current(scope.authority())) {
            throw changed();
          }
          if (!imageVectorsCurrent(scope)) {
            throw imageVectorRequired();
          }
          var byId = new HashMap<String, String>();
          for (var publication : scope.publications()) {
            byId.put(publication.vectorPhysicalSegmentId(), publication.basePhysicalSegmentId());
          }
          var baseIds = new ArrayList<String>();
          for (String id : ids) {
            String baseId = byId.get(id);
            if (baseId == null) {
              throw ModelValues.invalid();
            }
            baseIds.add(baseId);
          }
          return hydratedImages(scope.authority(), baseIds);
        });
  }

  private List<ImageVectorPublication> verifiedImageVectors(
      EvidenceScope scope, IndexTarget imageTarget) {
    var publications = evidence.findImagePublications(scope);
    if (!scope.publications().containsAll(publications)) {
      throw ModelValues.invalid();
    }
    var vectors =
        imageVectors.findPublications(scope.actor().workspaceId(), publications, imageTarget);
    if (vectors.size() != publications.size()
        || !new HashSet<>(vectors.stream().map(ImageVectorPublication::basePublication).toList())
            .equals(new HashSet<>(publications))) {
      throw imageVectorRequired();
    }
    var bases =
        hydratedImages(
            scope, vectors.stream().map(ImageVectorPublication::basePhysicalSegmentId).toList());
    for (int index = 0; index < vectors.size(); index++) {
      var vector = vectors.get(index);
      var base = bases.get(index);
      var manifest =
          new RetrievalProjection.RevisionManifest(
              scope.actor().workspaceId(),
              vector.basePublication().documentId(),
              vector.vectorGenerationId(),
              Map.of(vector.vectorPhysicalSegmentId(), vector.entrySha256()));
      if (!vector.basePublication().equals(base.publication())
          || !vector.imageEvidenceId().equals(base.image().id())
          || !vector.target().equals(imageTarget)
          || !RetrievalProjection.physicalSegmentId(
                  vector.vectorGenerationId(), vector.imageEvidenceId())
              .equals(vector.vectorPhysicalSegmentId())
          || !manifest.sha256().equals(vector.manifestSha256())) {
        throw imageVectorRequired();
      }
    }
    return vectors;
  }

  private boolean imageVectorsCurrent(ImageVectorScope scope) {
    try {
      return new HashSet<>(verifiedImageVectors(scope.authority(), scope.imageTarget()))
          .equals(new HashSet<>(scope.publications()));
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private static ApplicationException imageVectorRequired() {
    return new ApplicationException(
        FailureKind.CONFLICT, "image_vector_required", "请为当前范围的全部图片建立原图向量。");
  }

  public List<PublishedImageEvidence> hydrateImages(
      EvidenceScope snapshot, List<String> physicalIds) {
    requireScope(snapshot);
    var ids = candidateIds(physicalIds);
    return store.transaction(
        () -> {
          if (!current(snapshot)) {
            throw changed();
          }
          return hydratedImages(snapshot, ids);
        });
  }

  public VisualSourceEvidence image(EvidenceScope snapshot, String physicalId) {
    requireScope(snapshot);
    ModelValues.identifier(physicalId, 128);
    return store.transaction(
        () -> {
          if (!current(snapshot)) {
            throw changed();
          }
          var image = hydratedImages(snapshot, List.of(physicalId)).getFirst();
          return imageSource(snapshot.actor(), image);
        });
  }

  public VisualSourceEvidence visualSource(Actor actor, String traceId, int citationOrdinal) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(traceId, 128);
    if (citationOrdinal < 1 || citationOrdinal > 32) {
      throw ModelValues.notFound();
    }
    return store.transaction(
        () -> {
          var scope = evidence.findTraceScope(actor, traceId);
          if (scope == null || !current(scope)) {
            throw ModelValues.notFound();
          }
          var saved = evidence.findTraceImageCitation(actor, traceId, citationOrdinal);
          if (saved == null) {
            throw ModelValues.notFound();
          }
          var item =
              hydratedImages(scope, List.of(saved.evidence().physicalSegmentId())).getFirst();
          if (!item.publication().publicationId().equals(saved.publicationId())
              || !item.image().id().equals(saved.imageEvidenceId())
              || !item.publication().sourceSha256().equals(saved.sourceSha256())) {
            throw ModelValues.notFound();
          }
          var original = imageSource(actor, item);
          return new VisualSourceEvidence(item, original.original(), saved.evidence());
        });
  }

  public TraceReceipt finish(
      EvidenceScope snapshot, TraceDraft draft, Supplier<AnswerEligibility> eligibility) {
    return finish(snapshot, draft, eligibility, null);
  }

  public TraceReceipt finish(
      EvidenceScope snapshot,
      TraceDraft draft,
      Supplier<AnswerEligibility> eligibility,
      ImageVectorScope imageVectorScope) {
    return finish(snapshot, draft, eligibility, imageVectorScope, null);
  }

  public TraceReceipt finish(
      EvidenceScope snapshot,
      TraceDraft draft,
      Supplier<AnswerEligibility> eligibility,
      ImageVectorScope imageVectorScope,
      AudioVectorScope audioVectorScope) {
    requireScope(snapshot);
    if (draft == null
        || eligibility == null
        || (audioVectorScope != null && !snapshot.equals(audioVectorScope.base()))
        || (imageVectorScope != null && !snapshot.equals(imageVectorScope.authority()))) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          // Check after waiting for the shared monitor, not merely before entering this
          // transaction.
          AnswerEligibility firstCheck = eligibility.get();
          if (firstCheck == null) {
            throw ModelValues.invalid();
          }
          var historical =
              evidence.findHistoricalPublications(
                  snapshot.actor().workspaceId(),
                  snapshot.publications().stream().map(PublicationVersion::publicationId).toList());
          if (!new HashSet<>(historical).equals(new HashSet<>(snapshot.publications()))) {
            throw ModelValues.invalid();
          }
          TraceDraft decision = eligibleDraft(draft, firstCheck);
          if (firstCheck == AnswerEligibility.ELIGIBLE && !current(snapshot)) {
            decision = refused(draft, "scope_changed");
          } else if (firstCheck == AnswerEligibility.ELIGIBLE
              && imageVectorScope != null
              && !imageVectorsCurrent(imageVectorScope)) {
            decision = refused(draft, "image_vector_required");
          } else if (firstCheck == AnswerEligibility.ELIGIBLE
              && audioVectorScope != null
              && !audioVectorsCurrent(audioVectorScope)) {
            decision = refused(draft, "audio_vector_required");
          }
          var citations = new ArrayList<TraceCitationEntity>();
          var imageCitations = new ArrayList<TraceImageCitationEntity>();
          var audioCitations = new ArrayList<TraceAudioCitationEntity>();
          var videoCitations = new ArrayList<TraceVideoCitationEntity>();
          var ocrCitations = new ArrayList<TraceVideoOcrCitationEntity>();
          var subtitleCitations = new ArrayList<TraceVideoSubtitleCitationEntity>();
          if ("answered".equals(decision.outcome())) {
            var ids =
                decision.evidence().stream()
                    .map(item -> item.physicalSegmentId())
                    .distinct()
                    .toList();
            var byId = new HashMap<String, PublishedEvidence>();
            for (var item : hydrated(snapshot, ids)) {
              byId.put(item.physicalSegmentId(), item);
            }
            for (var locator : decision.evidence()) {
              var source =
                  new SourceEvidence(
                      byId.get(locator.physicalSegmentId()), locator.start(), locator.end());
              var item = source.evidence();
              citations.add(
                  new TraceCitationEntity(
                      locator,
                      item.publication().publicationId(),
                      item.segment().segmentId(),
                      item.segment().page(),
                      item.segment().textSha256(),
                      item.pageSha256(),
                      quoteHash(source)));
            }
            var imageIds =
                decision.visualEvidence().stream()
                    .map(item -> item.physicalSegmentId())
                    .distinct()
                    .toList();
            var imagesById = new HashMap<String, PublishedImageEvidence>();
            for (var item : hydratedImages(snapshot, imageIds)) {
              imagesById.put(item.physicalSegmentId(), item);
            }
            for (var locator : decision.visualEvidence()) {
              var source =
                  imageSource(snapshot.actor(), imagesById.get(locator.physicalSegmentId()));
              var item = source.source();
              imageCitations.add(
                  new TraceImageCitationEntity(
                      locator,
                      item.publication().publicationId(),
                      item.image().id(),
                      source.original().sha256()));
            }
            var audioIds =
                decision.audioEvidence().stream()
                    .map(item -> item.physicalSegmentId())
                    .distinct()
                    .toList();
            var audioById = new HashMap<String, PublishedAudioEvidence>();
            for (var item : hydratedAudio(snapshot, audioIds)) {
              audioById.put(item.physicalSegmentId(), item);
            }
            for (var locator : decision.audioEvidence()) {
              var source =
                  new AudioSourceEvidence(
                      audioById.get(locator.physicalSegmentId()),
                      locator.startCodePoint(),
                      locator.endCodePoint());
              var item = source.evidence();
              audioCitations.add(
                  new TraceAudioCitationEntity(
                      locator,
                      item.publication().publicationId(),
                      item.span().id(),
                      source.startMs(),
                      source.endMs(),
                      item.publication().sourceSha256(),
                      item.span().textSha256(),
                      item.transcript().contextSha256(),
                      audioQuoteHash(source)));
            }
          }
          if ("answered".equals(decision.outcome()) && decision.videoProof() != null) {
            var proof = decision.videoProof();
            var material = videoMaterial(snapshot, proof.publicationId(), proof.groupId());
            for (var locator : decision.videoEvidence()) {
              videoCitations.add(videoCitation(new VideoSourceEvidence(material, locator)));
            }
          }
          if ("answered".equals(decision.outcome())) {
            for (var locator : decision.videoOcrEvidence()) {
              ocrCitations.add(ocrCitation(ocrMaterial(snapshot, locator)));
            }
            var subtitleIds =
                decision.videoSubtitleEvidence().stream()
                    .map(TraceEvidence::physicalSegmentId)
                    .distinct()
                    .toList();
            var subtitleById = new HashMap<String, PublishedVideoSubtitleEvidence>();
            for (var source : classifiedVideo(snapshot, subtitleIds).subtitles()) {
              subtitleById.put(source.physicalSegmentId(), source);
            }
            for (var locator : decision.videoSubtitleEvidence()) {
              subtitleCitations.add(
                  subtitleCitation(
                      new VideoSubtitleSourceEvidence(
                          subtitleById.get(locator.physicalSegmentId()), locator, null)));
            }
          }
          AnswerEligibility lastCheck = eligibility.get();
          if (lastCheck == null) {
            throw ModelValues.invalid();
          }
          if (firstCheck == AnswerEligibility.ELIGIBLE && lastCheck != AnswerEligibility.ELIGIBLE) {
            decision = eligibleDraft(draft, lastCheck);
            citations.clear();
            imageCitations.clear();
            audioCitations.clear();
            videoCitations.clear();
            ocrCitations.clear();
            subtitleCitations.clear();
          }
          if ("answered".equals(decision.outcome())
              && imageVectorScope != null
              && !imageVectorsCurrent(imageVectorScope)) {
            decision = refused(draft, "image_vector_required");
            citations.clear();
            imageCitations.clear();
            audioCitations.clear();
            videoCitations.clear();
            ocrCitations.clear();
            subtitleCitations.clear();
          }
          if ("answered".equals(decision.outcome())
              && audioVectorScope != null
              && !audioVectorsCurrent(audioVectorScope)) {
            decision = refused(draft, "audio_vector_required");
            citations.clear();
            imageCitations.clear();
            audioCitations.clear();
            videoCitations.clear();
            ocrCitations.clear();
            subtitleCitations.clear();
          }
          String traceId = UUID.randomUUID().toString();
          evidence.insertTrace(
              traceId,
              snapshot,
              decision,
              citations,
              imageCitations,
              audioCitations,
              videoCitations,
              ocrCitations,
              subtitleCitations,
              Instant.now().toString());
          return new TraceReceipt(traceId, decision.outcome(), decision.reasonCode());
        });
  }

  public SourceEvidence source(Actor actor, String traceId, int citationOrdinal) {
    return sourceWithTarget(actor, traceId, citationOrdinal, null);
  }

  public SourceEvidence source(
      Actor actor, String traceId, int citationOrdinal, IndexTarget requiredTarget) {
    if (requiredTarget == null) {
      throw ModelValues.invalid();
    }
    return sourceWithTarget(actor, traceId, citationOrdinal, requiredTarget);
  }

  private SourceEvidence sourceWithTarget(
      Actor actor, String traceId, int citationOrdinal, IndexTarget requiredTarget) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(traceId, 128);
    if (citationOrdinal < 1 || citationOrdinal > 32) {
      throw ModelValues.notFound();
    }
    return store.transaction(
        () -> {
          var scope = evidence.findTraceScope(actor, traceId);
          if (scope == null || !current(scope)) {
            throw ModelValues.notFound();
          }
          if (requiredTarget != null
              && scope.publications().stream().anyMatch(p -> !p.target().equals(requiredTarget))) {
            throw ModelValues.notFound();
          }
          var saved = evidence.findTraceCitation(actor, traceId, citationOrdinal);
          if (saved == null) {
            throw ModelValues.notFound();
          }
          var item = hydrated(scope, List.of(saved.evidence().physicalSegmentId())).getFirst();
          var source = new SourceEvidence(item, saved.evidence().start(), saved.evidence().end());
          if (!item.publication().publicationId().equals(saved.publicationId())
              || !item.segment().segmentId().equals(saved.sourceSegmentId())
              || item.segment().page() != saved.page()
              || !item.segment().textSha256().equals(saved.textSha256())
              || !item.pageSha256().equals(saved.pageSha256())
              || !quoteHash(source).equals(saved.quoteSha256())) {
            throw ModelValues.notFound();
          }
          return new SourceEvidence(
              item,
              source.start(),
              source.end(),
              sourceImage(actor, item, source.start(), source.end()));
        });
  }

  private SourceImage sourceImage(Actor actor, PublishedEvidence source, int start, int end) {
    var document =
        management
            .findAuthorizedDocument(actor, source.publication().documentId(), false)
            .orElseThrow(ModelValues::notFound);
    if (!"image".equals(document.documentType())) {
      return null;
    }
    byte[] original = evidence.findImageOriginal(actor, source.publication(), ImageInput.MAX_BYTES);
    if (original == null
        || !ModelValues.sha256(original).equals(source.publication().sourceSha256())) {
      throw ModelValues.notFound();
    }
    try {
      ImageInput.validateEnvelope(document.filename(), document.mimeType(), original);
      var dimensions = ImageInput.inspect(original);
      var regions = evidence.findImageRegions(source.publication(), start, end);
      if (source.publication().parserRevision().startsWith("java-image-ocr-v2-tsv:")
          && regions.isEmpty()) {
        throw ModelValues.notFound();
      }
      return new SourceImage(
          document.mimeType(), original, dimensions.width(), dimensions.height(), regions);
    } catch (TextParser.Failure failure) {
      throw ModelValues.notFound();
    }
  }

  private VisualSourceEvidence imageSource(Actor actor, PublishedImageEvidence source) {
    if (source == null) {
      throw ModelValues.notFound();
    }
    byte[] original = evidence.findImageOriginal(actor, source.publication(), ImageInput.MAX_BYTES);
    if (original == null
        || !ModelValues.sha256(original).equals(source.publication().sourceSha256())) {
      throw ModelValues.notFound();
    }
    try {
      ImageInput.validateEnvelope(source.filename(), source.mediaType(), original);
      var dimensions = ImageInput.inspect(original);
      if (dimensions.width() != source.image().width()
          || dimensions.height() != source.image().height()) {
        throw ModelValues.notFound();
      }
      return new VisualSourceEvidence(source, new VisualImage(source.mediaType(), original));
    } catch (TextParser.Failure failure) {
      throw ModelValues.notFound();
    }
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
    var items = evidence.findPublishedEvidence(scope, ids);
    if (items.size() != ids.size()) {
      throw ModelValues.invalid();
    }
    var byId = new HashMap<String, PublishedEvidence>();
    for (var item : items) {
      if (!scope.publications().contains(item.publication())
          || byId.put(item.physicalSegmentId(), item) != null) {
        throw ModelValues.invalid();
      }
    }
    return ids.stream().map(byId::get).toList();
  }

  private List<PublishedImageEvidence> hydratedImages(EvidenceScope scope, List<String> ids) {
    var items = evidence.findPublishedImageEvidence(scope, ids);
    if (items.size() != ids.size()) {
      throw ModelValues.invalid();
    }
    var byId = new HashMap<String, PublishedImageEvidence>();
    for (var item : items) {
      if (!scope.publications().contains(item.publication())
          || byId.put(item.physicalSegmentId(), item) != null) {
        throw ModelValues.invalid();
      }
    }
    return ids.stream().map(byId::get).toList();
  }

  private List<PublishedAudioEvidence> hydratedAudio(EvidenceScope scope, List<String> ids) {
    if (evidence.publishedAudioTranscriptBytes(scope, ids) > MAX_PAGE_BYTES) {
      throw new ApplicationException(
          FailureKind.CAPACITY_EXCEEDED, "evidence_capacity_exceeded", "引用音频转录超过处理上限，请缩小资料范围。");
    }
    var items = evidence.findPublishedAudioEvidence(scope, ids);
    if (items.size() != ids.size()) {
      throw ModelValues.invalid();
    }
    var byId = new HashMap<String, PublishedAudioEvidence>();
    for (var item : items) {
      if (!scope.publications().contains(item.publication())
          || byId.put(item.physicalSegmentId(), item) != null) {
        throw ModelValues.invalid();
      }
    }
    return ids.stream().map(byId::get).toList();
  }

  private static String audioQuoteHash(AudioSourceEvidence source) {
    return ModelValues.sha256(source.quote().getBytes(StandardCharsets.UTF_8));
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

  private static TraceDraft refused(TraceDraft draft, String reason) {
    return new TraceDraft(
            draft.questionSha256(),
            null,
            "abstained",
            reason,
            draft.modelRevision(),
            draft.promptRevision(),
            draft.policyRevision(),
            List.of())
        .withQueryTrace(draft.queryTrace());
  }

  private static TraceDraft eligibleDraft(TraceDraft draft, AnswerEligibility eligibility) {
    return switch (eligibility) {
      case ELIGIBLE -> draft;
      case PROCESSING_TIMEOUT -> refused(draft, "processing_timeout");
      case CONFIGURATION_CHANGED -> refused(draft, "configuration_changed");
    };
  }

  private static String quoteHash(SourceEvidence source) {
    String page = source.evidence().page().text();
    String quote =
        page.substring(
            page.offsetByCodePoints(0, source.start()), page.offsetByCodePoints(0, source.end()));
    return ModelValues.sha256(quote.getBytes(StandardCharsets.UTF_8));
  }

  private static ApplicationException changed() {
    return new ApplicationException(FailureKind.CONFLICT, "scope_changed", "资料授权或已发布证据发生变化，请重新查询。");
  }
}
