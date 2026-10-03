package com.evidence.rag.repository;

import java.util.List;
import java.util.stream.Collectors;

/** Closed v22 body-column inventory. Identifiers are source constants, never caller SQL. */
final class CleanupPayloadTables {
  record Body(String name, String empty) {}

  record Table(String name, List<String> keys, String owner, List<Body> bodies) {
    String document(String alias) {
      return owner.replace("$", alias);
    }

    String rowKey(String alias) {
      return keys.stream()
          .map(k -> "hex(CAST(" + alias + "." + k + " AS BLOB))")
          .collect(Collectors.joining("||':'||"));
    }

    String empty(String alias) {
      return bodies.stream()
          .map(b -> alias + "." + b.name() + " IS " + b.empty())
          .collect(Collectors.joining(" AND "));
    }
  }

  private static Body text(String name) {
    return new Body(name, "''");
  }

  private static Body blob(String name) {
    return new Body(name, "x''");
  }

  private static Table revision(String name, String key, Body... bodies) {
    return new Table(
        name,
        List.of(key),
        "(SELECT document_id FROM corpus_revisions WHERE id=$.revision_id)",
        List.of(bodies));
  }

  static final List<Table> TABLES =
      List.of(
          new Table(
              "corpus_documents",
              List.of("document_id"),
              "$.document_id",
              List.of(blob("original_blob"))),
          new Table(
              "sound_originals",
              List.of("document_id"),
              "$.document_id",
              List.of(blob("original_blob"))),
          new Table(
              "video_av_originals",
              List.of("document_id"),
              "$.document_id",
              List.of(blob("original_blob"))),
          new Table(
              "corpus_pages",
              List.of("revision_id", "page_number"),
              "(SELECT document_id FROM corpus_revisions WHERE id=$.revision_id)",
              List.of(text("text"))),
          revision("corpus_segments", "id", text("text")),
          revision("image_evidence", "id", text("recall_text")),
          revision("audio_spans", "id", text("text")),
          revision("video_frames", "id", blob("frame_blob"), text("recall_text")),
          revision("video_transcript_spans", "id", text("text")),
          revision("video_frame_ocr", "frame_id", text("text")),
          revision("video_ocr_segments", "id", text("text")),
          revision("video_subtitle_tracks", "id", text("text")),
          revision("video_subtitle_cues", "id", text("text")),
          new Table(
              "synopsis_entries",
              List.of("task_id", "ordinal"),
              "(SELECT document_id FROM synopsis_tasks WHERE id=$.task_id)",
              List.of(text("text"))),
          new Table(
              "sound_spans",
              List.of("publication_id", "id"),
              "(SELECT document_id FROM sound_publications WHERE id=$.publication_id)",
              List.of(text("recall_text"))),
          new Table(
              "sound_trace_evidence",
              List.of("trace_id", "ordinal"),
              "(SELECT document_id FROM sound_publications WHERE id=$.publication_id)",
              List.of(new Body("facts_json", "'[]'"))),
          new Table(
              "video_av_trace_evidence",
              List.of("trace_id", "ordinal"),
              "(SELECT document_id FROM video_av_publications WHERE id=$.publication_id)",
              List.of(new Body("facts_json", "'[]'"))));

  private CleanupPayloadTables() {}

  static List<String> names() {
    return TABLES.stream().map(Table::name).toList();
  }
}
