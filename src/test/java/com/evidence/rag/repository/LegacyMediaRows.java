package com.evidence.rag.repository;

import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Raw rows of the removed sound and video audiovisual libraries (2026-11-03), written exactly as
 * the old code wrote them so that kept legacy reads, cleanup and purge stay covered. Callers hold
 * the Store transaction and have already inserted the matching {@code documents} row.
 */
public final class LegacyMediaRows {
  private LegacyMediaRows() {}

  public static void soundOriginal(
      SqliteAuthorityStore store, DocumentOriginal original, String createdAt) {
    original(store, "sound_originals", original, createdAt);
  }

  public static void videoAvOriginal(
      SqliteAuthorityStore store, DocumentOriginal original, String createdAt) {
    original(store, "video_av_originals", original, createdAt);
  }

  /** One sealed single-span sound publication of the original's active revision. Returns its id. */
  public static String soundPublication(
      SqliteAuthorityStore store,
      String workspaceId,
      DocumentOriginal original,
      String recallText,
      String createdAt) {
    String id = UUID.randomUUID().toString();
    String generation = UUID.randomUUID().toString();
    String span = "sound-" + ModelValues.sha256(bytes(original.revisionId() + ":0"));
    String physical = "seg-" + ModelValues.sha256(bytes(generation + ":" + span));
    store.execute(
        """
        INSERT INTO sound_spans(publication_id,id,ordinal,start_sample,end_sample,pcm_sha256,
          recall_text,physical_segment_id,entry_sha256) VALUES(?,?,0,0,16000,?,?,?,?)
        """,
        id,
        span,
        "1".repeat(64),
        recallText,
        physical,
        "2".repeat(64));
    store.execute(
        """
        INSERT INTO sound_publications(id,workspace_id,document_id,source_revision_id,source_sha256,
          filename,media_type,size_bytes,generation_id,embedding_identity,projection_identity,
          embedding_model_revision,dimensions,sound_model_revision,decoder_revision,chunk_seconds,
          sample_count,span_count,manifest_sha256,profile_fingerprint,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,'legacy-embedding',?,'legacy-revision',2,'legacy-sound',
          'legacy-decoder',1,16000,1,?,?,?)
        """,
        id,
        workspaceId,
        original.documentId(),
        original.revisionId(),
        original.sourceSha256(),
        original.filename(),
        original.mediaType(),
        original.sizeBytes(),
        generation,
        "d".repeat(64),
        "3".repeat(64),
        "4".repeat(64),
        createdAt);
    // The old library registered its remote write before publishing, for later cleanup.
    store.execute(
        """
        INSERT INTO cleanup_projection_attempts(document_id,workspace_id,source_revision_id,
          source_sha256,generation_id,route,endpoint,database_name,collection_name,
          embedding_identity,dimensions,projection_identity,write_issued)
        VALUES(?,?,?,?,?,'sound','http://127.0.0.1:19530/','default','java_sound_legacy',
          'legacy-embedding',2,?,1)
        """,
        original.documentId(),
        workspaceId,
        original.revisionId(),
        original.sourceSha256(),
        generation,
        "d".repeat(64));
    return id;
  }

  private static void original(
      SqliteAuthorityStore store, String table, DocumentOriginal original, String createdAt) {
    store.execute(
        "INSERT INTO "
            + table
            + "(document_id,source_revision_id,source_sha256,filename,media_type,size_bytes,"
            + "original_blob,created_at) VALUES(?,?,?,?,?,?,?,?)",
        original.documentId(),
        original.revisionId(),
        original.sourceSha256(),
        original.filename(),
        original.mediaType(),
        original.sizeBytes(),
        original.content(),
        createdAt);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
