package com.evidence.rag.service;

import static com.evidence.rag.model.domain.ModelValues.identifier;
import static com.evidence.rag.model.domain.ModelValues.invalid;
import static com.evidence.rag.model.domain.ModelValues.notFound;
import static com.evidence.rag.model.domain.ModelValues.sha256;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.SynopsisClaim;
import com.evidence.rag.model.domain.SynopsisFileInput;
import com.evidence.rag.model.domain.SynopsisSourceMaterial;
import com.evidence.rag.model.dto.SynopsisDocumentResult;
import com.evidence.rag.model.dto.SynopsisTaskResult;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.entity.SynopsisTaskEntity;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.SynopsisRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.ImageInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Authority and persistence boundary for derived file summaries, not answer evidence. */
public final class SynopsisLibraryService {
  private static final int MAX_PENDING = 32;
  private static final Set<String> ERRORS =
      Set.of(
          "synopsis_failed",
          "worker_interrupted",
          "authorization_changed",
          "input_capacity_exceeded",
          "model_failure",
          "model_refused",
          "unsupported_claims",
          "incomplete_evidence",
          "configuration_changed",
          "source_changed",
          "processing_interrupted",
          "processing_timeout");
  private final SqliteAuthorityStore store;
  private final SynopsisRepository repository;
  private final SynopsisMaterialRepository materials;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;
  private final Supplier<String> modelRevision;
  private final Supplier<String> hierarchyRevision;

  public SynopsisLibraryService(
      SqliteAuthorityStore store,
      SynopsisRepository repository,
      SynopsisMaterialRepository materials,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      Supplier<String> modelRevision,
      Supplier<String> hierarchyRevision) {
    this.store = Objects.requireNonNull(store);
    this.repository = Objects.requireNonNull(repository);
    this.materials = Objects.requireNonNull(materials);
    this.management = Objects.requireNonNull(management);
    this.permissions = Objects.requireNonNull(permissions);
    this.modelRevision = Objects.requireNonNull(modelRevision);
    this.hierarchyRevision = Objects.requireNonNull(hierarchyRevision);
  }

  public SynopsisLibraryService(
      SqliteAuthorityStore store,
      SynopsisRepository repository,
      SynopsisMaterialRepository materials,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      Supplier<String> modelRevision) {
    this(store, repository, materials, management, permissions, modelRevision, () -> null);
  }

  public SynopsisTaskResult create(Actor actor, String documentId) {
    if (actor == null) {
      throw invalid();
    }
    identifier(documentId, 100);
    return store.transaction(
        () -> {
          permissions.require(management.currentRole(actor, documentId), true);
          var publication =
              materials
                  .publication(actor, documentId)
                  .orElseThrow(
                      () ->
                          new ApplicationException(
                              FailureKind.CONFLICT, "synopsis_requires_indexed", "请先完成资料索引。"));
          var input = materials.document(actor, publication);
          String policy = policy(input);
          String model = revision(policy);
          if (model == null) {
            throw new ApplicationException(
                FailureKind.UNAVAILABLE, "configuration_changed", "摘要分层模型尚未配置。");
          }
          var reusable =
              repository.findReusable(documentId, publication.publicationId(), model, policy);
          if (reusable.isPresent()) {
            return result(reusable.get());
          }
          if (repository.pendingCount(actor.workspaceId()) >= MAX_PENDING) {
            throw new ApplicationException(
                FailureKind.CAPACITY_EXCEEDED, "synopsis_queue_full", "摘要队列已满，请稍后再试。");
          }
          String id = UUID.randomUUID().toString(), now = Instant.now().toString();
          repository.insertTask(id, actor, publication, model, policy, now);
          audit(actor, documentId, "synopsis_queued", id, "queued");
          return result(repository.findTask(id).orElseThrow());
        });
  }

  public SynopsisTaskResult task(Actor actor, String id) {
    return store.transaction(() -> result(authorizedTask(actor, id)));
  }

  public SynopsisDocumentResult get(Actor actor, String documentId) {
    identifier(documentId, 100);
    if (actor == null) {
      throw invalid();
    }
    return store.transaction(
        () -> {
          var publication =
              materials
                  .publication(actor, documentId)
                  .orElseThrow(com.evidence.rag.model.domain.ModelValues::notFound);
          var input = materials.document(actor, publication);
          String policy = policy(input);
          String model = revision(policy);
          if (model == null) {
            throw notFound();
          }
          var task =
              repository
                  .findReusable(documentId, publication.publicationId(), model, policy)
                  .orElseThrow(com.evidence.rag.model.domain.ModelValues::notFound);
          var synopsis = available(task);
          if (!input.fingerprint().equals(synopsis.inputFingerprint())) {
            throw notFound();
          }
          return new SynopsisDocumentResult(task.id(), synopsis);
        });
  }

  public SynopsisSourceMaterial source(
      Actor actor, String synopsisId, int entryOrdinal, int sourceOrdinal) {
    if (entryOrdinal < 0 || sourceOrdinal < 0) {
      throw notFound();
    }
    return store.transaction(
        () -> {
          var task = authorizedTask(actor, synopsisId);
          var synopsis = available(task);
          if (entryOrdinal >= synopsis.entries().size()
              || sourceOrdinal >= synopsis.entries().get(entryOrdinal).evidence().size()) {
            throw notFound();
          }
          var reference = synopsis.entries().get(entryOrdinal).evidence().get(sourceOrdinal);
          var material = materials.source(actor, task.publication(), reference);
          if (material.evidence().kind()
              == com.evidence.rag.model.domain.SynopsisEvidence.Kind.IMAGE_OCR) {
            var page = (SynopsisSourceMaterial.Page) material.locator();
            try {
              return new SynopsisSourceMaterial(
                  material.evidence(),
                  material.filename(),
                  material.mediaType(),
                  new SynopsisSourceMaterial.Page(
                      page.page(),
                      page.start(),
                      page.end(),
                      ImageInput.inspect(material.content()),
                      page.regions()),
                  material.content(),
                  null);
            } catch (RuntimeException invalidImage) {
              throw notFound();
            }
          }
          return material;
        });
  }

  public Optional<SynopsisClaim> claim(String workspace) {
    identifier(workspace, 200);
    return store.transaction(
        () -> {
          if (repository.hasProcessing(workspace)) {
            return Optional.empty();
          }
          for (String id : repository.queuedIds(workspace)) {
            var task = repository.findTask(id).orElseThrow();
            String changed = changed(task);
            if (changed != null) {
              terminate(task, changed);
              continue;
            }
            SynopsisFileInput input;
            try {
              input = materials.document(task.creator(), task.publication());
            } catch (ApplicationException failure) {
              terminate(
                  task,
                  "input_capacity_exceeded".equals(failure.code())
                      ? "input_capacity_exceeded"
                      : "source_changed");
              continue;
            }
            if (!policy(input).equals(task.policyRevision())) {
              terminate(task, "configuration_changed");
              continue;
            }
            String token = UUID.randomUUID().toString() + UUID.randomUUID();
            if (!repository.markProcessing(id, tokenHash(token), input, Instant.now().toString())) {
              continue;
            }
            audit(
                task.creator(),
                task.publication().documentId(),
                "synopsis_claimed",
                id,
                "processing");
            return Optional.of(
                new SynopsisClaim(
                    id, task.creator(), input, task.modelRevision(), task.policyRevision(), token));
          }
          return Optional.empty();
        });
  }

  public boolean current(SynopsisClaim claim) {
    return store.transaction(
        () -> {
          var task = claimed(claim);
          return task != null && changed(task) == null;
        });
  }

  public boolean complete(SynopsisClaim claim, FileSynopsis synopsis) {
    return store.transaction(
        () -> {
          var task = claimed(claim);
          if (task == null) {
            return false;
          }
          String changed = changed(task);
          if (changed != null) {
            terminate(task, changed);
            return false;
          }
          if (synopsis == null
              || !task.publication().equals(synopsis.publication())
              || !task.inputFingerprint().equals(synopsis.inputFingerprint())
              || !task.modelRevision().equals(synopsis.modelRevision())
              || !task.policyRevision().equals(synopsis.policyRevision())) {
            terminate(task, "incomplete_evidence");
            return false;
          }
          if (synopsis.unavailableReason() != null) {
            return terminate(task, synopsis.unavailableReason());
          }
          try {
            var currentInput = materials.document(task.creator(), task.publication());
            if (!currentInput.fingerprint().equals(synopsis.inputFingerprint())) {
              terminate(task, "source_changed");
              return false;
            }
            var evidence = new java.util.HashMap<String, FileSynopsis.Reference>();
            for (var source : currentInput.evidence()) {
              evidence.put(
                  source.id(),
                  new FileSynopsis.Reference(
                      source.id(), source.sha256(), source.kind(), source.time()));
            }
            boolean timed =
                currentInput.evidence().stream().anyMatch(source -> source.time() != null);
            boolean timeline =
                synopsis.entries().stream()
                    .anyMatch(
                        entry ->
                            entry.item().section()
                                == com.evidence.rag.model.domain.SynopsisDraft.Section.TIMELINE);
            if (timed != timeline
                || synopsis.entries().stream()
                    .flatMap(entry -> entry.evidence().stream())
                    .anyMatch(reference -> !reference.equals(evidence.get(reference.id())))) {
              terminate(task, "incomplete_evidence");
              return false;
            }
          } catch (ApplicationException sourceChanged) {
            terminate(task, "source_changed");
            return false;
          }
          if (!repository.complete(
              task.id(), tokenHash(claim.token()), synopsis, Instant.now().toString())) {
            return false;
          }
          audit(
              task.creator(),
              task.publication().documentId(),
              "synopsis_available",
              task.id(),
              "available");
          return true;
        });
  }

  public boolean fail(SynopsisClaim claim, String safeCode) {
    if (!ERRORS.contains(safeCode == null ? "" : safeCode)) {
      throw invalid();
    }
    return store.transaction(
        () -> {
          var task = claimed(claim);
          if (task == null) {
            return false;
          }
          String changed = changed(task);
          return terminate(task, changed == null ? safeCode : changed);
        });
  }

  public int recover(String workspace) {
    return recoverProcessing(store, repository, workspace);
  }

  /** Startup use case has no model dependency and keeps its transaction in the Service layer. */
  public static int recoverProcessing(
      SqliteAuthorityStore store, SynopsisRepository repository, String workspace) {
    identifier(workspace, 200);
    return store.transaction(
        () -> repository.recoverProcessing(workspace, Instant.now().toString()));
  }

  private static String policy(SynopsisFileInput input) {
    return input.bounded()
        ? SynopsisService.POLICY_REVISION
        : HierarchicalSynopsisService.POLICY_REVISION;
  }

  private String revision(String policy) {
    if (SynopsisService.POLICY_REVISION.equals(policy)) {
      return identifier(modelRevision.get(), 200);
    }
    if (HierarchicalSynopsisService.POLICY_REVISION.equals(policy)) {
      String revision = hierarchyRevision.get();
      return revision == null ? null : identifier(revision, 200);
    }
    return null;
  }

  private SynopsisTaskEntity authorizedTask(Actor actor, String id) {
    if (actor == null) {
      throw invalid();
    }
    identifier(id, 128);
    var task =
        repository.findTask(id).orElseThrow(com.evidence.rag.model.domain.ModelValues::notFound);
    permissions.require(management.currentRole(actor, task.publication().documentId()), false);
    if (!actor.workspaceId().equals(task.creator().workspaceId())
        || !materials
            .publication(actor, task.publication().documentId())
            .filter(task.publication()::equals)
            .isPresent()
        || !task.modelRevision().equals(revision(task.policyRevision()))) {
      throw notFound();
    }
    return task;
  }

  private FileSynopsis available(SynopsisTaskEntity task) {
    if (!"available".equals(task.state())) {
      throw notFound();
    }
    return repository
        .findSynopsis(task.id())
        .orElseThrow(com.evidence.rag.model.domain.ModelValues::notFound);
  }

  private SynopsisTaskEntity claimed(SynopsisClaim claim) {
    if (claim == null) {
      return null;
    }
    var task = repository.findTask(claim.taskId()).orElse(null);
    if (task == null
        || !"processing".equals(task.state())
        || task.claimHash() == null
        || !MessageDigest.isEqual(
            task.claimHash().getBytes(StandardCharsets.US_ASCII),
            tokenHash(claim.token()).getBytes(StandardCharsets.US_ASCII))
        || !task.creator().equals(claim.creator())
        || !task.publication().equals(claim.publication())
        || !task.modelRevision().equals(claim.modelRevision())
        || !task.policyRevision().equals(claim.policyRevision())
        || !Objects.equals(task.inputFingerprint(), claim.input().fingerprint())) {
      return null;
    }
    return task;
  }

  private String changed(SynopsisTaskEntity task) {
    if (!permissions.canEdit(
        management.currentRole(task.creator(), task.publication().documentId()))) {
      return "authorization_changed";
    }
    if (!task.modelRevision().equals(revision(task.policyRevision()))) {
      return "configuration_changed";
    }
    return materials
            .publication(task.creator(), task.publication().documentId())
            .filter(task.publication()::equals)
            .isPresent()
        ? null
        : "source_changed";
  }

  private boolean terminate(SynopsisTaskEntity task, String reason) {
    boolean finished =
        repository.terminate(task.id(), "unavailable", reason, Instant.now().toString());
    if (finished) {
      audit(
          task.creator(),
          task.publication().documentId(),
          "synopsis_unavailable",
          task.id(),
          "unavailable");
    }
    return finished;
  }

  private void audit(Actor actor, String documentId, String action, String id, String state) {
    management.insertAudit(
        AuditEventEntity.create(
            actor,
            documentId,
            action,
            null,
            Map.of("synopsis_id", id, "state", state),
            Set.of("synopsis_id", "state")));
  }

  private static String tokenHash(String token) {
    return sha256(token.getBytes(StandardCharsets.UTF_8));
  }

  private static SynopsisTaskResult result(SynopsisTaskEntity task) {
    return new SynopsisTaskResult(
        task.id(),
        task.publication().documentId(),
        task.publication().publicationId(),
        task.state(),
        task.errorCode(),
        task.createdAt(),
        task.updatedAt());
  }
}
