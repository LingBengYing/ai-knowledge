package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** v29 stores mixed text answer locators without weakening the old audiovisual proof tables. */
final class KnowledgeAnswerSchema {
  private final SqliteAuthorityStore store;

  KnowledgeAnswerSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new AuthoritySchema(store).verifyVersionTwentyEight();
          store.execute(
              """
          CREATE TABLE knowledge_answer_traces(
            id TEXT PRIMARY KEY NOT NULL,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
            selection_all INTEGER NOT NULL CHECK(selection_all IN (0,1)),
            scope_count INTEGER NOT NULL CHECK(scope_count BETWEEN 0 AND 128),
            citation_count INTEGER NOT NULL CHECK(citation_count BETWEEN 0 AND 32),
            question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
            answer_sha256 TEXT CHECK(answer_sha256 IS NULL OR (length(answer_sha256)=64 AND answer_sha256 NOT GLOB '*[^a-f0-9]*')),
            outcome TEXT NOT NULL CHECK(outcome IN ('answered','abstained')),reason_code TEXT,
            model_revision TEXT NOT NULL,prompt_revision TEXT NOT NULL,policy_revision TEXT NOT NULL,created_at TEXT NOT NULL,
            CHECK((outcome='answered' AND answer_sha256 IS NOT NULL AND reason_code IS NULL AND citation_count>0)
              OR (outcome='abstained' AND answer_sha256 IS NULL AND reason_code IS NOT NULL AND citation_count=0)))
          """);
          store.execute(
              """
          CREATE TABLE knowledge_answer_documents(
            trace_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 127),publication_id TEXT NOT NULL,
            PRIMARY KEY(trace_id,ordinal),UNIQUE(trace_id,publication_id),
            FOREIGN KEY(trace_id) REFERENCES knowledge_answer_traces(id) DEFERRABLE INITIALLY DEFERRED)
          """);
          store.execute(
              """
          CREATE TABLE knowledge_answer_citations(
            trace_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 1 AND 32),publication_id TEXT NOT NULL,
            kind TEXT NOT NULL CHECK(kind IN ('DOCUMENT_TEXT','VIDEO_TRANSCRIPT','VIDEO_SUBTITLE','VIDEO_FRAME_OCR')),
            physical_id TEXT NOT NULL,start_offset INTEGER NOT NULL CHECK(start_offset>=0),
            end_offset INTEGER NOT NULL CHECK(end_offset>start_offset AND end_offset-start_offset<=1200),
            context_sha256 TEXT NOT NULL CHECK(length(context_sha256)=64 AND context_sha256 NOT GLOB '*[^a-f0-9]*'),
            quote_sha256 TEXT NOT NULL CHECK(length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*'),
            page_number INTEGER,start_us INTEGER,end_us INTEGER,
            PRIMARY KEY(trace_id,ordinal),
            FOREIGN KEY(trace_id,publication_id) REFERENCES knowledge_answer_documents(trace_id,publication_id) ON DELETE RESTRICT,
            CHECK((kind='DOCUMENT_TEXT' AND page_number BETWEEN 1 AND 500 AND page_number IS NOT NULL AND start_us IS NULL AND end_us IS NULL)
              OR (kind!='DOCUMENT_TEXT' AND page_number IS NULL AND start_us IS NOT NULL AND end_us IS NOT NULL AND start_us>=0 AND end_us>start_us AND end_us<=600000000)))
          """);
          store.execute(
              """
          CREATE TRIGGER knowledge_answer_traces_complete BEFORE INSERT ON knowledge_answer_traces
          WHEN NEW.scope_count!=(SELECT COUNT(*) FROM knowledge_answer_documents WHERE trace_id=NEW.id)
            OR (NEW.scope_count>0 AND (SELECT MIN(ordinal)!=0 OR MAX(ordinal)!=NEW.scope_count-1 FROM knowledge_answer_documents WHERE trace_id=NEW.id))
            OR NEW.citation_count!=(SELECT COUNT(*) FROM knowledge_answer_citations WHERE trace_id=NEW.id)
            OR (NEW.citation_count>0 AND (SELECT MIN(ordinal)!=1 OR MAX(ordinal)!=NEW.citation_count FROM knowledge_answer_citations WHERE trace_id=NEW.id))
            OR EXISTS(SELECT 1 FROM knowledge_answer_documents s LEFT JOIN index_publications p ON p.id=s.publication_id
              LEFT JOIN documents d ON d.id=p.document_id WHERE s.trace_id=NEW.id AND (p.id IS NULL OR d.workspace_id IS NOT NEW.workspace_id))
          BEGIN SELECT RAISE(ABORT,'incomplete knowledge answer'); END
          """);
          store.execute(
              """
          CREATE TRIGGER knowledge_answer_citations_identity BEFORE INSERT ON knowledge_answer_citations
          WHEN NOT (
            (NEW.kind='DOCUMENT_TEXT' AND EXISTS(SELECT 1 FROM index_publication_entries e JOIN corpus_segments s ON s.id=e.source_segment_id
              WHERE e.publication_id=NEW.publication_id AND e.physical_segment_id=NEW.physical_id AND s.page_number=NEW.page_number AND NEW.start_offset>=s.start_offset AND NEW.end_offset<=s.end_offset))
            OR (NEW.kind='VIDEO_TRANSCRIPT' AND EXISTS(SELECT 1 FROM video_transcript_publication_entries e JOIN video_transcript_spans s ON s.id=e.video_transcript_span_id
              WHERE e.publication_id=NEW.publication_id AND e.physical_segment_id=NEW.physical_id AND NEW.start_us=s.start_ms*1000 AND NEW.end_us=s.end_ms*1000))
            OR (NEW.kind='VIDEO_SUBTITLE' AND EXISTS(SELECT 1 FROM video_subtitle_publication_entries e JOIN video_subtitle_cues s ON s.id=e.video_subtitle_cue_id
              WHERE e.publication_id=NEW.publication_id AND e.physical_segment_id=NEW.physical_id AND NEW.start_us=s.start_us AND NEW.end_us=s.end_us AND NEW.start_offset>=s.start_offset AND NEW.end_offset<=s.end_offset))
            OR (NEW.kind='VIDEO_FRAME_OCR' AND EXISTS(SELECT 1 FROM video_ocr_publication_entries e JOIN video_ocr_segments s ON s.id=e.video_ocr_segment_id JOIN video_frames f ON f.id=s.frame_id
              WHERE e.publication_id=NEW.publication_id AND e.physical_segment_id=NEW.physical_id AND NEW.start_us=f.presentation_us AND NEW.end_us=f.presentation_us+f.duration_us AND NEW.start_offset>=s.start_offset AND NEW.end_offset<=s.end_offset)))
          BEGIN SELECT RAISE(ABORT,'invalid knowledge citation'); END
          """);
          for (String table :
              List.of(
                  "knowledge_answer_traces",
                  "knowledge_answer_documents",
                  "knowledge_answer_citations")) {
            for (String operation : List.of("UPDATE", "DELETE")) {
              store.execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable knowledge answer'); END");
            }
            String identity =
                table.equals("knowledge_answer_traces")
                    ? "id=NEW.id"
                    : "trace_id=NEW.trace_id AND ordinal=NEW.ordinal";
            store.execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_replace BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM "
                    + table
                    + " WHERE "
                    + identity
                    + ") BEGIN SELECT RAISE(ABORT,'immutable knowledge answer'); END");
            if (!table.equals("knowledge_answer_traces")) {
              store.execute(
                  "CREATE TRIGGER "
                      + table
                      + "_sealed BEFORE INSERT ON "
                      + table
                      + " WHEN EXISTS(SELECT 1 FROM knowledge_answer_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed knowledge answer'); END");
            }
          }
          refreshInventory();
          store.execute("UPDATE format_info SET version=29");
          store.execute("PRAGMA user_version=29");
          verify();
          return null;
        });
  }

  void verify() {
    new VideoTextEvidenceSchema(store).verify(29);
    if (store.count(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('knowledge_answer_traces','knowledge_answer_documents','knowledge_answer_citations')")
        != 3) {
      throw new IllegalStateException("Unsupported knowledge answer authority format");
    }
  }

  private void refreshInventory() {
    String guard =
        AuthorityRows.text(
            store
                .rows(
                    "SELECT sql FROM sqlite_master WHERE type='trigger' AND name='cleanup_schema_objects_no_update'")
                .getFirst(),
            "sql");
    store.execute("DROP TRIGGER cleanup_schema_objects_no_update");
    for (var row :
        store.rows(
            "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
      String name = AuthorityRows.text(row, "name");
      String hash =
          ModelValues.sha256(AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8));
      if (store.count("SELECT COUNT(*) FROM cleanup_schema_objects WHERE name=?", name) == 0) {
        store.execute(
            "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
            name,
            row.get("type"),
            hash);
      } else {
        store.execute(
            "UPDATE cleanup_schema_objects SET object_type=?,sql_sha256=? WHERE name=?",
            row.get("type"),
            hash,
            name);
      }
    }
    store.execute(guard);
  }
}
