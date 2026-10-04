package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.KnowledgeCitationLocator;
import com.evidence.rag.model.domain.KnowledgeEvidence;
import com.evidence.rag.model.domain.KnowledgeTraceDraft;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProductHelpEvidence;

/** Append-only hash-only mixed answer trace. Callers own the complete authorization transaction. */
public final class KnowledgeAnswerRepository {
  private final SqliteAuthorityStore store;

  public KnowledgeAnswerRepository(SqliteAuthorityStore store) {
    this.store = store;
  }

  public void insert(String id, EvidenceScope scope, KnowledgeTraceDraft draft, String now) {
    for (int i = 0; i < scope.publications().size(); i++) {
      store.execute("INSERT INTO knowledge_answer_documents(trace_id,ordinal,publication_id) VALUES(?,?,?)",
          id, i, scope.publications().get(i).publicationId());
    }
    for (var reference : draft.references()) {
      var value = KnowledgeCitationLocator.from(reference);
      store.execute("INSERT INTO knowledge_answer_citations(trace_id,ordinal,publication_id,kind,physical_id,start_offset,end_offset,context_sha256,quote_sha256,page_number,start_us,end_us) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
          id, value.citationId(), value.publicationId(), value.key().kind().name(), value.key().physicalId(),
          value.start(), value.end(), value.contextSha256(), value.quoteSha256(), value.page(), value.startUs(), value.endUs());
    }
    store.execute("INSERT INTO knowledge_answer_traces(id,workspace_id,actor_id,selection_all,scope_count,citation_count,question_sha256,answer_sha256,outcome,reason_code,model_revision,prompt_revision,policy_revision,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id, scope.actor().workspaceId(), scope.actor().principalId(), scope.selection().all() ? 1 : 0,
        scope.publications().size(), draft.references().size(), draft.questionSha256(), draft.answerSha256(),
        draft.status(), draft.reason(), draft.modelRevision(), draft.promptRevision(), draft.policyRevision(), now);
  }

  public EvidenceScope scope(Actor actor, String id) {
    var rows = store.rows("SELECT selection_all,scope_count FROM knowledge_answer_traces WHERE id=? AND workspace_id=? AND actor_id=? AND outcome='answered'",
        id, actor.workspaceId(), actor.principalId());
    if (rows.size() != 1) {
      throw ModelValues.notFound();
    }
    var ids = store.rows("SELECT publication_id FROM knowledge_answer_documents WHERE trace_id=? ORDER BY ordinal", id)
        .stream().map(row -> AuthorityRows.text(row, "publication_id")).toList();
    var publications = new EvidenceRepository(store).findHistoricalPublications(actor.workspaceId(), ids);
    if (ids.size() != AuthorityRows.integer(rows.getFirst(), "scope_count") || publications.size() != ids.size()) {
      throw ModelValues.notFound();
    }
    return new EvidenceScope(actor,
        AuthorityRows.integer(rows.getFirst(), "selection_all") == 1
            ? DocumentSelection.allDocuments()
            : DocumentSelection.selected(publications.stream().map(value -> value.documentId()).toList()),
        publications);
  }

  public KnowledgeCitationLocator citation(String id, int ordinal) {
    var rows = store.rows("SELECT * FROM knowledge_answer_citations WHERE trace_id=? AND ordinal=?", id, ordinal);
    if (rows.size() != 1) {
      throw ModelValues.notFound();
    }
    var row = rows.getFirst();
    return new KnowledgeCitationLocator(ordinal, AuthorityRows.text(row, "publication_id"),
        new KnowledgeEvidence.Key(ProductHelpEvidence.Kind.valueOf(AuthorityRows.text(row, "kind")),
            AuthorityRows.text(row, "physical_id")),
        AuthorityRows.integer(row, "start_offset"), AuthorityRows.integer(row, "end_offset"),
        AuthorityRows.text(row, "context_sha256"), AuthorityRows.text(row, "quote_sha256"),
        row.get("page_number") == null ? null : AuthorityRows.integer(row, "page_number"),
        row.get("start_us") == null ? null : AuthorityRows.number(row, "start_us"),
        row.get("end_us") == null ? null : AuthorityRows.number(row, "end_us"));
  }
}
