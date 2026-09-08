package com.evidence.rag.service;

import static com.evidence.rag.model.domain.ModelValues.identifier;
import static com.evidence.rag.model.domain.ModelValues.invalid;
import static com.evidence.rag.model.domain.ModelValues.notFound;
import static com.evidence.rag.model.domain.ModelValues.sha256;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.entity.TaskEntity;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Ingestion admission, fencing and evidence acceptance. Remote parsing never holds a transaction.
 */
public final class IngestionService {
  private static final long MAX_ORIGINAL_BYTES = 256L * 1024 * 1024;
  private static final int MAX_PENDING_INGESTIONS = 32;
  private static final Set<String> ERRORS =
      Set.of(
          "unsupported_document",
          "parser_failed",
          "parser_timeout",
          "parser_output_invalid",
          "worker_interrupted");
  private final SqliteAuthorityStore store;
  private final IngestionRepository ingestion;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;

  public IngestionService(
      SqliteAuthorityStore store,
      IngestionRepository ingestion,
      ManagementRepository management,
      DocumentPermissionPolicy permissions) {
    this.store = Objects.requireNonNull(store);
    this.ingestion = Objects.requireNonNull(ingestion);
    this.management = Objects.requireNonNull(management);
    this.permissions = Objects.requireNonNull(permissions);
  }

  public int maximumUploadBytes() {
    return TextParser.MAX_BYTES;
  }

  /** Admits file metadata before Web receives bytes; full content is revalidated on upload. */
  public String prepareUpload(String filename) {
    try {
      TextParser.validateMetadata(filename, "application/octet-stream");
      return canonicalMime(filename);
    } catch (TextParser.Failure failure) {
      throw new ApplicationException(
          FailureKind.INVALID_INPUT, "unsupported_document", "仅支持符合文件格式的 PDF、TXT 和 Markdown。");
    }
  }

  private static String canonicalMime(String filename) {
    String lower = filename.toLowerCase(Locale.ROOT);
    return lower.endsWith(".pdf")
        ? "application/pdf"
        : lower.endsWith(".md") ? "text/markdown" : "text/plain";
  }

  public void validateUploadEnvelope(String filename, String mime, byte[] content) {
    try {
      TextParser.validateEnvelope(filename, mime, content);
    } catch (TextParser.Failure failure) {
      throw new ApplicationException(
          FailureKind.INVALID_INPUT, "unsupported_document", "仅支持符合文件格式的 PDF、TXT 和 Markdown。");
    }
  }

  public TaskResult uploadDocument(Actor owner, String filename, String mime, byte[] content) {
    if (owner == null
        || content == null
        || content.length == 0
        || content.length > TextParser.MAX_BYTES) {
      throw invalid();
    }
    byte[] original = content.clone();
    validateUploadEnvelope(filename, mime, original);
    String canonicalMime = canonicalMime(filename);
    String sourceHash = sha256(original);
    return store.transaction(
        () -> {
          checkPendingQuota(owner.workspaceId());
          if (ingestion.storedBytes(owner.workspaceId()) > MAX_ORIGINAL_BYTES - original.length) {
            throw quotaExceeded();
          }
          String documentId = UUID.randomUUID().toString(),
              revisionId = UUID.randomUUID().toString();
          String jobId = UUID.randomUUID().toString(), now = Instant.now().toString();
          management.insertDocument(
              owner,
              new SyntheticDocument(
                  documentId,
                  filename,
                  "document",
                  canonicalMime,
                  revisionId,
                  sourceHash,
                  original.length),
              now);
          management.insertGrant(documentId, owner.principalId(), "owner");
          ingestion.insertOriginal(
              documentId, revisionId, TextParser.REVISION, sourceHash, original, now);
          ingestion.insertJob(jobId, documentId, revisionId, owner.principalId(), now);
          audit(
              owner,
              documentId,
              "ingestion_queued",
              null,
              values(
                  "revision_id",
                  revisionId,
                  "source_sha256",
                  sourceHash,
                  "size_bytes",
                  original.length,
                  "state",
                  "queued"),
              Set.of("revision_id", "source_sha256", "size_bytes", "state"));
          var task = authorizedTask(owner, jobId, false);
          return TaskResults.ingestion(
              task,
              permissions.canEdit(task.currentRole()),
              creatorCanWrite(task, owner.workspaceId()));
        });
  }

  public Optional<IngestionClaim> claimIngestion(String workspaceId) {
    Actor worker = new Actor(workspaceId, "system:ingestion");
    return store.transaction(
        () -> {
          for (int candidate = 0; candidate < MAX_PENDING_INGESTIONS; candidate++) {
            var jobId = ingestion.nextQueuedId(workspaceId).orElse(null);
            if (jobId == null) {
              return Optional.empty();
            }
            var task = ingestion.findInternalTask(jobId).orElseThrow();
            if (cancelIfUnauthorized(task)) {
              continue;
            }
            String token = UUID.randomUUID().toString() + UUID.randomUUID();
            ingestion.markProcessing(
                jobId, sha256(token.getBytes(StandardCharsets.UTF_8)), Instant.now().toString());
            audit(
                worker,
                task.documentId(),
                "ingestion_claimed",
                values("state", "queued"),
                values("state", "processing", "attempt", task.attempt()),
                Set.of("state", "attempt"));
            return Optional.of(
                new IngestionClaim(
                    jobId,
                    task.documentId(),
                    task.revisionId(),
                    workspaceId,
                    task.attempt(),
                    token,
                    task.filename(),
                    task.mimeType(),
                    task.parserRevision(),
                    ingestion.original(task.documentId())));
          }
          return Optional.empty();
        });
  }

  public boolean completeIngestion(IngestionClaim claim, ParsedText parsed) {
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task == null) {
            return false;
          }
          if (!TextParser.REVISION.equals(task.parserRevision())
              || !sha256(claim.content()).equals(task.sourceSha256())) {
            throw invalidParserOutput();
          }
          if (cancelIfUnauthorized(task)) {
            return false;
          }
          validateParsed(parsed);
          for (TextPage page : parsed.pages()) {
            ingestion.insertPage(
                claim.revisionId(), page, sha256(page.text().getBytes(StandardCharsets.UTF_8)));
          }
          for (TextSegment segment : parsed.segments()) {
            String textHash = sha256(segment.text().getBytes(StandardCharsets.UTF_8));
            String segmentId =
                sha256(
                    (claim.workspaceId()
                            + "\u0000"
                            + claim.documentId()
                            + "\u0000"
                            + claim.revisionId()
                            + "\u0000"
                            + segment.ordinal()
                            + "\u0000"
                            + textHash)
                        .getBytes(StandardCharsets.UTF_8));
            ingestion.insertSegment(segmentId, claim.revisionId(), segment, textHash);
          }
          ingestion.markParsed(
              claim.jobId(),
              claim.documentId(),
              claim.revisionId(),
              parsed.pages().size(),
              parsed.segments().size(),
              Instant.now().toString());
          audit(
              new Actor(claim.workspaceId(), "system:ingestion"),
              claim.documentId(),
              "ingestion_parsed",
              values("state", "processing"),
              values(
                  "state",
                  "parsed",
                  "revision_id",
                  claim.revisionId(),
                  "page_count",
                  parsed.pages().size(),
                  "segment_count",
                  parsed.segments().size()),
              Set.of("state", "revision_id", "page_count", "segment_count"));
          return true;
        });
  }

  public boolean failIngestion(IngestionClaim claim, String safeCode) {
    if (safeCode == null || !ERRORS.contains(safeCode)) {
      throw invalid();
    }
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task == null) {
            return false;
          }
          if (cancelIfUnauthorized(task)) {
            return false;
          }
          finishFailed(task, safeCode);
          return true;
        });
  }

  public boolean isIngestionClaimCurrent(IngestionClaim claim) {
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          return task != null && !cancelIfUnauthorized(task);
        });
  }

  public TaskResult ingestionStatus(Actor actor, String jobId) {
    return store.transaction(
        () -> {
          var task = authorizedTask(actor, jobId, false);
          return TaskResults.ingestion(
              task,
              permissions.canEdit(task.currentRole()),
              creatorCanWrite(task, actor.workspaceId()));
        });
  }

  public TaskResult retryIngestion(Actor actor, String jobId) {
    return store.transaction(
        () -> {
          var task = authorizedTask(actor, jobId, true);
          if (!Set.of("failed", "cancelled").contains(task.state())) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "ingestion_state_conflict", "当前任务状态不能重试。");
          }
          if (task.attempt() >= 3) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "ingestion_retry_limit", "该任务已达到三次尝试上限。");
          }
          if (!creatorCanWrite(task, actor.workspaceId())) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "authorization_changed", "任务创建者已无当前写权限。");
          }
          checkPendingQuota(actor.workspaceId());
          ingestion.markQueued(jobId, task.attempt() + 1, Instant.now().toString());
          audit(
              actor,
              task.documentId(),
              "ingestion_retried",
              values("state", task.state(), "attempt", task.attempt()),
              values("state", "queued", "attempt", task.attempt() + 1),
              Set.of("state", "attempt"));
          var updated = authorizedTask(actor, jobId, false);
          return TaskResults.ingestion(
              updated,
              permissions.canEdit(updated.currentRole()),
              creatorCanWrite(updated, actor.workspaceId()));
        });
  }

  public TaskResult cancelIngestion(Actor actor, String jobId) {
    return store.transaction(
        () -> {
          var task = authorizedTask(actor, jobId, true);
          if (!Set.of("queued", "processing").contains(task.state())) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "ingestion_state_conflict", "当前任务状态不能取消。");
          }
          ingestion.markCancelled(jobId, Instant.now().toString());
          audit(
              actor,
              task.documentId(),
              "ingestion_cancelled",
              values("state", task.state()),
              values("state", "cancelled"),
              Set.of("state"));
          var updated = authorizedTask(actor, jobId, false);
          return TaskResults.ingestion(
              updated,
              permissions.canEdit(updated.currentRole()),
              creatorCanWrite(updated, actor.workspaceId()));
        });
  }

  public ParsedText parsedEvidence(Actor actor, String documentId) {
    return store.transaction(
        () -> {
          String revisionId =
              ingestion.authorizedParsedRevision(actor, documentId).orElseThrow(() -> notFound());
          permissions.require(management.currentRole(actor, documentId), false);
          return ingestion.parsedEvidence(revisionId);
        });
  }

  /** Called by startup composition before any ingestion worker is scheduled. */
  public void recoverIngestions() {
    store.transaction(
        () -> {
          for (String jobId : ingestion.processingIds()) {
            var task = ingestion.findInternalTask(jobId).orElseThrow();
            if (!cancelIfUnauthorized(task)) {
              finishFailed(task, "worker_interrupted");
            }
          }
          return null;
        });
  }

  private TaskEntity authorizedTask(Actor actor, String jobId, boolean edit) {
    if (actor == null) {
      throw invalid();
    }
    identifier(jobId, 100);
    var task = ingestion.findAuthorizedTask(actor, jobId, edit).orElseThrow(() -> notFound());
    permissions.require(task.currentRole(), edit);
    return task;
  }

  private TaskEntity currentClaim(IngestionClaim claim) {
    if (claim == null || claim.token() == null || claim.token().length() != 72) {
      return null;
    }
    var task = ingestion.findInternalTask(claim.jobId()).orElse(null);
    if (task == null
        || !"processing".equals(task.state())
        || !Objects.equals(claim.workspaceId(), task.workspaceId())
        || !Objects.equals(claim.documentId(), task.documentId())
        || !Objects.equals(claim.revisionId(), task.revisionId())
        || !Objects.equals(claim.filename(), task.filename())
        || !Objects.equals(claim.mimeType(), task.mimeType())
        || !Objects.equals(claim.parserRevision(), task.parserRevision())
        || claim.attempt() != task.attempt()
        || !MessageDigest.isEqual(
            sha256(claim.token().getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.US_ASCII),
            task.claimTokenSha256().getBytes(StandardCharsets.US_ASCII))) {
      return null;
    }
    return task;
  }

  private boolean creatorCanWrite(TaskEntity task, String workspaceId) {
    return permissions.canEdit(
        management.currentRole(new Actor(workspaceId, task.createdBy()), task.documentId()));
  }

  /** Only called with a current queued/internal task or a fully matched processing claim. */
  private boolean cancelIfUnauthorized(TaskEntity task) {
    if (creatorCanWrite(task, task.workspaceId())) {
      return false;
    }
    ingestion.markCancelled(task.id(), Instant.now().toString());
    audit(
        new Actor(task.workspaceId(), "system:ingestion"),
        task.documentId(),
        "ingestion_authorization_cancelled",
        values("state", task.state()),
        values("state", "cancelled", "reason", "authorization_changed"),
        Set.of("state", "reason"));
    return true;
  }

  private void checkPendingQuota(String workspaceId) {
    if (ingestion.pendingCount(workspaceId) >= MAX_PENDING_INGESTIONS) {
      throw quotaExceeded();
    }
  }

  private static ApplicationException quotaExceeded() {
    return new ApplicationException(
        FailureKind.CONFLICT, "ingestion_quota_exceeded", "当前组织的待处理任务或原文件存储已达到开发配额。");
  }

  private static ApplicationException invalidParserOutput() {
    return new ApplicationException(
        FailureKind.INVALID_INPUT, "parser_output_invalid", "解析结果未通过证据完整性校验。");
  }

  private static void validateParsed(ParsedText parsed) {
    if (parsed == null
        || parsed.pages().isEmpty()
        || parsed.pages().size() > 500
        || parsed.segments().isEmpty()
        || parsed.segments().size() > 4096) {
      throw invalidParserOutput();
    }
    var pagePoints = new ArrayList<int[]>();
    long total = 0;
    for (var page : parsed.pages()) {
      if (page.number() != pagePoints.size() + 1 || page.text() == null) {
        throw invalidParserOutput();
      }
      int[] points = page.text().codePoints().toArray();
      total += points.length;
      if (total > 1_000_000) {
        throw invalidParserOutput();
      }
      for (int point : points) {
        if ((Character.isISOControl(point) && point != '\n' && point != '\t' && point != '\f')
            || (point >= 0xD800 && point <= 0xDFFF)) {
          throw invalidParserOutput();
        }
      }
      pagePoints.add(points);
    }
    int ordinal = 0, previousPage = 0, previousStart = -1;
    long segmentPoints = 0;
    for (var segment : parsed.segments()) {
      if (segment.ordinal() != ordinal++
          || segment.page() < 1
          || segment.page() > pagePoints.size()
          || segment.start() < 0
          || segment.end() <= segment.start()
          || segment.end() - segment.start() > 1200
          || segment.text() == null
          || segment
              .text()
              .codePoints()
              .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
          || segment.page() < previousPage
          || (segment.page() == previousPage && segment.start() <= previousStart)) {
        throw invalidParserOutput();
      }
      int[] points = pagePoints.get(segment.page() - 1);
      if (segment.end() > points.length
          || !segment
              .text()
              .equals(new String(points, segment.start(), segment.end() - segment.start()))) {
        throw invalidParserOutput();
      }
      segmentPoints += segment.end() - segment.start();
      if (segmentPoints > 1_500_000) {
        throw invalidParserOutput();
      }
      previousPage = segment.page();
      previousStart = segment.start();
    }
  }

  private void finishFailed(TaskEntity task, String safeCode) {
    ingestion.markFailed(task.id(), safeCode, Instant.now().toString());
    audit(
        new Actor(task.workspaceId(), "system:ingestion"),
        task.documentId(),
        "ingestion_failed",
        values("state", "processing"),
        values("state", "failed", "error_code", safeCode),
        Set.of("state", "error_code"));
  }

  private void audit(
      Actor actor, String id, String action, Object before, Object after, Set<String> fields) {
    management.insertAudit(AuditEventEntity.create(actor, id, action, before, after, fields));
  }

  private static Map<String, Object> values(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int index = 0; index < pairs.length; index += 2) {
      result.put((String) pairs[index], pairs[index + 1]);
    }
    return result;
  }
}
