package com.evidence.rag.support;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.service.ManagementService;
import com.evidence.rag.web.converter.ManagementRequestMapper;
import com.evidence.rag.web.converter.ManagementResponseMapper;
import com.evidence.rag.web.converter.TaskResponseMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only composition plus real HTTP contract conversion. It keeps historical assertions at the
 * same observable interface without retaining a production compatibility facade or a second store.
 */
public final class AuthorityTestContext implements AutoCloseable {
  private final JsonMapper json = JsonMapper.builder().build();
  private final SqliteAuthorityStore store;
  private final ManagementService management;
  private final IngestionService ingestion;
  private final IndexingService indexing;

  public AuthorityTestContext(Path directory) {
    store = new SqliteAuthorityStore(directory);
    try {
      var managementRepository = new ManagementRepository(store);
      var ingestionRepository = new IngestionRepository(store);
      var indexingRepository = new IndexingRepository(store);
      var permissions = new DocumentPermissionPolicy();
      management =
          new ManagementService(
              store, managementRepository, ingestionRepository, indexingRepository, permissions);
      ingestion =
          new IngestionService(store, ingestionRepository, managementRepository, permissions);
      indexing = new IndexingService(store, indexingRepository, managementRepository, permissions);
      ingestion.recoverIngestions();
      indexing.recoverIndexings();
    } catch (RuntimeException failure) {
      store.close();
      throw failure;
    }
  }

  public SqliteAuthorityStore store() {
    return store;
  }

  public ManagementService management() {
    return management;
  }

  public IngestionService ingestion() {
    return ingestion;
  }

  public IndexingService indexing() {
    return indexing;
  }

  public void registerSyntheticDocument(
      Actor owner, SyntheticDocument document, Map<String, String> grants) {
    management.registerSyntheticDocument(owner, document, grants);
  }

  public Map<String, Object> listDocuments(Actor actor, Map<String, String> query) {
    return response(
        ManagementResponseMapper.page(
            management.listDocuments(actor, ManagementRequestMapper.query(query))));
  }

  public Map<String, Object> updateDocument(Actor actor, String id, Map<String, Object> patch) {
    return response(
        ManagementResponseMapper.document(
            management.updateDocument(actor, id, ManagementRequestMapper.patch(patch))));
  }

  public Map<String, Object> listFolders(Actor actor) {
    return response(ManagementResponseMapper.folders(management.listFolders(actor)));
  }

  public Map<String, Object> createFolder(Actor actor, Map<String, Object> body) {
    return response(management.createFolder(actor, ManagementRequestMapper.folderName(body)));
  }

  public Map<String, Object> renameFolder(Actor actor, String id, Map<String, Object> body) {
    return response(management.renameFolder(actor, id, ManagementRequestMapper.folderName(body)));
  }

  public Map<String, Object> removeFolder(Actor actor, String id) {
    return response(management.removeFolder(actor, id));
  }

  public Map<String, Object> listTags(Actor actor) {
    return response(ManagementResponseMapper.tags(management.listTags(actor)));
  }

  public Map<String, Object> documentActions(Actor actor, Map<String, Object> body) {
    return response(
        ManagementResponseMapper.actions(
            management.documentActions(actor, ManagementRequestMapper.action(body))));
  }

  public Map<String, Object> uploadDocument(
      Actor actor, String filename, String mime, byte[] content) {
    return response(
        TaskResponseMapper.from(ingestion.uploadDocument(actor, filename, mime, content)));
  }

  public Optional<IngestionClaim> claimIngestion(String workspaceId) {
    return ingestion.claimIngestion(workspaceId);
  }

  public boolean completeIngestion(IngestionClaim claim, ParsedText parsed) {
    return ingestion.completeIngestion(claim, parsed);
  }

  public boolean failIngestion(IngestionClaim claim, String code) {
    return ingestion.failIngestion(claim, code);
  }

  public boolean isIngestionClaimCurrent(IngestionClaim claim) {
    return ingestion.isIngestionClaimCurrent(claim);
  }

  public Map<String, Object> ingestionStatus(Actor actor, String id) {
    return response(TaskResponseMapper.from(ingestion.ingestionStatus(actor, id)));
  }

  public Map<String, Object> retryIngestion(Actor actor, String id) {
    return response(TaskResponseMapper.from(ingestion.retryIngestion(actor, id)));
  }

  public Map<String, Object> cancelIngestion(Actor actor, String id) {
    return response(TaskResponseMapper.from(ingestion.cancelIngestion(actor, id)));
  }

  public ParsedText parsedEvidence(Actor actor, String id) {
    return ingestion.parsedEvidence(actor, id);
  }

  public Map<String, Object> createIndexing(Actor actor, String id, IndexTarget target) {
    return response(TaskResponseMapper.from(indexing.createIndexing(actor, id, target)));
  }

  public Optional<IndexClaim> claimIndexing(String workspaceId) {
    return indexing.claimIndexing(workspaceId);
  }

  public boolean completeIndexing(
      IndexClaim claim, Map<String, String> digests, VerifiedRevision verified) {
    return indexing.completeIndexing(claim, digests, verified);
  }

  public boolean isIndexingClaimCurrent(IndexClaim claim) {
    return indexing.isIndexingClaimCurrent(claim);
  }

  public boolean failIndexing(IndexClaim claim, String code) {
    return indexing.failIndexing(claim, code);
  }

  public Map<String, Object> indexingStatus(Actor actor, String id) {
    return response(TaskResponseMapper.from(indexing.indexingStatus(actor, id)));
  }

  public Map<String, Object> cancelIndexing(Actor actor, String id) {
    return response(TaskResponseMapper.from(indexing.cancelIndexing(actor, id)));
  }

  public Map<String, Object> retryIndexing(Actor actor, String id, IndexTarget target) {
    return response(TaskResponseMapper.from(indexing.retryIndexing(actor, id, target)));
  }

  public Map<String, Object> auditEvents(Actor actor) {
    var events =
        management.auditEvents(actor).stream()
            .map(
                event -> {
                  var row = new LinkedHashMap<String, Object>();
                  row.put("id", event.id());
                  row.put("workspace_id", event.workspaceId());
                  row.put("actor_id", event.actorId());
                  row.put("entity_id", event.entityId());
                  row.put("action", event.action());
                  row.put("fields_json", event.fieldsJson());
                  row.put("before_sha256", event.beforeSha256());
                  row.put("after_sha256", event.afterSha256());
                  row.put("created_at", event.createdAt());
                  return row;
                })
            .toList();
    return Map.of("items", events);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> response(Object value) {
    return json.convertValue(value, Map.class);
  }

  @Override
  public void close() {
    store.close();
  }
}
