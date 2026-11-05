-- Java library schema v43 (evidence-rag-java-management-v1): every table, index and trigger.
-- Tables come in foreign-key dependency order (creation order inside reference cycles), then
-- indexes, then triggers. Generated once from sqlite_master of a fresh library built by the
-- v1->v43 migration chain this file replaced.
-- Each statement is stored verbatim (cleanup_schema_objects hashes the exact text) and ends with
-- a line holding only ';'. Do not edit: the schema is frozen at v43.

CREATE TABLE format_info(format TEXT PRIMARY KEY,version INTEGER NOT NULL)
;

CREATE TABLE folders(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,owner_id TEXT NOT NULL,name TEXT NOT NULL,name_key TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(workspace_id,owner_id,name_key))
;

CREATE TABLE documents(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,filename TEXT NOT NULL,document_type TEXT NOT NULL,mime_type TEXT NOT NULL,active_revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,size_bytes INTEGER NOT NULL,updated_at TEXT NOT NULL,display_name TEXT NOT NULL,folder_id TEXT REFERENCES folders(id) ON DELETE RESTRICT)
;

CREATE TABLE document_acl(document_id TEXT REFERENCES documents(id) ON DELETE CASCADE,principal_id TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('owner','editor','reader')),PRIMARY KEY(document_id,principal_id))
;

CREATE TABLE document_tags(document_id TEXT REFERENCES documents(id) ON DELETE CASCADE,tag TEXT NOT NULL,ordinal INTEGER NOT NULL,PRIMARY KEY(document_id,tag))
;

CREATE TABLE management_audit(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,entity_id TEXT NOT NULL,action TEXT NOT NULL,fields_json TEXT NOT NULL,before_sha256 TEXT NOT NULL,after_sha256 TEXT NOT NULL,created_at TEXT NOT NULL)
;

CREATE TABLE corpus_revisions(id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,parser_revision TEXT NOT NULL,source_sha256 TEXT NOT NULL,created_at TEXT NOT NULL,parsed_at TEXT,page_count INTEGER NOT NULL DEFAULT 0 CHECK(page_count BETWEEN 0 AND 500),segment_count INTEGER NOT NULL DEFAULT 0 CHECK(segment_count BETWEEN 0 AND 4096),UNIQUE(document_id,id))
;

CREATE TABLE document_tombstones(
  document_id TEXT PRIMARY KEY NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
  workspace_id TEXT NOT NULL CHECK(length(workspace_id)>0),
  requested_by TEXT NOT NULL CHECK(length(requested_by)>0),
  requested_at TEXT NOT NULL CHECK(length(requested_at)>0))
;

CREATE TABLE audio_compilations(
  revision_id TEXT PRIMARY KEY NOT NULL REFERENCES corpus_revisions(id) ON DELETE RESTRICT,
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  decoder_revision TEXT NOT NULL CHECK(length(decoder_revision) BETWEEN 1 AND 200),
  model_revision TEXT NOT NULL CHECK(length(model_revision) BETWEEN 1 AND 200),
  compiler_revision TEXT NOT NULL CHECK(length(compiler_revision) BETWEEN 1 AND 200),
  duration_ms INTEGER NOT NULL CHECK(duration_ms BETWEEN 1 AND 600000),
  span_count INTEGER NOT NULL CHECK(span_count BETWEEN 1 AND 600),
  projection_count INTEGER NOT NULL CHECK(projection_count BETWEEN 1 AND span_count),
  transcript_sha256 TEXT NOT NULL CHECK(length(transcript_sha256)=64 AND transcript_sha256 NOT GLOB '*[^a-f0-9]*'),
  created_at TEXT NOT NULL)
;

CREATE TABLE sound_publications(
  id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 128),workspace_id TEXT NOT NULL,
  document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
  source_revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  filename TEXT NOT NULL,media_type TEXT NOT NULL,size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520),
  generation_id TEXT NOT NULL UNIQUE CHECK(length(generation_id)=36),
  embedding_identity TEXT NOT NULL,projection_identity TEXT NOT NULL CHECK(length(projection_identity)=64 AND projection_identity NOT GLOB '*[^a-f0-9]*'),
  embedding_model_revision TEXT NOT NULL,dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 2 AND 3072),
  sound_model_revision TEXT NOT NULL,decoder_revision TEXT NOT NULL,chunk_seconds INTEGER NOT NULL CHECK(chunk_seconds BETWEEN 1 AND 30),
  sample_count INTEGER NOT NULL CHECK(sample_count BETWEEN 1 AND 9600000),span_count INTEGER NOT NULL CHECK(span_count BETWEEN 1 AND 600),
  manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  profile_fingerprint TEXT NOT NULL CHECK(length(profile_fingerprint)=64 AND profile_fingerprint NOT GLOB '*[^a-f0-9]*'),created_at TEXT NOT NULL,
  UNIQUE(document_id,source_revision_id,profile_fingerprint))
;

CREATE TABLE video_av_publications(
  id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=36),workspace_id TEXT NOT NULL,
  document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
  source_revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  filename TEXT NOT NULL,media_type TEXT NOT NULL,size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520),
  source_first_pts INTEGER NOT NULL,source_time_base_num INTEGER NOT NULL CHECK(source_time_base_num>0),
  source_time_base_den INTEGER NOT NULL CHECK(source_time_base_den>0),ticks_per_second INTEGER NOT NULL CHECK(ticks_per_second>0 AND ticks_per_second%16000=0),
  duration_tick INTEGER NOT NULL CHECK(duration_tick>0 AND duration_tick/ticks_per_second<=600),has_audio INTEGER NOT NULL CHECK(has_audio IN(0,1)),
  decoder_revision TEXT NOT NULL,analysis_model_revision TEXT NOT NULL,
  profile_fingerprint TEXT NOT NULL CHECK(length(profile_fingerprint)=64 AND profile_fingerprint NOT GLOB '*[^a-f0-9]*'),
  visual_embedding_identity TEXT NOT NULL,visual_projection_identity TEXT NOT NULL CHECK(length(visual_projection_identity)=64 AND visual_projection_identity NOT GLOB '*[^a-f0-9]*'),
  visual_model_revision TEXT NOT NULL,visual_dimensions INTEGER NOT NULL CHECK(visual_dimensions BETWEEN 2 AND 3072),
  audio_embedding_identity TEXT NOT NULL,audio_projection_identity TEXT NOT NULL CHECK(length(audio_projection_identity)=64 AND audio_projection_identity NOT GLOB '*[^a-f0-9]*'),
  audio_model_revision TEXT NOT NULL,audio_dimensions INTEGER NOT NULL CHECK(audio_dimensions BETWEEN 2 AND 3072),
  chunk_seconds INTEGER NOT NULL CHECK(chunk_seconds BETWEEN 1 AND 30),window_count INTEGER NOT NULL CHECK(window_count BETWEEN 1 AND 1201),
  window_manifest_sha256 TEXT NOT NULL CHECK(length(window_manifest_sha256)=64 AND window_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  visual_entry_count INTEGER NOT NULL CHECK(visual_entry_count BETWEEN 1 AND 1201),visual_manifest_sha256 TEXT NOT NULL CHECK(length(visual_manifest_sha256)=64 AND visual_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  audio_entry_count INTEGER NOT NULL CHECK(audio_entry_count BETWEEN 0 AND 1201),audio_manifest_sha256 TEXT NOT NULL CHECK(length(audio_manifest_sha256)=64 AND audio_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  created_at_ms INTEGER NOT NULL CHECK(created_at_ms>0),
  CHECK(visual_embedding_identity=audio_embedding_identity AND visual_model_revision=audio_model_revision AND visual_embedding_identity=visual_model_revision AND visual_dimensions=audio_dimensions AND visual_projection_identity!=audio_projection_identity),
  UNIQUE(document_id,source_revision_id,profile_fingerprint))
;

CREATE TABLE video_av_windows(
  publication_id TEXT NOT NULL REFERENCES video_av_publications(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  id TEXT NOT NULL CHECK(length(id)=67 AND substr(id,1,3)='av-' AND substr(id,4) NOT GLOB '*[^a-f0-9]*'),
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 1200),start_tick INTEGER NOT NULL CHECK(start_tick>=0),end_tick INTEGER NOT NULL CHECK(end_tick>start_tick),
  clip_sha256 TEXT,frame_count INTEGER,frames_manifest_sha256 TEXT,first_local_tick INTEGER,end_local_tick INTEGER,
  pcm_sha256 TEXT,wav_sha256 TEXT,audio_start_sample INTEGER,audio_end_sample INTEGER,sample_rate INTEGER,
  visual_physical_id TEXT UNIQUE,visual_entry_sha256 TEXT,audio_physical_id TEXT UNIQUE,audio_entry_sha256 TEXT,
  CHECK((clip_sha256 IS NULL AND frame_count IS NULL AND frames_manifest_sha256 IS NULL AND first_local_tick IS NULL AND end_local_tick IS NULL AND visual_physical_id IS NULL AND visual_entry_sha256 IS NULL)
    OR (clip_sha256 IS NOT NULL AND length(clip_sha256)=64 AND clip_sha256 NOT GLOB '*[^a-f0-9]*' AND frame_count IS NOT NULL AND frame_count>0 AND frames_manifest_sha256 IS NOT NULL AND length(frames_manifest_sha256)=64 AND frames_manifest_sha256 NOT GLOB '*[^a-f0-9]*'
      AND first_local_tick IS NOT NULL AND end_local_tick IS NOT NULL AND first_local_tick>=0 AND end_local_tick>first_local_tick AND end_local_tick<=end_tick-start_tick AND visual_physical_id IS NOT NULL AND length(visual_physical_id)=68 AND substr(visual_physical_id,1,4)='seg-' AND substr(visual_physical_id,5) NOT GLOB '*[^a-f0-9]*' AND visual_entry_sha256 IS NOT NULL AND length(visual_entry_sha256)=64 AND visual_entry_sha256 NOT GLOB '*[^a-f0-9]*')),
  CHECK((pcm_sha256 IS NULL AND wav_sha256 IS NULL AND audio_start_sample IS NULL AND audio_end_sample IS NULL AND sample_rate IS NULL AND audio_physical_id IS NULL AND audio_entry_sha256 IS NULL)
    OR (pcm_sha256 IS NOT NULL AND length(pcm_sha256)=64 AND pcm_sha256 NOT GLOB '*[^a-f0-9]*' AND wav_sha256 IS NOT NULL AND length(wav_sha256)=64 AND wav_sha256 NOT GLOB '*[^a-f0-9]*' AND audio_start_sample IS NOT NULL AND audio_end_sample IS NOT NULL AND sample_rate IS NOT NULL AND audio_start_sample>=0 AND audio_end_sample>audio_start_sample AND audio_end_sample<=9600000 AND audio_end_sample-audio_start_sample<=480000 AND sample_rate=16000
      AND audio_physical_id IS NOT NULL AND length(audio_physical_id)=68 AND substr(audio_physical_id,1,4)='seg-' AND substr(audio_physical_id,5) NOT GLOB '*[^a-f0-9]*' AND audio_entry_sha256 IS NOT NULL AND length(audio_entry_sha256)=64 AND audio_entry_sha256 NOT GLOB '*[^a-f0-9]*')),
  CHECK(clip_sha256 IS NOT NULL OR pcm_sha256 IS NOT NULL),CHECK(visual_physical_id IS NULL OR audio_physical_id IS NULL OR visual_physical_id!=audio_physical_id),
  PRIMARY KEY(publication_id,id),UNIQUE(publication_id,ordinal))
;

CREATE TABLE cleanup_library(id INTEGER PRIMARY KEY CHECK(id=1),library_id TEXT NOT NULL UNIQUE,
  backups_known INTEGER NOT NULL CHECK(backups_known IN (0,1)))
;

CREATE TABLE document_cleanups(id TEXT PRIMARY KEY,document_id TEXT NOT NULL UNIQUE REFERENCES documents(id) ON DELETE RESTRICT,
  workspace_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,requested_by TEXT NOT NULL,
  requested_at TEXT NOT NULL,updated_at TEXT NOT NULL,completed_at TEXT,
  state TEXT NOT NULL CHECK(state IN ('pending','running','blocked','failed','completed')),
  claim_sha256 TEXT,error_code TEXT,
  CHECK((state='completed')=(completed_at IS NOT NULL)),CHECK(state!='running' OR claim_sha256 IS NOT NULL))
;

CREATE TABLE cleanup_resources(cleanup_id TEXT NOT NULL REFERENCES document_cleanups(id) ON DELETE RESTRICT,
  kind TEXT NOT NULL CHECK(kind IN ('database_payload','database_file','managed_backups','managed_temporaries','remote_inventory','remote_logical_rows','remote_write_terminal','remote_physical_storage','restore_barrier')),
  status TEXT NOT NULL CHECK(status IN ('pending','running','completed','not_applicable','blocked','failed')),
  PRIMARY KEY(cleanup_id,kind))
;

CREATE TABLE cleanup_plans(cleanup_id TEXT PRIMARY KEY REFERENCES document_cleanups(id) ON DELETE RESTRICT,
  document_id TEXT NOT NULL,workspace_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,
  manifest_sha256 TEXT NOT NULL,payload_count INTEGER NOT NULL CHECK(payload_count>=0))
;

CREATE TABLE cleanup_payloads(cleanup_id TEXT NOT NULL REFERENCES cleanup_plans(cleanup_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  table_name TEXT NOT NULL,row_key TEXT NOT NULL,body_column TEXT NOT NULL,
  size_bytes INTEGER NOT NULL CHECK(size_bytes>=0),body_sha256 TEXT NOT NULL CHECK(length(body_sha256)=64),
  PRIMARY KEY(cleanup_id,table_name,row_key,body_column))
;

CREATE TABLE cleanup_document_provenance(document_id TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE RESTRICT,
  inventory_known INTEGER NOT NULL CHECK(inventory_known IN (0,1)))
;

CREATE TABLE cleanup_projection_attempts(document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
  workspace_id TEXT NOT NULL,source_revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,
  generation_id TEXT NOT NULL,route TEXT NOT NULL,endpoint TEXT NOT NULL,database_name TEXT NOT NULL,
  collection_name TEXT NOT NULL,embedding_identity TEXT NOT NULL,dimensions INTEGER NOT NULL,
  projection_identity TEXT NOT NULL,write_issued INTEGER NOT NULL CHECK(write_issued IN (0,1)),
  PRIMARY KEY(generation_id,route))
;

CREATE TABLE cleanup_managed_backups(relative_path TEXT PRIMARY KEY,sha256 TEXT NOT NULL,
  size_bytes INTEGER NOT NULL CHECK(size_bytes>=0))
;

CREATE TABLE cleanup_schema_objects(name TEXT PRIMARY KEY,object_type TEXT NOT NULL,sql_sha256 TEXT NOT NULL)
;

CREATE TABLE "corpus_documents"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),document_id TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE RESTRICT,original_blob BLOB NOT NULL CHECK(payload_purged=1 OR (length(original_blob) BETWEEN 1 AND 20971520)),initial_revision_id TEXT NOT NULL,parsed_revision_id TEXT,active_revision_id TEXT CHECK(active_revision_id IS NULL),FOREIGN KEY(document_id,initial_revision_id) REFERENCES corpus_revisions(document_id,id),FOREIGN KEY(document_id,parsed_revision_id) REFERENCES corpus_revisions(document_id,id),CHECK(payload_purged=0 OR (original_blob IS x'')))
;

CREATE TABLE "sound_originals"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  document_id TEXT PRIMARY KEY NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
  source_revision_id TEXT NOT NULL UNIQUE,
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  filename TEXT NOT NULL,media_type TEXT NOT NULL,
  size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520),
  original_blob BLOB NOT NULL CHECK(payload_purged=1 OR (length(original_blob)=0 OR length(original_blob)=size_bytes)),
  created_at TEXT NOT NULL,CHECK(payload_purged=0 OR (original_blob IS x'')))
;

CREATE TABLE "video_av_originals"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  document_id TEXT PRIMARY KEY NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
  source_revision_id TEXT NOT NULL UNIQUE,
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  filename TEXT NOT NULL,media_type TEXT NOT NULL,
  size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520),
  original_blob BLOB NOT NULL CHECK(payload_purged=1 OR (length(original_blob)=0 OR length(original_blob)=size_bytes)),
  created_at TEXT NOT NULL,CHECK(payload_purged=0 OR (original_blob IS x'')))
;

CREATE TABLE "corpus_pages"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),revision_id TEXT NOT NULL REFERENCES corpus_revisions(id) ON DELETE RESTRICT,page_number INTEGER NOT NULL CHECK(page_number BETWEEN 1 AND 500),text TEXT NOT NULL,text_sha256 TEXT NOT NULL,PRIMARY KEY(revision_id,page_number),CHECK(payload_purged=0 OR (text IS '')))
;

CREATE TABLE image_text_regions(
  revision_id TEXT NOT NULL,page_number INTEGER NOT NULL CHECK(page_number=1),
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 49999),
  start_offset INTEGER NOT NULL CHECK(start_offset>=0),
  end_offset INTEGER NOT NULL CHECK(end_offset>start_offset),
  left_pixel INTEGER NOT NULL CHECK(left_pixel>=0),
  top_pixel INTEGER NOT NULL CHECK(top_pixel>=0),
  right_pixel INTEGER NOT NULL CHECK(right_pixel>left_pixel),
  bottom_pixel INTEGER NOT NULL CHECK(bottom_pixel>top_pixel),
  PRIMARY KEY(revision_id,ordinal),
  FOREIGN KEY(revision_id,page_number) REFERENCES corpus_pages(revision_id,page_number) ON DELETE RESTRICT)
;

CREATE TABLE "corpus_segments"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),id TEXT PRIMARY KEY,revision_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 4095),page_number INTEGER NOT NULL,start_offset INTEGER NOT NULL CHECK(start_offset>=0),end_offset INTEGER NOT NULL CHECK(end_offset>start_offset AND end_offset-start_offset<=1200),text TEXT NOT NULL,text_sha256 TEXT NOT NULL,UNIQUE(revision_id,ordinal),FOREIGN KEY(revision_id,page_number) REFERENCES corpus_pages(revision_id,page_number) ON DELETE RESTRICT,CHECK(payload_purged=0 OR (text IS '')))
;

CREATE TABLE "image_evidence"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  id TEXT PRIMARY KEY NOT NULL,
  revision_id TEXT NOT NULL UNIQUE REFERENCES corpus_revisions(id) ON DELETE RESTRICT,
  width INTEGER NOT NULL CHECK(width BETWEEN 1 AND 12000000),
  height INTEGER NOT NULL CHECK(height BETWEEN 1 AND 12000000 AND width*height<=12000000),
  recall_text TEXT NOT NULL CHECK(payload_purged=1 OR (length(recall_text) BETWEEN 1 AND 4096 AND length(CAST(recall_text AS BLOB))<=16384)),
  recall_sha256 TEXT NOT NULL CHECK(length(recall_sha256)=64 AND recall_sha256 NOT GLOB '*[^a-f0-9]*'),
  description_revision TEXT NOT NULL CHECK(length(description_revision) BETWEEN 1 AND 128),
  created_at TEXT NOT NULL,CHECK(payload_purged=0 OR (recall_text IS '')))
;

CREATE TABLE "audio_spans"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  id TEXT PRIMARY KEY NOT NULL,
  revision_id TEXT NOT NULL REFERENCES audio_compilations(revision_id) ON DELETE RESTRICT,
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 599),
  start_ms INTEGER NOT NULL CHECK(start_ms>=0),
  end_ms INTEGER NOT NULL CHECK(end_ms>start_ms AND end_ms<=600000 AND end_ms-start_ms<=30000),
  text TEXT NOT NULL CHECK(payload_purged=1 OR (length(text)<=4096 AND length(CAST(text AS BLOB))<=16384)),
  text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
  index_ordinal INTEGER CHECK(index_ordinal BETWEEN 0 AND 599),
  UNIQUE(revision_id,ordinal),UNIQUE(revision_id,index_ordinal),
  CHECK(payload_purged=1 OR ((index_ordinal IS NULL AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))=0)
    OR (index_ordinal IS NOT NULL AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))>0))),CHECK(payload_purged=0 OR (text IS '')))
;

CREATE TABLE "sound_spans"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  publication_id TEXT NOT NULL REFERENCES sound_publications(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  id TEXT NOT NULL CHECK(length(id)=70 AND substr(id,1,6)='sound-' AND substr(id,7) NOT GLOB '*[^a-f0-9]*'),
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 599),start_sample INTEGER NOT NULL CHECK(start_sample>=0),
  end_sample INTEGER NOT NULL CHECK(end_sample>start_sample AND end_sample<=9600000 AND end_sample-start_sample<=480000),
  pcm_sha256 TEXT NOT NULL CHECK(length(pcm_sha256)=64 AND pcm_sha256 NOT GLOB '*[^a-f0-9]*'),
  recall_text TEXT NOT NULL CHECK(payload_purged=1 OR (length(CAST(recall_text AS BLOB))<=8192)),
  physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id)=68 AND substr(physical_segment_id,1,4)='seg-' AND substr(physical_segment_id,5) NOT GLOB '*[^a-f0-9]*'),
  entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
  PRIMARY KEY(publication_id,id),UNIQUE(publication_id,ordinal),CHECK(payload_purged=0 OR (recall_text IS '')))
;

CREATE TABLE document_original_revisions(revision_id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,filename TEXT NOT NULL,document_type TEXT NOT NULL CHECK(document_type IN ('document','image','audio','video')),media_type TEXT NOT NULL,source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),size_bytes INTEGER NOT NULL CHECK(typeof(size_bytes)='integer' AND size_bytes BETWEEN 1 AND 20971520),original_blob BLOB NOT NULL,created_at TEXT NOT NULL,payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),UNIQUE(document_id,revision_id),CHECK((payload_purged=0 AND length(original_blob)=size_bytes) OR (payload_purged=1 AND length(original_blob)=0)))
;

CREATE TABLE document_replacements(id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,base_revision_id TEXT NOT NULL,candidate_revision_id TEXT NOT NULL UNIQUE,base_publication_id TEXT,pipeline TEXT NOT NULL CHECK(pipeline IN ('corpus','sound','video_av')),state TEXT NOT NULL CHECK(state IN ('stored','queued','processing','parsed','indexing','published','failed','cancelled')),created_by TEXT NOT NULL,ingestion_job_id TEXT,index_job_id TEXT,publication_id TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,FOREIGN KEY(document_id,base_revision_id) REFERENCES document_original_revisions(document_id,revision_id),FOREIGN KEY(document_id,candidate_revision_id) REFERENCES document_original_revisions(document_id,revision_id),CHECK(base_revision_id!=candidate_revision_id),CHECK((state='published')=(publication_id IS NOT NULL)))
;

CREATE TABLE "ingestion_jobs"(replacement_id TEXT REFERENCES document_replacements(id) ON DELETE RESTRICT,id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES corpus_documents(document_id) ON DELETE RESTRICT,revision_id TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('queued','processing','parsed','failed','cancelled')),attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 3),claim_token_sha256 TEXT,created_by TEXT NOT NULL,error_code TEXT CHECK(error_code IN ('unsupported_document','parser_failed','parser_timeout','parser_output_invalid','worker_interrupted','text_configuration_required','media_text_configuration_mismatch')),created_at TEXT NOT NULL,updated_at TEXT NOT NULL,FOREIGN KEY(document_id,revision_id) REFERENCES corpus_revisions(document_id,id),CHECK((state='processing' AND claim_token_sha256 IS NOT NULL AND length(claim_token_sha256)=64) OR (state!='processing' AND claim_token_sha256 IS NULL)),CHECK((state='failed' AND error_code IS NOT NULL) OR (state!='failed' AND error_code IS NULL)),UNIQUE(document_id,revision_id))
;

CREATE TABLE model_rebuilds(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,created_by TEXT NOT NULL,
  base_active_version INTEGER,base_configuration_sha256 TEXT,base_anchor_sha256 TEXT,
  base_batch_id TEXT,base_selected_at TEXT NOT NULL,target_version INTEGER NOT NULL,
  configuration_sha256 TEXT NOT NULL,anchor_sha256 TEXT NOT NULL,
  embedding_identity TEXT NOT NULL,projection_identity TEXT NOT NULL,model_revision TEXT NOT NULL,
  dimensions INTEGER NOT NULL CHECK(typeof(dimensions)='integer' AND dimensions BETWEEN 2 AND 8192),
  state TEXT NOT NULL CHECK(state IN ('queued','running','applying','completed','failed')),
  total_documents INTEGER NOT NULL CHECK(typeof(total_documents)='integer' AND total_documents>=0),
  error_code TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
  FOREIGN KEY(base_batch_id) REFERENCES model_rebuilds(id) ON DELETE RESTRICT,
  CHECK(typeof(target_version)='integer' AND target_version BETWEEN 1 AND 9007199254740991),
  CHECK(length(configuration_sha256)=64 AND configuration_sha256 NOT GLOB '*[^a-f0-9]*'),
  CHECK(length(anchor_sha256)=64 AND anchor_sha256 NOT GLOB '*[^a-f0-9]*'),
  CHECK((base_active_version IS NULL AND base_configuration_sha256 IS NULL AND base_anchor_sha256 IS NULL AND base_batch_id IS NULL)
    OR (typeof(base_active_version)='integer' AND base_active_version BETWEEN 1 AND 9007199254740991
      AND length(base_configuration_sha256)=64 AND base_configuration_sha256 NOT GLOB '*[^a-f0-9]*'
      AND length(base_anchor_sha256)=64 AND base_anchor_sha256 NOT GLOB '*[^a-f0-9]*')),
  CHECK((state='failed' AND error_code IS NOT NULL) OR (state!='failed' AND error_code IS NULL)))
;

CREATE TABLE text_runtime_selection(id INTEGER PRIMARY KEY CHECK(id=1),
  initialized INTEGER NOT NULL CHECK(initialized IN (0,1)),active_version INTEGER,
  configuration_sha256 TEXT,anchor_sha256 TEXT,batch_id TEXT,
  updated_at TEXT NOT NULL,
  FOREIGN KEY(batch_id) REFERENCES model_rebuilds(id) ON DELETE RESTRICT,
  CHECK((active_version IS NULL AND configuration_sha256 IS NULL AND anchor_sha256 IS NULL AND batch_id IS NULL)
    OR (initialized=1 AND typeof(active_version)='integer' AND active_version BETWEEN 1 AND 9007199254740991
      AND length(configuration_sha256)=64 AND configuration_sha256 NOT GLOB '*[^a-f0-9]*'
      AND length(anchor_sha256)=64 AND anchor_sha256 NOT GLOB '*[^a-f0-9]*')))
;

CREATE TABLE "video_compilations"(
  revision_id TEXT PRIMARY KEY NOT NULL REFERENCES corpus_revisions(id) ON DELETE RESTRICT,
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  decoder_revision TEXT NOT NULL CHECK(length(decoder_revision) BETWEEN 1 AND 200),
  compiler_revision TEXT NOT NULL CHECK(length(compiler_revision) BETWEEN 1 AND 200),
  timeline_origin_us INTEGER NOT NULL,duration_us INTEGER NOT NULL CHECK(duration_us BETWEEN 1 AND 600000000),
  frame_count INTEGER NOT NULL CHECK(frame_count BETWEEN 1 AND 128),
  span_count INTEGER NOT NULL CHECK(span_count BETWEEN 0 AND 600),
  group_count INTEGER NOT NULL CHECK(group_count BETWEEN 1 AND 77528),
  projection_count INTEGER NOT NULL CHECK(projection_count BETWEEN CASE WHEN compiler_revision LIKE 'java-video-compiler-v4:%' THEN 0 ELSE frame_count END AND CASE WHEN compiler_revision LIKE 'java-video-compiler-v4:%' THEN span_count ELSE frame_count+span_count END),
  frame_bytes INTEGER NOT NULL CHECK(frame_bytes BETWEEN 1 AND 33554432),
  manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  audio_model_revision TEXT CHECK(length(audio_model_revision) BETWEEN 1 AND 200),
  audio_transcription_revision TEXT CHECK(length(audio_transcription_revision) BETWEEN 1 AND 200),
  audio_sample_count INTEGER CHECK(audio_sample_count BETWEEN 1 AND 9600000),
  created_at TEXT NOT NULL,
  CHECK((span_count=0 AND audio_model_revision IS NULL AND audio_transcription_revision IS NULL AND audio_sample_count IS NULL)
    OR (span_count>0 AND audio_model_revision IS NOT NULL AND audio_transcription_revision IS NOT NULL AND audio_sample_count IS NOT NULL
      AND (audio_sample_count+15)/16<=(duration_us+999)/1000)),CHECK(compiler_revision NOT LIKE 'java-video-compiler-v4:%' OR (length(compiler_revision)=87 AND substr(compiler_revision,24) NOT GLOB '*[^a-f0-9]*')))
;

CREATE TABLE video_ocr_compilations(
  revision_id TEXT PRIMARY KEY NOT NULL REFERENCES video_compilations(revision_id) ON DELETE RESTRICT,
  ocr_revision TEXT NOT NULL CHECK(length(ocr_revision) BETWEEN 1 AND 200),
  frame_count INTEGER NOT NULL CHECK(frame_count BETWEEN 1 AND 128),
  projection_count INTEGER NOT NULL CHECK(projection_count BETWEEN 0 AND 4096),
  base_manifest_sha256 TEXT NOT NULL CHECK(length(base_manifest_sha256)=64 AND base_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),created_at TEXT NOT NULL)
;

CREATE TABLE video_subtitle_compilations(
  revision_id TEXT PRIMARY KEY NOT NULL REFERENCES video_compilations(revision_id) ON DELETE RESTRICT,
  video_epoch_pts INTEGER NOT NULL,video_time_base_numerator INTEGER NOT NULL CHECK(video_time_base_numerator>0),
  video_time_base_denominator INTEGER NOT NULL CHECK(video_time_base_denominator>0),
  track_count INTEGER NOT NULL CHECK(track_count BETWEEN 0 AND 4),
  cue_count INTEGER NOT NULL CHECK(cue_count BETWEEN 0 AND 2048),
  projection_count INTEGER NOT NULL CHECK(projection_count BETWEEN 0 AND cue_count),
  base_manifest_sha256 TEXT NOT NULL CHECK(length(base_manifest_sha256)=64 AND base_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  native_manifest_sha256 TEXT NOT NULL CHECK(length(native_manifest_sha256)=64 AND native_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  ocr_expected INTEGER NOT NULL CHECK(ocr_expected IN (0,1)),
  ocr_manifest_sha256 TEXT CHECK(ocr_manifest_sha256 IS NULL OR (length(ocr_manifest_sha256)=64 AND ocr_manifest_sha256 NOT GLOB '*[^a-f0-9]*')),
  created_at TEXT NOT NULL,CHECK(track_count>0 OR cue_count=0),
  CHECK((ocr_expected=0 AND ocr_manifest_sha256 IS NULL) OR (ocr_expected=1 AND ocr_manifest_sha256 IS NOT NULL)))
;

CREATE TABLE "video_transcript_spans"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL REFERENCES video_compilations(revision_id) ON DELETE RESTRICT,
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 599),
  start_ms INTEGER NOT NULL CHECK(start_ms>=0),end_ms INTEGER NOT NULL CHECK(end_ms>start_ms AND end_ms<=600000 AND end_ms-start_ms<=30000),
  text TEXT NOT NULL CHECK(payload_purged=1 OR (length(text)<=4096 AND length(CAST(text AS BLOB))<=16384)),
  text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
  index_ordinal INTEGER CHECK(index_ordinal BETWEEN 0 AND 599),
  UNIQUE(revision_id,id),UNIQUE(revision_id,ordinal),UNIQUE(revision_id,index_ordinal),
  CHECK(payload_purged=1 OR ((index_ordinal IS NULL AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))=0)
    OR (index_ordinal IS NOT NULL AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))>0))),CHECK(payload_purged=0 OR (text IS '')))
;

CREATE TABLE "video_subtitle_tracks"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL REFERENCES video_subtitle_compilations(revision_id) ON DELETE RESTRICT,
  stream_index INTEGER NOT NULL CHECK(stream_index BETWEEN 0 AND 2147483647),
  codec TEXT NOT NULL CHECK(codec IN ('mov_text','subrip','webvtt')),
  time_base_numerator INTEGER NOT NULL CHECK(time_base_numerator>0),time_base_denominator INTEGER NOT NULL CHECK(time_base_denominator>0),
  language TEXT CHECK(language IS NULL OR (length(language) BETWEEN 1 AND 63 AND instr(language,char(0))=0)),
  cue_count INTEGER NOT NULL CHECK(cue_count BETWEEN 0 AND 2048),
  text TEXT NOT NULL CHECK(payload_purged=1 OR (length(text)<=502047 AND instr(text,char(0))=0)),
  text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
  UNIQUE(revision_id,id),UNIQUE(revision_id,stream_index),CHECK(payload_purged=1 OR (cue_count>0 OR length(text)=0)),CHECK(payload_purged=0 OR (text IS '')))
;

CREATE TABLE "video_subtitle_cues"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL,track_id TEXT NOT NULL,
  stream_index INTEGER NOT NULL CHECK(stream_index BETWEEN 0 AND 2147483647),ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 2047),
  pts INTEGER NOT NULL,duration INTEGER NOT NULL CHECK(duration>=0),
  text TEXT NOT NULL CHECK(payload_purged=1 OR (length(text)<=4096 AND length(CAST(text AS BLOB))<=16384 AND instr(text,char(0))=0)),
  text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64 AND payload_sha256 NOT GLOB '*[^a-f0-9]*'),
  start_offset INTEGER NOT NULL CHECK(start_offset>=0),end_offset INTEGER NOT NULL CHECK(payload_purged=1 OR (end_offset>=start_offset AND end_offset-start_offset=length(text))),
  start_us INTEGER,end_us INTEGER,index_ordinal INTEGER CHECK(index_ordinal BETWEEN 0 AND 2047),
  UNIQUE(revision_id,id),UNIQUE(track_id,ordinal),UNIQUE(revision_id,index_ordinal),
  FOREIGN KEY(revision_id,track_id) REFERENCES video_subtitle_tracks(revision_id,id) ON DELETE RESTRICT,
  CHECK(payload_purged=1 OR ((index_ordinal IS NULL AND start_us IS NULL AND end_us IS NULL AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))=0)
    OR (index_ordinal IS NOT NULL AND start_us IS NOT NULL AND end_us IS NOT NULL AND start_us>=0 AND end_us>start_us AND end_us<=600000000 AND duration>0 AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))>0))),CHECK(payload_purged=0 OR (text IS '')))
;

CREATE TABLE "video_frames"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL REFERENCES video_compilations(revision_id) ON DELETE RESTRICT,
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 127),
  presentation_us INTEGER NOT NULL CHECK(presentation_us>=0),duration_us INTEGER NOT NULL CHECK(duration_us BETWEEN 1 AND 600000000),
  media_type TEXT NOT NULL CHECK(media_type IN ('image/png','image/jpeg')),
  frame_blob BLOB NOT NULL CHECK(payload_purged=1 OR (length(frame_blob) BETWEEN 1 AND 10485760)),
  frame_sha256 TEXT NOT NULL CHECK(length(frame_sha256)=64 AND frame_sha256 NOT GLOB '*[^a-f0-9]*'),
  width INTEGER NOT NULL CHECK(width>0),height INTEGER NOT NULL CHECK(height>0 AND width*height<=12000000),
  recall_text TEXT CHECK(payload_purged=1 OR (length(recall_text) BETWEEN 1 AND 4096 AND length(CAST(recall_text AS BLOB))<=16384)),
  recall_sha256 TEXT CHECK(length(recall_sha256)=64 AND recall_sha256 NOT GLOB '*[^a-f0-9]*'),
  description_revision TEXT CHECK(length(description_revision) BETWEEN 1 AND 128),
  UNIQUE(revision_id,id),UNIQUE(revision_id,ordinal),CHECK(payload_purged=0 OR (frame_blob IS x'' AND recall_text IS '')),CHECK((recall_text IS NULL AND recall_sha256 IS NULL AND description_revision IS NULL) OR (recall_text IS NOT NULL AND recall_sha256 IS NOT NULL AND description_revision IS NOT NULL)))
;

CREATE TABLE video_evidence_groups(
  id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL REFERENCES video_compilations(revision_id) ON DELETE RESTRICT,
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 77527),
  start_us INTEGER NOT NULL CHECK(start_us>=0),end_us INTEGER NOT NULL CHECK(end_us>start_us AND end_us<=600000000),
  frame_id TEXT,transcript_span_id TEXT,CHECK(frame_id IS NOT NULL OR transcript_span_id IS NOT NULL),
  UNIQUE(revision_id,id),UNIQUE(revision_id,ordinal),UNIQUE(revision_id,frame_id,transcript_span_id),
  FOREIGN KEY(revision_id,frame_id) REFERENCES video_frames(revision_id,id) ON DELETE RESTRICT,
  FOREIGN KEY(revision_id,transcript_span_id) REFERENCES video_transcript_spans(revision_id,id) ON DELETE RESTRICT)
;

CREATE TABLE "video_frame_ocr"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  revision_id TEXT NOT NULL REFERENCES video_ocr_compilations(revision_id) ON DELETE RESTRICT,
  frame_id TEXT PRIMARY KEY NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 127),
  frame_sha256 TEXT NOT NULL CHECK(length(frame_sha256)=64 AND frame_sha256 NOT GLOB '*[^a-f0-9]*'),
  width INTEGER NOT NULL CHECK(width>0),height INTEGER NOT NULL CHECK(height>0),
  text TEXT NOT NULL CHECK(payload_purged=1 OR (length(text)<=1500000 AND instr(text,char(0))=0)),
  text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
  segment_count INTEGER NOT NULL CHECK(segment_count BETWEEN 0 AND 4096),
  region_count INTEGER NOT NULL CHECK(region_count BETWEEN 0 AND 200000),
  CHECK(payload_purged=1 OR ((length(text)=0 AND segment_count=0 AND region_count=0) OR (length(text)>0 AND segment_count>0 AND region_count>0))),
  UNIQUE(revision_id,frame_id),UNIQUE(revision_id,ordinal),
  FOREIGN KEY(revision_id,frame_id) REFERENCES video_frames(revision_id,id) ON DELETE RESTRICT,CHECK(payload_purged=0 OR (text IS '')))
;

CREATE TABLE video_ocr_regions(
  revision_id TEXT NOT NULL,frame_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal>=0),
  start_offset INTEGER NOT NULL CHECK(start_offset>=0),end_offset INTEGER NOT NULL CHECK(end_offset>start_offset),
  left_pixel INTEGER NOT NULL CHECK(left_pixel>=0),top_pixel INTEGER NOT NULL CHECK(top_pixel>=0),
  right_pixel INTEGER NOT NULL CHECK(right_pixel>left_pixel),bottom_pixel INTEGER NOT NULL CHECK(bottom_pixel>top_pixel),
  PRIMARY KEY(frame_id,ordinal),
  FOREIGN KEY(revision_id,frame_id) REFERENCES video_frame_ocr(revision_id,frame_id) ON DELETE RESTRICT)
;

CREATE TABLE "video_ocr_segments"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL,frame_id TEXT NOT NULL,
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 4095),
  start_offset INTEGER NOT NULL CHECK(start_offset>=0),end_offset INTEGER NOT NULL,
  text TEXT NOT NULL CHECK(payload_purged=1 OR (length(text) BETWEEN 1 AND 4096 AND length(CAST(text AS BLOB))<=16384 AND instr(text,char(0))=0)),
  text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
  CHECK(payload_purged=1 OR (end_offset>start_offset AND end_offset-start_offset=length(text))),
  UNIQUE(frame_id,ordinal),UNIQUE(revision_id,id),
  FOREIGN KEY(revision_id,frame_id) REFERENCES video_frame_ocr(revision_id,frame_id) ON DELETE RESTRICT,CHECK(payload_purged=0 OR (text IS '')))
;

CREATE TABLE "knowledge_answer_traces"(
  id TEXT PRIMARY KEY NOT NULL,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
  selection_all INTEGER NOT NULL CHECK(selection_all IN (0,1)),
  scope_count INTEGER NOT NULL CHECK(scope_count>=0),
  citation_count INTEGER NOT NULL CHECK(citation_count>=0),
  question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
  answer_sha256 TEXT CHECK(answer_sha256 IS NULL OR (length(answer_sha256)=64 AND answer_sha256 NOT GLOB '*[^a-f0-9]*')),
  outcome TEXT NOT NULL CHECK(outcome IN ('answered','abstained')),reason_code TEXT,
  model_revision TEXT NOT NULL,prompt_revision TEXT NOT NULL,policy_revision TEXT NOT NULL,created_at TEXT NOT NULL,
  CHECK((outcome='answered' AND answer_sha256 IS NOT NULL AND reason_code IS NULL AND citation_count>0)
    OR (outcome='abstained' AND answer_sha256 IS NULL AND reason_code IS NOT NULL AND citation_count=0)))
;

CREATE TABLE "query_traces"(
  id TEXT PRIMARY KEY NOT NULL,
  workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
  selection_all INTEGER NOT NULL CHECK(selection_all IN (0,1)),
  scope_count INTEGER NOT NULL CHECK(scope_count>=0),
  citation_count INTEGER NOT NULL CHECK(citation_count BETWEEN 0 AND 32),
  question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
  answer_sha256 TEXT CHECK(answer_sha256 IS NULL OR (length(answer_sha256)=64 AND answer_sha256 NOT GLOB '*[^a-f0-9]*')),
  outcome TEXT NOT NULL CHECK(outcome IN ('answered','abstained')),
  reason_code TEXT,model_revision TEXT NOT NULL,prompt_revision TEXT NOT NULL,
  policy_revision TEXT NOT NULL,created_at TEXT NOT NULL,
  CHECK((outcome='answered' AND answer_sha256 IS NOT NULL AND reason_code IS NULL AND citation_count>0)
    OR (outcome='abstained' AND answer_sha256 IS NULL AND reason_code IS NOT NULL AND length(reason_code) BETWEEN 1 AND 64 AND citation_count=0)))
;

CREATE TABLE query_trace_preparations(
  trace_id TEXT PRIMARY KEY NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
  preparation_revision TEXT NOT NULL CHECK(length(preparation_revision) BETWEEN 1 AND 200),
  ranking_revision TEXT CHECK(ranking_revision IS NULL OR length(ranking_revision) BETWEEN 1 AND 200),
  status TEXT NOT NULL CHECK(status IN ('prepared','failed')),
  reason_code TEXT,
  manifest_sha256 TEXT CHECK(manifest_sha256 IS NULL OR (length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*')),
  attachment_count INTEGER NOT NULL CHECK(attachment_count BETWEEN 1 AND 3),
  CHECK((status='prepared' AND reason_code IS NULL AND manifest_sha256 IS NOT NULL)
    OR (status='failed' AND manifest_sha256 IS NULL AND reason_code IS NOT NULL
      AND length(reason_code) BETWEEN 1 AND 64 AND reason_code GLOB '[a-z]*' AND reason_code NOT GLOB '*[^a-z0-9_]*')))
;

CREATE TABLE query_trace_attachments(
  trace_id TEXT NOT NULL REFERENCES query_trace_preparations(trace_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 2),
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  media_kind TEXT NOT NULL CHECK(media_kind IN ('IMAGE','AUDIO','VIDEO')),
  compiler_revision TEXT CHECK(compiler_revision IS NULL OR length(compiler_revision) BETWEEN 1 AND 200),
  content_sha256 TEXT CHECK(content_sha256 IS NULL OR (length(content_sha256)=64 AND content_sha256 NOT GLOB '*[^a-f0-9]*')),
  text_code_points INTEGER CHECK(text_code_points IS NULL OR text_code_points BETWEEN 0 AND 8192),
  visual_count INTEGER CHECK(visual_count IS NULL OR visual_count BETWEEN 0 AND 128),
  selected_image_count INTEGER NOT NULL CHECK(selected_image_count BETWEEN 0 AND 3),
  visual_sampled INTEGER CHECK(visual_sampled IS NULL OR visual_sampled IN (0,1)),
  PRIMARY KEY(trace_id,ordinal),
  CHECK((compiler_revision IS NULL AND content_sha256 IS NULL AND text_code_points IS NULL
      AND visual_count IS NULL AND visual_sampled IS NULL AND selected_image_count=0)
    OR (compiler_revision IS NOT NULL AND content_sha256 IS NOT NULL AND text_code_points IS NOT NULL
      AND visual_count IS NOT NULL AND visual_sampled IS NOT NULL AND selected_image_count<=visual_count
      AND ((media_kind='IMAGE' AND visual_count=1) OR (media_kind='AUDIO' AND visual_count=0)
        OR (media_kind='VIDEO' AND visual_count>0))
      AND (visual_sampled=0 OR selected_image_count<visual_count)
      AND (visual_sampled=1 OR visual_count=0 OR selected_image_count>0))))
;

CREATE TABLE query_trace_attachment_images(
  trace_id TEXT NOT NULL,
  attachment_ordinal INTEGER NOT NULL CHECK(attachment_ordinal BETWEEN 0 AND 2),
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 2),
  image_sha256 TEXT NOT NULL CHECK(length(image_sha256)=64 AND image_sha256 NOT GLOB '*[^a-f0-9]*'),
  PRIMARY KEY(trace_id,attachment_ordinal,ordinal),UNIQUE(trace_id,attachment_ordinal,image_sha256),
  FOREIGN KEY(trace_id,attachment_ordinal) REFERENCES query_trace_attachments(trace_id,ordinal) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED)
;

CREATE TABLE "sound_traces"(
  id TEXT PRIMARY KEY NOT NULL,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
  selection_all INTEGER NOT NULL CHECK(selection_all IN (0,1)),scope_count INTEGER NOT NULL CHECK(scope_count>=0),
  citation_count INTEGER NOT NULL CHECK(citation_count BETWEEN 0 AND 32),
  question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
  answer_sha256 TEXT CHECK(answer_sha256 IS NULL OR (length(answer_sha256)=64 AND answer_sha256 NOT GLOB '*[^a-f0-9]*')),
  status TEXT NOT NULL CHECK(status IN ('answered','abstained')),reason_code TEXT,model_revision TEXT NOT NULL,
  policy_revision TEXT NOT NULL CHECK(policy_revision='java-sound-answer-v1'),created_at TEXT NOT NULL,
  CHECK((status='answered' AND answer_sha256 IS NOT NULL AND reason_code IS NULL AND citation_count>0)
    OR (status='abstained' AND answer_sha256 IS NULL AND reason_code IS NOT NULL AND length(reason_code)>0 AND citation_count=0)))
;

CREATE TABLE "sound_trace_evidence"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  trace_id TEXT NOT NULL REFERENCES sound_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 1 AND 32),publication_id TEXT NOT NULL,span_id TEXT NOT NULL,
  facts_json TEXT NOT NULL CHECK(payload_purged=1 OR (json_valid(facts_json) AND json_type(facts_json)='array' AND json_array_length(facts_json) BETWEEN 1 AND 16 AND length(CAST(facts_json AS BLOB))<=32768)),
  facts_sha256 TEXT NOT NULL CHECK(length(facts_sha256)=64 AND facts_sha256 NOT GLOB '*[^a-f0-9]*'),
  proof_sha256 TEXT NOT NULL CHECK(length(proof_sha256)=64 AND proof_sha256 NOT GLOB '*[^a-f0-9]*'),
  PRIMARY KEY(trace_id,ordinal),FOREIGN KEY(publication_id,span_id) REFERENCES sound_spans(publication_id,id) ON DELETE RESTRICT,CHECK(payload_purged=0 OR (facts_json IS '[]')))
;

CREATE TABLE "video_av_traces"(
  id TEXT PRIMARY KEY NOT NULL,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
  selection_all INTEGER NOT NULL CHECK(selection_all IN (0,1)),scope_count INTEGER NOT NULL CHECK(scope_count>=0),
  citation_count INTEGER NOT NULL CHECK(citation_count BETWEEN 0 AND 32),mode TEXT NOT NULL CHECK(mode IN('VISUAL','AUDIO','JOINT')),
  question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
  answer_sha256 TEXT CHECK(answer_sha256 IS NULL OR (length(answer_sha256)=64 AND answer_sha256 NOT GLOB '*[^a-f0-9]*')),
  status TEXT NOT NULL CHECK(status IN ('answered','abstained')),reason_code TEXT,model_revision TEXT NOT NULL,
  policy_revision TEXT NOT NULL CHECK(policy_revision='java-video-av-answer-v1'),created_at TEXT NOT NULL,
  CHECK((status='answered' AND answer_sha256 IS NOT NULL AND reason_code IS NULL AND citation_count>0)
    OR (status='abstained' AND answer_sha256 IS NULL AND reason_code IS NOT NULL AND length(reason_code)>0 AND citation_count=0)))
;

CREATE TABLE video_av_query_preparations(
  trace_id TEXT PRIMARY KEY NOT NULL REFERENCES video_av_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  attachment_count INTEGER NOT NULL CHECK(typeof(attachment_count)='integer' AND attachment_count BETWEEN 1 AND 3),
  mode TEXT NOT NULL CHECK(mode IN ('VISUAL','AUDIO','JOINT')),
  question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
  profile_fingerprint TEXT NOT NULL CHECK(length(profile_fingerprint)=64 AND profile_fingerprint NOT GLOB '*[^a-f0-9]*'),
  embedding_revision TEXT NOT NULL CHECK(length(embedding_revision) BETWEEN 1 AND 200),
  preparation_revision TEXT NOT NULL CHECK(preparation_revision='java-video-av-query-preparation-v1'),
  manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'))
;

CREATE TABLE video_av_query_attachments(
  trace_id TEXT NOT NULL REFERENCES video_av_query_preparations(trace_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal BETWEEN 0 AND 2),
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  media_kind TEXT NOT NULL CHECK(media_kind='video'),
  compiler_revision TEXT NOT NULL CHECK(length(compiler_revision) BETWEEN 1 AND 200),
  content_sha256 TEXT,window_count INTEGER,visual_window_count INTEGER,audio_window_count INTEGER,audio_present INTEGER,
  used_mode TEXT NOT NULL CHECK(used_mode IN ('VISUAL','AUDIO','JOINT')),
  status TEXT NOT NULL CHECK(status IN ('prepared','not_prepared')),
  PRIMARY KEY(trace_id,ordinal),
  CHECK((status='not_prepared' AND content_sha256 IS NULL AND window_count IS NULL
    AND visual_window_count IS NULL AND audio_window_count IS NULL AND audio_present IS NULL)
    OR (status='prepared' AND content_sha256 IS NOT NULL AND length(content_sha256)=64
    AND content_sha256 NOT GLOB '*[^a-f0-9]*' AND window_count IS NOT NULL AND typeof(window_count)='integer' AND window_count BETWEEN 1 AND 1201
    AND visual_window_count IS NOT NULL AND typeof(visual_window_count)='integer' AND visual_window_count BETWEEN 1 AND window_count
    AND audio_window_count IS NOT NULL AND typeof(audio_window_count)='integer' AND audio_window_count BETWEEN 0 AND window_count
    AND visual_window_count+audio_window_count>=window_count
    AND audio_present IS NOT NULL AND typeof(audio_present)='integer' AND audio_present IN (0,1) AND audio_present=(audio_window_count>0)
    AND (used_mode='VISUAL' OR audio_window_count>0))))
;

CREATE TABLE "video_av_trace_evidence"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  trace_id TEXT NOT NULL REFERENCES video_av_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 1 AND 32),publication_id TEXT NOT NULL,window_id TEXT NOT NULL,
  facts_json TEXT NOT NULL CHECK(payload_purged=1 OR (json_valid(facts_json) AND json_type(facts_json)='array' AND json_array_length(facts_json) BETWEEN 1 AND 16 AND length(CAST(facts_json AS BLOB))<=65536)),
  facts_sha256 TEXT NOT NULL CHECK(length(facts_sha256)=64 AND facts_sha256 NOT GLOB '*[^a-f0-9]*'),
  proof_sha256 TEXT NOT NULL CHECK(length(proof_sha256)=64 AND proof_sha256 NOT GLOB '*[^a-f0-9]*'),
  PRIMARY KEY(trace_id,ordinal),FOREIGN KEY(publication_id,window_id) REFERENCES video_av_windows(publication_id,id) ON DELETE RESTRICT,CHECK(payload_purged=0 OR (facts_json IS '[]')))
;

CREATE TABLE "knowledge_answer_documents"(
  trace_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal>=0),publication_id TEXT NOT NULL,
  PRIMARY KEY(trace_id,ordinal),UNIQUE(trace_id,publication_id),
  FOREIGN KEY(trace_id) REFERENCES knowledge_answer_traces(id) DEFERRABLE INITIALLY DEFERRED)
;

CREATE TABLE "sound_trace_documents"(
  trace_id TEXT NOT NULL REFERENCES sound_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  ordinal INTEGER NOT NULL CHECK(ordinal>=0),publication_id TEXT NOT NULL REFERENCES sound_publications(id) ON DELETE RESTRICT,
  PRIMARY KEY(trace_id,ordinal),UNIQUE(trace_id,publication_id))
;

CREATE TABLE "video_av_trace_documents"(
  trace_id TEXT NOT NULL REFERENCES video_av_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  ordinal INTEGER NOT NULL CHECK(ordinal>=0),publication_id TEXT NOT NULL REFERENCES video_av_publications(id) ON DELETE RESTRICT,
  PRIMARY KEY(trace_id,ordinal),UNIQUE(trace_id,publication_id))
;

CREATE TABLE "knowledge_answer_citations"(
  trace_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal>=1),publication_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('DOCUMENT_TEXT','VIDEO_TRANSCRIPT','VIDEO_SUBTITLE','VIDEO_FRAME_OCR')),
  physical_id TEXT NOT NULL,start_offset INTEGER NOT NULL CHECK(start_offset>=0),
  end_offset INTEGER NOT NULL CHECK(end_offset>start_offset),
  context_sha256 TEXT NOT NULL CHECK(length(context_sha256)=64 AND context_sha256 NOT GLOB '*[^a-f0-9]*'),
  quote_sha256 TEXT NOT NULL CHECK(length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*'),
  page_number INTEGER,start_us INTEGER,end_us INTEGER,
  PRIMARY KEY(trace_id,ordinal),
  FOREIGN KEY(trace_id,publication_id) REFERENCES knowledge_answer_documents(trace_id,publication_id) ON DELETE RESTRICT,
  CHECK((kind='DOCUMENT_TEXT' AND page_number BETWEEN 1 AND 500 AND page_number IS NOT NULL AND start_us IS NULL AND end_us IS NULL)
    OR (kind!='DOCUMENT_TEXT' AND page_number IS NULL AND start_us IS NOT NULL AND end_us IS NOT NULL AND start_us>=0 AND end_us>start_us AND end_us<=600000000)))
;

CREATE TABLE wiki_page_revisions(
  workspace_id TEXT NOT NULL,page_id TEXT NOT NULL,
  version INTEGER NOT NULL CHECK(typeof(version)='integer' AND version>=1),
  content_json TEXT NOT NULL CHECK(json_valid(content_json)),
  model_revision TEXT NOT NULL,policy_revision TEXT NOT NULL,
  created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
  PRIMARY KEY(workspace_id,page_id,version))
;

CREATE TABLE wiki_proposals(
  workspace_id TEXT NOT NULL,id TEXT NOT NULL,page_id TEXT NOT NULL,
  base_version INTEGER NOT NULL CHECK(typeof(base_version)='integer' AND base_version>=0),
  before_json TEXT CHECK(before_json IS NULL OR json_valid(before_json)),
  after_json TEXT NOT NULL CHECK(json_valid(after_json)),
  generation_method TEXT NOT NULL CHECK(generation_method IN ('extractive','model')),
  model_revision TEXT NOT NULL,policy_revision TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('pending','accepted','dismissed')),
  created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
  reviewed_at INTEGER CHECK(reviewed_at IS NULL OR (typeof(reviewed_at)='integer' AND reviewed_at>=created_at)),
  PRIMARY KEY(workspace_id,id),
  CHECK((base_version=0 AND before_json IS NULL) OR (base_version>0 AND before_json IS NOT NULL)),
  CHECK((status='pending' AND reviewed_at IS NULL) OR (status!='pending' AND reviewed_at IS NOT NULL)))
;

CREATE TABLE wiki_drafts(
  workspace_id TEXT NOT NULL,id TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,
  version INTEGER NOT NULL CHECK(typeof(version)='integer' AND version>=1),
  created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
  updated_at INTEGER NOT NULL CHECK(typeof(updated_at)='integer' AND updated_at>=created_at),
  PRIMARY KEY(workspace_id,id))
;

CREATE TABLE wiki_page_lifecycle(
  workspace_id TEXT NOT NULL,page_id TEXT NOT NULL,
  lifecycle_version INTEGER NOT NULL CHECK(typeof(lifecycle_version)='integer' AND lifecycle_version BETWEEN 1 AND 9007199254740991),
  state TEXT NOT NULL CHECK(state IN ('active','deleted')),
  content_version INTEGER NOT NULL CHECK(typeof(content_version)='integer' AND content_version>=1),
  actor_id TEXT NOT NULL,created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
  PRIMARY KEY(workspace_id,page_id,lifecycle_version),
  FOREIGN KEY(workspace_id,page_id,content_version) REFERENCES wiki_page_revisions(workspace_id,page_id,version))
;

CREATE TABLE import_index_requests(
  revision_id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id),
  workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
  pipeline TEXT NOT NULL CHECK(pipeline IN ('corpus','sound','video_av')),
  replacement_id TEXT,base_revision_id TEXT,
  state TEXT NOT NULL CHECK(state IN ('pending','dispatching','submitted','failed')),
  error_code TEXT,task_id TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
  CHECK((replacement_id IS NULL)=(base_revision_id IS NULL)))
;

CREATE TABLE wiki_page_purges(
  workspace_id TEXT NOT NULL,page_id TEXT NOT NULL,
  content_version INTEGER NOT NULL CHECK(typeof(content_version)='integer' AND content_version>=1),
  lifecycle_version INTEGER NOT NULL CHECK(typeof(lifecycle_version)='integer' AND lifecycle_version>=1),
  actor_id TEXT NOT NULL,created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
  PRIMARY KEY(workspace_id,page_id))
;

CREATE TABLE knowledge_conversations(
  id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
  title TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,
  generation INTEGER NOT NULL DEFAULT 0,turn_count INTEGER NOT NULL DEFAULT 0,
  active_run_id TEXT,summary TEXT NOT NULL DEFAULT '',covered_turn_count INTEGER NOT NULL DEFAULT 0,
  compression_status TEXT NOT NULL DEFAULT 'idle'
    CHECK(compression_status IN ('idle','compressing','compressed','failed')))
;

CREATE TABLE knowledge_conversation_turns(
  conversation_id TEXT NOT NULL REFERENCES knowledge_conversations(id),
  ordinal INTEGER NOT NULL,run_id TEXT NOT NULL UNIQUE,request_id TEXT NOT NULL,
  question TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('running','completed','failed','cancelled')),
  result_json TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,
  PRIMARY KEY(conversation_id,ordinal),UNIQUE(conversation_id,request_id))
;

CREATE TABLE document_import_batches(
  id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
  request_id TEXT NOT NULL,manifest_sha256 TEXT NOT NULL,created_at TEXT NOT NULL,
  request_limit INTEGER NOT NULL CHECK(request_limit>0),
  reserved_requests INTEGER NOT NULL DEFAULT 0 CHECK(reserved_requests>=0 AND reserved_requests<=request_limit),
  halted_reason TEXT,UNIQUE(workspace_id,actor_id,request_id))
;

CREATE TABLE document_import_files(
  batch_id TEXT NOT NULL REFERENCES document_import_batches(id),ordinal INTEGER NOT NULL CHECK(ordinal>=0),
  filename TEXT NOT NULL,size_bytes INTEGER NOT NULL CHECK(size_bytes>0),source_sha256 TEXT NOT NULL,
  document_id TEXT UNIQUE REFERENCES documents(id),revision_id TEXT,
  ingestion_task_id TEXT REFERENCES ingestion_jobs(id),PRIMARY KEY(batch_id,ordinal))
;

CREATE TABLE batch_task_requeues(
  task_kind TEXT NOT NULL CHECK(task_kind IN ('ingestion','indexing')),
  job_id TEXT NOT NULL,attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 3),
  reason TEXT NOT NULL CHECK(reason IN ('restart','resume')),created_at TEXT NOT NULL,
  PRIMARY KEY(task_kind,job_id,attempt))
;

CREATE TABLE document_import_file_states(
  batch_id TEXT NOT NULL,ordinal INTEGER NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('awaiting_upload','queued','parsing','parsed','indexing','indexed','failed','cancelled','deleted','duplicate')),
  duplicate_document_id TEXT,duplicate_ordinal INTEGER CHECK(duplicate_ordinal>=0),
  PRIMARY KEY(batch_id,ordinal),
  FOREIGN KEY(batch_id,ordinal) REFERENCES document_import_files(batch_id,ordinal),
  CHECK((state='duplicate')=(duplicate_document_id IS NOT NULL OR duplicate_ordinal IS NOT NULL)))
;

CREATE TABLE document_import_file_paths(
  batch_id TEXT NOT NULL,ordinal INTEGER NOT NULL,
  relative_path TEXT NOT NULL CHECK(length(relative_path) BETWEEN 1 AND 1024),
  PRIMARY KEY(batch_id,ordinal),
  FOREIGN KEY(batch_id,ordinal) REFERENCES document_import_files(batch_id,ordinal))
;

CREATE TABLE document_import_file_times(
  batch_id TEXT NOT NULL,ordinal INTEGER NOT NULL,
  changed_at TEXT NOT NULL,finished_at TEXT,
  PRIMARY KEY(batch_id,ordinal),
  FOREIGN KEY(batch_id,ordinal) REFERENCES document_import_files(batch_id,ordinal))
;

CREATE TABLE document_import_batch_times(
  batch_id TEXT PRIMARY KEY REFERENCES document_import_batches(id),updated_at TEXT NOT NULL)
;

CREATE TABLE document_import_batch_streams(
  batch_id TEXT PRIMARY KEY REFERENCES document_import_batches(id),
  expected_total INTEGER NOT NULL CHECK(expected_total BETWEEN 1 AND 10000),
  include_duplicates INTEGER NOT NULL CHECK(include_duplicates IN (0,1)),
  sealed_at TEXT)
;

CREATE TABLE document_import_batch_placement(
  batch_id TEXT PRIMARY KEY REFERENCES document_import_batches(id),
  target_folder_id TEXT,
  target_folder_name TEXT,
  preserve_paths INTEGER NOT NULL CHECK(preserve_paths IN (0,1)),
  CHECK((target_folder_id IS NULL AND target_folder_name IS NULL)
    OR (target_folder_id IS NOT NULL AND length(target_folder_name) BETWEEN 1 AND 80)))
;

CREATE TABLE indexing_attempts(job_id TEXT NOT NULL REFERENCES indexing_jobs(id) ON DELETE RESTRICT,attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 3),projection_generation_id TEXT NOT NULL UNIQUE CHECK(length(projection_generation_id)=36),started_at TEXT NOT NULL,PRIMARY KEY(job_id,attempt),UNIQUE(job_id,attempt,projection_generation_id))
;

CREATE TABLE index_publications(id TEXT PRIMARY KEY,job_id TEXT NOT NULL UNIQUE,document_id TEXT NOT NULL,revision_id TEXT NOT NULL,attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 3),projection_generation_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,parser_revision TEXT NOT NULL,embedding_identity TEXT NOT NULL,projection_identity TEXT NOT NULL,model_revision TEXT NOT NULL,dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 2 AND 8192),manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64),segment_count INTEGER NOT NULL CHECK(segment_count BETWEEN 1 AND 4096),created_at TEXT NOT NULL,FOREIGN KEY(job_id,document_id,revision_id) REFERENCES indexing_jobs(id,document_id,revision_id) ON DELETE RESTRICT,FOREIGN KEY(job_id,attempt,projection_generation_id) REFERENCES indexing_attempts(job_id,attempt,projection_generation_id) ON DELETE RESTRICT,UNIQUE(id,document_id,revision_id))
;

CREATE TABLE index_publication_entries(publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,source_segment_id TEXT NOT NULL REFERENCES corpus_segments(id) ON DELETE RESTRICT,physical_segment_id TEXT NOT NULL UNIQUE,entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64),PRIMARY KEY(publication_id,source_segment_id))
;

CREATE TABLE active_corpus_publications(document_id TEXT PRIMARY KEY REFERENCES corpus_documents(document_id) ON DELETE RESTRICT,publication_id TEXT NOT NULL UNIQUE,revision_id TEXT NOT NULL,FOREIGN KEY(publication_id,document_id,revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT)
;

CREATE TABLE image_publication_entries(
  publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
  image_evidence_id TEXT NOT NULL REFERENCES image_evidence(id) ON DELETE RESTRICT,
  physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
  entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
  PRIMARY KEY(publication_id,image_evidence_id))
;

CREATE TABLE audio_publication_entries(
  publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
  audio_span_id TEXT NOT NULL REFERENCES audio_spans(id) ON DELETE RESTRICT,
  physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
  entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
  PRIMARY KEY(publication_id,audio_span_id))
;

CREATE TABLE video_frame_publication_entries(publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,video_frame_id TEXT NOT NULL REFERENCES video_frames(id) ON DELETE RESTRICT,physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id) BETWEEN 1 AND 128),entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),PRIMARY KEY(publication_id,video_frame_id))
;

CREATE TABLE video_transcript_publication_entries(publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,video_transcript_span_id TEXT NOT NULL REFERENCES video_transcript_spans(id) ON DELETE RESTRICT,physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id) BETWEEN 1 AND 128),entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),PRIMARY KEY(publication_id,video_transcript_span_id))
;

CREATE TABLE video_ocr_publication_entries(
  publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
  video_ocr_segment_id TEXT NOT NULL REFERENCES video_ocr_segments(id) ON DELETE RESTRICT,
  physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
  entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
  PRIMARY KEY(publication_id,video_ocr_segment_id))
;

CREATE TABLE video_subtitle_publication_entries(
  publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
  video_subtitle_cue_id TEXT NOT NULL REFERENCES video_subtitle_cues(id) ON DELETE RESTRICT,
  physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
  entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
  PRIMARY KEY(publication_id,video_subtitle_cue_id))
;

CREATE TABLE "synopsis_tasks"(
  id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 128),
  workspace_id TEXT NOT NULL,document_id TEXT NOT NULL,revision_id TEXT NOT NULL,publication_id TEXT NOT NULL,
  model_revision TEXT NOT NULL CHECK(length(model_revision) BETWEEN 1 AND 200),
  policy_revision TEXT NOT NULL CHECK(length(policy_revision) BETWEEN 1 AND 200),
  state TEXT NOT NULL CHECK(state IN ('queued','processing','available','unavailable','cancelled')),
  claim_token_sha256 TEXT CHECK(claim_token_sha256 IS NULL OR (length(claim_token_sha256)=64 AND claim_token_sha256 NOT GLOB '*[^a-f0-9]*')),
  input_fingerprint TEXT CHECK(input_fingerprint IS NULL OR (length(input_fingerprint)=64 AND input_fingerprint NOT GLOB '*[^a-f0-9]*')),
  input_count INTEGER NOT NULL DEFAULT 0 CHECK(input_count BETWEEN 0 AND 4096),
  entry_count INTEGER NOT NULL DEFAULT 0 CHECK(entry_count BETWEEN 0 AND 32),
  created_by TEXT NOT NULL,error_code TEXT CHECK(error_code IS NULL OR (length(error_code) BETWEEN 1 AND 80 AND error_code NOT GLOB '*[^a-z0-9_]*')),
  created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(id,publication_id),
  FOREIGN KEY(publication_id,document_id,revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT,
  CHECK((state='processing' AND claim_token_sha256 IS NOT NULL) OR (state!='processing' AND claim_token_sha256 IS NULL)),
  CHECK((state IN ('unavailable','cancelled') AND error_code IS NOT NULL) OR (state NOT IN ('unavailable','cancelled') AND error_code IS NULL)),
  CHECK((state='available' AND entry_count BETWEEN 3 AND 32) OR (state!='available' AND entry_count=0)),
  CHECK((input_count=0 AND input_fingerprint IS NULL) OR (input_count>0 AND input_fingerprint IS NOT NULL)))
;

CREATE TABLE "synopsis_input_evidence"(
  task_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 4095),publication_id TEXT NOT NULL,
  physical_segment_id TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('TEXT','IMAGE_OCR','IMAGE','AUDIO_TRANSCRIPT','VIDEO_FRAME','VIDEO_TRANSCRIPT','VIDEO_OCR','VIDEO_SUBTITLE')),
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64 AND content_sha256 NOT GLOB '*[^a-f0-9]*'),
  start_us INTEGER,end_us INTEGER,
  source_segment_id TEXT,image_evidence_id TEXT,audio_span_id TEXT,video_frame_id TEXT,video_transcript_span_id TEXT,video_ocr_segment_id TEXT,video_subtitle_cue_id TEXT,
  PRIMARY KEY(task_id,ordinal),UNIQUE(task_id,physical_segment_id),
  FOREIGN KEY(task_id,publication_id) REFERENCES "synopsis_tasks"(id,publication_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,source_segment_id) REFERENCES index_publication_entries(publication_id,source_segment_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,image_evidence_id) REFERENCES image_publication_entries(publication_id,image_evidence_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,audio_span_id) REFERENCES audio_publication_entries(publication_id,audio_span_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,video_frame_id) REFERENCES video_frame_publication_entries(publication_id,video_frame_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,video_transcript_span_id) REFERENCES video_transcript_publication_entries(publication_id,video_transcript_span_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,video_ocr_segment_id) REFERENCES video_ocr_publication_entries(publication_id,video_ocr_segment_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,video_subtitle_cue_id) REFERENCES video_subtitle_publication_entries(publication_id,video_subtitle_cue_id) ON DELETE RESTRICT, CHECK((kind IN ('TEXT','IMAGE_OCR','IMAGE') AND start_us IS NULL AND end_us IS NULL)
    OR (kind IN ('AUDIO_TRANSCRIPT','VIDEO_FRAME','VIDEO_TRANSCRIPT','VIDEO_OCR','VIDEO_SUBTITLE') AND start_us IS NOT NULL AND end_us IS NOT NULL AND start_us>=0 AND end_us>start_us)),
  CHECK((source_segment_id IS NOT NULL)+(image_evidence_id IS NOT NULL)+(audio_span_id IS NOT NULL)+(video_frame_id IS NOT NULL)+(video_transcript_span_id IS NOT NULL)+(video_ocr_segment_id IS NOT NULL)+(video_subtitle_cue_id IS NOT NULL)=1))
;

CREATE TABLE image_vector_publications(
  id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 128),
  publication_id TEXT NOT NULL,
  document_id TEXT NOT NULL,
  source_revision_id TEXT NOT NULL,
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  image_evidence_id TEXT NOT NULL,
  base_physical_segment_id TEXT NOT NULL CHECK(length(base_physical_segment_id) BETWEEN 1 AND 128),
  vector_generation_id TEXT NOT NULL CHECK(length(vector_generation_id)=36),
  vector_physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(vector_physical_segment_id) BETWEEN 1 AND 128),
  embedding_identity TEXT NOT NULL CHECK(length(embedding_identity) BETWEEN 1 AND 128),
  projection_identity TEXT NOT NULL CHECK(length(projection_identity)=64 AND projection_identity NOT GLOB '*[^a-f0-9]*'),
  model_revision TEXT NOT NULL CHECK(length(model_revision) BETWEEN 1 AND 160),
  dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 2 AND 8192),
  entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
  manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  created_at TEXT NOT NULL,
  FOREIGN KEY(publication_id,document_id,source_revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,image_evidence_id) REFERENCES image_publication_entries(publication_id,image_evidence_id) ON DELETE RESTRICT,
  UNIQUE(publication_id,embedding_identity,projection_identity,model_revision,dimensions))
;

CREATE TABLE audio_vector_publications(
  id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 128),
  publication_id TEXT NOT NULL,
  document_id TEXT NOT NULL,
  source_revision_id TEXT NOT NULL,
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  vector_generation_id TEXT NOT NULL CHECK(length(vector_generation_id)=36),
  embedding_identity TEXT NOT NULL CHECK(length(embedding_identity) BETWEEN 1 AND 128),
  projection_identity TEXT NOT NULL CHECK(length(projection_identity)=64 AND projection_identity NOT GLOB '*[^a-f0-9]*'),
  model_revision TEXT NOT NULL CHECK(length(model_revision) BETWEEN 1 AND 160),
  dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 2 AND 3072),
  decoder_revision TEXT NOT NULL CHECK(length(decoder_revision) BETWEEN 1 AND 200),
  manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  segment_count INTEGER NOT NULL CHECK(segment_count BETWEEN 1 AND 600),
  created_at TEXT NOT NULL,
  FOREIGN KEY(publication_id,document_id,source_revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT,
  UNIQUE(publication_id,embedding_identity,projection_identity,model_revision,dimensions,decoder_revision))
;

CREATE TABLE audio_vector_entries(
  audio_vector_publication_id TEXT NOT NULL REFERENCES audio_vector_publications(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  audio_evidence_id TEXT NOT NULL REFERENCES audio_spans(id) ON DELETE RESTRICT,
  base_physical_segment_id TEXT NOT NULL CHECK(length(base_physical_segment_id) BETWEEN 1 AND 128),
  vector_physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(vector_physical_segment_id) BETWEEN 1 AND 128),
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 599),
  start_sample INTEGER NOT NULL CHECK(start_sample>=0),
  end_sample INTEGER NOT NULL CHECK(end_sample>start_sample AND end_sample<=9600000 AND end_sample-start_sample<=480000),
  pcm_sha256 TEXT NOT NULL CHECK(length(pcm_sha256)=64 AND pcm_sha256 NOT GLOB '*[^a-f0-9]*'),
  entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
  CHECK(base_physical_segment_id!=vector_physical_segment_id),
  PRIMARY KEY(audio_vector_publication_id,audio_evidence_id),
  UNIQUE(audio_vector_publication_id,ordinal),UNIQUE(audio_vector_publication_id,base_physical_segment_id))
;

CREATE TABLE "synopsis_entries"(payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),
  task_id TEXT NOT NULL REFERENCES "synopsis_tasks"(id) ON DELETE RESTRICT,
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 31),
  section TEXT NOT NULL CHECK(section IN ('OVERVIEW','TOPIC','TERM','TIMELINE')),
  text TEXT NOT NULL CHECK(payload_purged=1 OR (length(text) BETWEEN 1 AND 1024 AND instr(text,char(0))=0)),
  start_us INTEGER,end_us INTEGER,reference_count INTEGER NOT NULL CHECK(reference_count BETWEEN 1 AND 8),
  PRIMARY KEY(task_id,ordinal),
  CHECK((section='TIMELINE' AND start_us IS NOT NULL AND end_us IS NOT NULL AND start_us>=0 AND end_us>start_us)
    OR (section!='TIMELINE' AND start_us IS NULL AND end_us IS NULL)),CHECK(payload_purged=0 OR (text IS '')))
;

CREATE TABLE "synopsis_references"(
  task_id TEXT NOT NULL,entry_ordinal INTEGER NOT NULL,source_ordinal INTEGER NOT NULL CHECK(source_ordinal BETWEEN 0 AND 7),
  physical_segment_id TEXT NOT NULL,PRIMARY KEY(task_id,entry_ordinal,source_ordinal),UNIQUE(task_id,entry_ordinal,physical_segment_id),
  FOREIGN KEY(task_id,entry_ordinal) REFERENCES "synopsis_entries"(task_id,ordinal) ON DELETE RESTRICT,
  FOREIGN KEY(task_id,physical_segment_id) REFERENCES "synopsis_input_evidence"(task_id,physical_segment_id) ON DELETE RESTRICT)
;

CREATE TABLE image_vector_bindings(publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
  origin_vector_publication_id TEXT NOT NULL REFERENCES image_vector_publications(id) ON DELETE RESTRICT,
  inherited_from_publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
  binding_sha256 TEXT NOT NULL CHECK(typeof(binding_sha256)='text' AND length(binding_sha256)=64 AND binding_sha256 NOT GLOB '*[^a-f0-9]*'),
  created_at TEXT NOT NULL, model_rebuild_id TEXT REFERENCES model_rebuilds(id) ON DELETE RESTRICT,PRIMARY KEY(publication_id,origin_vector_publication_id),
  CHECK(publication_id!=inherited_from_publication_id))
;

CREATE TABLE audio_vector_bindings(publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
  origin_vector_publication_id TEXT NOT NULL REFERENCES audio_vector_publications(id) ON DELETE RESTRICT,
  inherited_from_publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
  binding_sha256 TEXT NOT NULL CHECK(typeof(binding_sha256)='text' AND length(binding_sha256)=64 AND binding_sha256 NOT GLOB '*[^a-f0-9]*'),
  created_at TEXT NOT NULL, model_rebuild_id TEXT REFERENCES model_rebuilds(id) ON DELETE RESTRICT,PRIMARY KEY(publication_id,origin_vector_publication_id),
  CHECK(publication_id!=inherited_from_publication_id))
;

CREATE TABLE "query_trace_documents"(
  trace_id TEXT NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  ordinal INTEGER NOT NULL CHECK(ordinal>=0),
  publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
  PRIMARY KEY(trace_id,ordinal),UNIQUE(trace_id,publication_id))
;

CREATE TABLE query_trace_evidence(
  trace_id TEXT NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
  publication_id TEXT NOT NULL,source_segment_id TEXT NOT NULL,physical_segment_id TEXT NOT NULL,
  page_number INTEGER NOT NULL CHECK(page_number BETWEEN 1 AND 500),
  start_offset INTEGER NOT NULL CHECK(start_offset>=0),
  end_offset INTEGER NOT NULL CHECK(end_offset>start_offset AND end_offset-start_offset<=1200),
  text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
  page_sha256 TEXT NOT NULL CHECK(length(page_sha256)=64 AND page_sha256 NOT GLOB '*[^a-f0-9]*'),
  quote_sha256 TEXT NOT NULL CHECK(length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*'),
  retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
  rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
  fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256) BETWEEN 64 AND 519 AND fact_sha256 NOT GLOB '*[^a-f0-9,]*'),
  PRIMARY KEY(trace_id,citation_ordinal),
  FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,source_segment_id) REFERENCES index_publication_entries(publication_id,source_segment_id) ON DELETE RESTRICT)
;

CREATE TABLE image_trace_evidence(
  trace_id TEXT NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
  publication_id TEXT NOT NULL,image_evidence_id TEXT NOT NULL,physical_segment_id TEXT NOT NULL,
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
  rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
  fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256) BETWEEN 64 AND 519 AND fact_sha256 NOT GLOB '*[^a-f0-9,]*'),
  visual_model_revision TEXT NOT NULL CHECK(length(visual_model_revision) BETWEEN 1 AND 160),
  visual_policy_revision TEXT NOT NULL CHECK(length(visual_policy_revision) BETWEEN 1 AND 160),
  PRIMARY KEY(trace_id,citation_ordinal),
  FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,image_evidence_id) REFERENCES image_publication_entries(publication_id,image_evidence_id) ON DELETE RESTRICT)
;

CREATE TABLE audio_trace_evidence(
  trace_id TEXT NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
  publication_id TEXT NOT NULL,audio_span_id TEXT NOT NULL,physical_segment_id TEXT NOT NULL,
  start_code_point INTEGER NOT NULL CHECK(start_code_point>=0),
  end_code_point INTEGER NOT NULL CHECK(end_code_point>start_code_point AND end_code_point-start_code_point<=1200),
  start_ms INTEGER NOT NULL CHECK(start_ms>=0),
  end_ms INTEGER NOT NULL CHECK(end_ms>start_ms AND end_ms<=600000 AND end_ms-start_ms<=30000),
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
  transcript_sha256 TEXT NOT NULL CHECK(length(transcript_sha256)=64 AND transcript_sha256 NOT GLOB '*[^a-f0-9]*'),
  quote_sha256 TEXT NOT NULL CHECK(length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*'),
  retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
  rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
  fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256) BETWEEN 64 AND 519 AND fact_sha256 NOT GLOB '*[^a-f0-9,]*'),
  PRIMARY KEY(trace_id,citation_ordinal),
  FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,audio_span_id) REFERENCES audio_publication_entries(publication_id,audio_span_id) ON DELETE RESTRICT)
;

CREATE TABLE video_trace_proofs(
  trace_id TEXT PRIMARY KEY NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
  publication_id TEXT NOT NULL,
  group_id TEXT NOT NULL REFERENCES video_evidence_groups(id) ON DELETE RESTRICT,
  mode TEXT NOT NULL CHECK(mode IN ('VISUAL','TRANSCRIPT','JOINT')),
  fact_count INTEGER NOT NULL CHECK(fact_count BETWEEN 1 AND 8),
  text_model_revision TEXT NOT NULL CHECK(length(text_model_revision) BETWEEN 1 AND 200),
  vision_model_revision TEXT NOT NULL CHECK(length(vision_model_revision) BETWEEN 1 AND 200),
  policy_revision TEXT NOT NULL CHECK(length(policy_revision) BETWEEN 1 AND 200),
  UNIQUE(trace_id,publication_id,group_id),
  FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT)
;

CREATE TABLE video_trace_facts(
  trace_id TEXT NOT NULL REFERENCES video_trace_proofs(trace_id) ON DELETE RESTRICT,
  ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 7),
  fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256)=64 AND fact_sha256 NOT GLOB '*[^a-f0-9]*'),
  visual_support INTEGER NOT NULL CHECK(visual_support IN (0,1)),
  transcript_support INTEGER NOT NULL CHECK(transcript_support IN (0,1)),
  CHECK(visual_support+transcript_support>0),
  PRIMARY KEY(trace_id,ordinal),UNIQUE(trace_id,fact_sha256))
;

CREATE TABLE video_trace_evidence(
  trace_id TEXT NOT NULL,
  citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
  publication_id TEXT NOT NULL,group_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('VISUAL','TRANSCRIPT')),
  frame_id TEXT,transcript_span_id TEXT,
  physical_segment_id TEXT NOT NULL CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
  start_code_point INTEGER,end_code_point INTEGER,
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  frame_sha256 TEXT CHECK(frame_sha256 IS NULL OR (length(frame_sha256)=64 AND frame_sha256 NOT GLOB '*[^a-f0-9]*')),
  span_text_sha256 TEXT CHECK(span_text_sha256 IS NULL OR (length(span_text_sha256)=64 AND span_text_sha256 NOT GLOB '*[^a-f0-9]*')),
  quote_sha256 TEXT CHECK(quote_sha256 IS NULL OR (length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*')),
  retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
  rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
  fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256)=64 AND fact_sha256 NOT GLOB '*[^a-f0-9]*'),
  CHECK((kind='VISUAL' AND frame_id IS NOT NULL AND transcript_span_id IS NULL AND start_code_point IS NULL AND end_code_point IS NULL AND frame_sha256 IS NOT NULL AND span_text_sha256 IS NULL AND quote_sha256 IS NULL)
    OR (kind='TRANSCRIPT' AND frame_id IS NULL AND transcript_span_id IS NOT NULL AND start_code_point IS NOT NULL AND start_code_point>=0 AND end_code_point IS NOT NULL AND end_code_point>start_code_point AND end_code_point-start_code_point<=1200 AND frame_sha256 IS NULL AND span_text_sha256 IS NOT NULL AND quote_sha256 IS NOT NULL)),
  PRIMARY KEY(trace_id,citation_ordinal),
  FOREIGN KEY(trace_id,publication_id,group_id) REFERENCES video_trace_proofs(trace_id,publication_id,group_id) ON DELETE RESTRICT,
  FOREIGN KEY(trace_id,fact_sha256) REFERENCES video_trace_facts(trace_id,fact_sha256) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,frame_id) REFERENCES video_frame_publication_entries(publication_id,video_frame_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,transcript_span_id) REFERENCES video_transcript_publication_entries(publication_id,video_transcript_span_id) ON DELETE RESTRICT)
;

CREATE TABLE video_ocr_trace_evidence(
  trace_id TEXT NOT NULL,citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
  publication_id TEXT NOT NULL,segment_id TEXT NOT NULL,frame_id TEXT NOT NULL REFERENCES video_frame_ocr(frame_id) ON DELETE RESTRICT,
  physical_segment_id TEXT NOT NULL CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
  start_code_point INTEGER NOT NULL CHECK(start_code_point>=0),end_code_point INTEGER NOT NULL CHECK(end_code_point>start_code_point AND end_code_point-start_code_point<=1200),
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  frame_sha256 TEXT NOT NULL CHECK(length(frame_sha256)=64 AND frame_sha256 NOT GLOB '*[^a-f0-9]*'),
  ocr_manifest_sha256 TEXT NOT NULL CHECK(length(ocr_manifest_sha256)=64 AND ocr_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  frame_text_sha256 TEXT NOT NULL CHECK(length(frame_text_sha256)=64 AND frame_text_sha256 NOT GLOB '*[^a-f0-9]*'),
  quote_sha256 TEXT NOT NULL CHECK(length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*'),
  retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
  rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256) BETWEEN 64 AND 519),
  PRIMARY KEY(trace_id,citation_ordinal),
  FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,segment_id) REFERENCES video_ocr_publication_entries(publication_id,video_ocr_segment_id) ON DELETE RESTRICT)
;

CREATE TABLE video_subtitle_trace_evidence(
  trace_id TEXT NOT NULL,citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
  publication_id TEXT NOT NULL,cue_id TEXT NOT NULL,track_id TEXT NOT NULL REFERENCES video_subtitle_tracks(id) ON DELETE RESTRICT,
  physical_segment_id TEXT NOT NULL CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
  start_code_point INTEGER NOT NULL CHECK(start_code_point>=0),end_code_point INTEGER NOT NULL CHECK(end_code_point>start_code_point AND end_code_point-start_code_point<=1200),
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  subtitle_manifest_sha256 TEXT NOT NULL CHECK(length(subtitle_manifest_sha256)=64 AND subtitle_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  native_manifest_sha256 TEXT NOT NULL CHECK(length(native_manifest_sha256)=64 AND native_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
  track_text_sha256 TEXT NOT NULL CHECK(length(track_text_sha256)=64 AND track_text_sha256 NOT GLOB '*[^a-f0-9]*'),
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64 AND payload_sha256 NOT GLOB '*[^a-f0-9]*'),
  quote_sha256 TEXT NOT NULL CHECK(length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*'),
  retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
  rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
  fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256) BETWEEN 64 AND 519 AND fact_sha256 NOT GLOB '*[^a-f0-9,]*'),
  PRIMARY KEY(trace_id,citation_ordinal),
  FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT,
  FOREIGN KEY(publication_id,cue_id) REFERENCES video_subtitle_publication_entries(publication_id,video_subtitle_cue_id) ON DELETE RESTRICT)
;

CREATE TABLE "indexing_jobs"(restart_of TEXT CHECK(restart_of IS NULL OR (typeof(restart_of)='text' AND length(restart_of) BETWEEN 1 AND 100)),model_rebuild_id TEXT REFERENCES model_rebuilds(id) ON DELETE RESTRICT,replacement_id TEXT REFERENCES document_replacements(id) ON DELETE RESTRICT,id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES corpus_documents(document_id) ON DELETE RESTRICT,revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64),parser_revision TEXT NOT NULL,embedding_identity TEXT NOT NULL,projection_identity TEXT NOT NULL,model_revision TEXT NOT NULL,dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 2 AND 8192),state TEXT NOT NULL CHECK(state IN ('queued','processing','prepared','indexed','failed','cancelled')),attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 3),claim_token_sha256 TEXT,projection_generation_id TEXT,created_by TEXT NOT NULL,error_code TEXT CHECK(error_code IN ('indexing_failed','indexing_timeout','indexing_output_invalid','worker_interrupted','authorization_changed','index_configuration_changed')),created_at TEXT NOT NULL,updated_at TEXT NOT NULL,rebuild_sequence INTEGER NOT NULL DEFAULT 0 CHECK(typeof(rebuild_sequence)='integer' AND rebuild_sequence BETWEEN 0 AND 2147483647),base_publication_id TEXT, base_vector_set_sha256 TEXT CHECK(base_vector_set_sha256 IS NULL OR (typeof(base_vector_set_sha256)='text' AND length(base_vector_set_sha256)=64 AND base_vector_set_sha256 NOT GLOB '*[^a-f0-9]*')),UNIQUE(document_id,rebuild_sequence),CHECK(model_rebuild_id IS NOT NULL OR replacement_id IS NOT NULL OR restart_of IS NOT NULL OR (rebuild_sequence=0 AND base_publication_id IS NULL) OR (rebuild_sequence>0 AND base_publication_id IS NOT NULL)),FOREIGN KEY(base_publication_id,document_id) REFERENCES index_publications(id,document_id) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,FOREIGN KEY(document_id,revision_id) REFERENCES corpus_revisions(document_id,id),FOREIGN KEY(id,attempt,projection_generation_id) REFERENCES indexing_attempts(job_id,attempt,projection_generation_id),UNIQUE(id,document_id,revision_id),CHECK((state='processing' AND claim_token_sha256 IS NOT NULL AND length(claim_token_sha256)=64) OR (state!='processing' AND claim_token_sha256 IS NULL)),CHECK((state='failed' AND error_code IS NOT NULL) OR (state!='failed' AND error_code IS NULL)),CHECK(state NOT IN ('processing','prepared','indexed') OR projection_generation_id IS NOT NULL),CHECK(state!='queued' OR projection_generation_id IS NULL),CHECK(restart_of IS NULL OR (rebuild_sequence>0 AND base_publication_id IS NULL AND base_vector_set_sha256 IS NULL AND replacement_id IS NULL AND model_rebuild_id IS NULL)))
;

CREATE TABLE model_rebuild_items(batch_id TEXT NOT NULL REFERENCES model_rebuilds(id) ON DELETE RESTRICT,
  ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal>=0),
  document_id TEXT NOT NULL REFERENCES corpus_documents(document_id) ON DELETE RESTRICT,
  revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
  parser_revision TEXT NOT NULL,base_publication_id TEXT,base_vector_set_sha256 TEXT NOT NULL CHECK(length(base_vector_set_sha256)=64 AND base_vector_set_sha256 NOT GLOB '*[^a-f0-9]*'),
  job_id TEXT NOT NULL UNIQUE,publication_id TEXT,state TEXT NOT NULL CHECK(state IN ('queued','prepared','published','failed')),
  PRIMARY KEY(batch_id,ordinal),UNIQUE(batch_id,document_id),
  FOREIGN KEY(document_id,revision_id) REFERENCES corpus_revisions(document_id,id) ON DELETE RESTRICT,
  FOREIGN KEY(base_publication_id,document_id,revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT,
  FOREIGN KEY(job_id) REFERENCES indexing_jobs(id) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY(publication_id,document_id,revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT,
  CHECK(state NOT IN ('prepared','published') OR publication_id IS NOT NULL))
;

CREATE TABLE batch_request_reservations(
  job_id TEXT NOT NULL REFERENCES indexing_jobs(id),attempt INTEGER NOT NULL,
  batch_id TEXT NOT NULL REFERENCES document_import_batches(id),reserved INTEGER NOT NULL CHECK(reserved>0),
  started INTEGER,finished INTEGER,audit_complete INTEGER,
  PRIMARY KEY(job_id,attempt))
;

CREATE INDEX documents_workspace ON documents(workspace_id,updated_at,id)
;

CREATE INDEX documents_folder ON documents(folder_id,workspace_id)
;

CREATE INDEX acl_principal ON document_acl(principal_id,document_id)
;

CREATE INDEX audit_actor ON management_audit(workspace_id,actor_id,created_at)
;

CREATE UNIQUE INDEX synopsis_one_pending ON synopsis_tasks(document_id) WHERE state IN ('queued','processing')
;

CREATE UNIQUE INDEX synopsis_one_processing ON synopsis_tasks(workspace_id) WHERE state='processing'
;

CREATE INDEX synopsis_queue ON synopsis_tasks(workspace_id,state,created_at,id)
;

CREATE UNIQUE INDEX document_replacement_one_pending ON document_replacements(document_id) WHERE state IN ('stored','queued','processing','parsed','indexing')
;

CREATE UNIQUE INDEX index_publications_document_identity ON index_publications(id,document_id)
;

CREATE INDEX ingestion_state ON ingestion_jobs(state,created_at,id)
;

CREATE UNIQUE INDEX ingestion_one_pending ON ingestion_jobs(document_id) WHERE state IN ('queued','processing')
;

CREATE UNIQUE INDEX model_rebuild_one_pending ON model_rebuilds((1)) WHERE state IN ('queued','running','applying')
;

CREATE INDEX query_traces_actor ON query_traces(workspace_id,actor_id,created_at,id)
;

CREATE INDEX wiki_pages_updated ON wiki_page_revisions(workspace_id,created_at DESC,page_id,version)
;

CREATE INDEX wiki_proposals_status ON wiki_proposals(workspace_id,status,created_at DESC,id)
;

CREATE INDEX wiki_drafts_updated ON wiki_drafts(workspace_id,updated_at DESC,id)
;

CREATE INDEX knowledge_conversations_owner ON knowledge_conversations(workspace_id,actor_id,updated_at DESC)
;

CREATE UNIQUE INDEX indexing_one_pending ON indexing_jobs(document_id) WHERE state IN ('queued','processing','prepared')
;

CREATE INDEX indexing_state ON indexing_jobs(state,created_at,id)
;

CREATE INDEX document_import_file_states_state ON document_import_file_states(batch_id,state)
;

CREATE INDEX document_import_files_ingestion_task ON document_import_files(ingestion_task_id)
;

CREATE INDEX document_import_files_revision ON document_import_files(revision_id)
;

CREATE INDEX import_index_requests_task ON import_index_requests(task_id)
;

CREATE INDEX batch_request_reservations_batch ON batch_request_reservations(batch_id)
;

CREATE INDEX documents_workspace_source ON documents(workspace_id,source_sha256)
;

CREATE INDEX document_import_file_times_finished ON document_import_file_times(batch_id,finished_at)
;

CREATE INDEX document_import_batches_workspace ON document_import_batches(workspace_id,created_at,id)
;

CREATE TRIGGER audit_no_update BEFORE UPDATE ON management_audit BEGIN SELECT RAISE(ABORT,'immutable audit'); END
;

CREATE TRIGGER audit_no_delete BEFORE DELETE ON management_audit BEGIN SELECT RAISE(ABORT,'immutable audit'); END
;

CREATE TRIGGER corpus_revision_identity BEFORE UPDATE OF id,document_id,parser_revision,source_sha256,created_at ON corpus_revisions BEGIN SELECT RAISE(ABORT,'immutable revision identity'); END
;

CREATE TRIGGER corpus_revision_frozen BEFORE UPDATE ON corpus_revisions WHEN OLD.parsed_at IS NOT NULL BEGIN SELECT RAISE(ABORT,'immutable parsed revision'); END
;

CREATE TRIGGER corpus_revisions_no_delete BEFORE DELETE ON corpus_revisions BEGIN SELECT RAISE(ABORT,'immutable corpus history'); END
;

CREATE TRIGGER indexing_attempt_identity BEFORE INSERT ON indexing_attempts WHEN NOT EXISTS(SELECT 1 FROM indexing_jobs j WHERE j.id=NEW.job_id AND j.attempt=NEW.attempt AND j.state='queued' AND j.projection_generation_id IS NULL) BEGIN SELECT RAISE(ABORT,'invalid indexing attempt'); END
;

CREATE TRIGGER index_publication_entry_identity BEFORE INSERT ON index_publication_entries WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id JOIN corpus_segments s ON s.id=NEW.source_segment_id AND s.revision_id=p.revision_id WHERE p.id=NEW.publication_id) BEGIN SELECT RAISE(ABORT,'invalid publication evidence'); END
;

CREATE TRIGGER indexing_attempts_no_delete BEFORE DELETE ON indexing_attempts BEGIN SELECT RAISE(ABORT,'immutable index history'); END
;

CREATE TRIGGER index_publications_no_delete BEFORE DELETE ON index_publications BEGIN SELECT RAISE(ABORT,'immutable index history'); END
;

CREATE TRIGGER index_publication_entries_no_delete BEFORE DELETE ON index_publication_entries BEGIN SELECT RAISE(ABORT,'immutable index history'); END
;

CREATE TRIGGER active_corpus_publications_no_delete BEFORE DELETE ON active_corpus_publications BEGIN SELECT RAISE(ABORT,'immutable index history'); END
;

CREATE TRIGGER indexing_attempts_no_update BEFORE UPDATE ON indexing_attempts BEGIN SELECT RAISE(ABORT,'immutable index publication'); END
;

CREATE TRIGGER index_publications_no_update BEFORE UPDATE ON index_publications BEGIN SELECT RAISE(ABORT,'immutable index publication'); END
;

CREATE TRIGGER index_publication_entries_no_update BEFORE UPDATE ON index_publication_entries BEGIN SELECT RAISE(ABORT,'immutable index publication'); END
;

CREATE TRIGGER query_trace_evidence_identity BEFORE INSERT ON query_trace_evidence
WHEN NOT EXISTS(SELECT 1 FROM index_publication_entries e
  JOIN index_publications p ON p.id=e.publication_id
  JOIN corpus_segments s ON s.id=e.source_segment_id AND s.revision_id=p.revision_id
  JOIN corpus_pages pg ON pg.revision_id=s.revision_id AND pg.page_number=s.page_number
  WHERE e.publication_id=NEW.publication_id AND e.source_segment_id=NEW.source_segment_id
    AND e.physical_segment_id=NEW.physical_segment_id AND s.page_number=NEW.page_number
    AND s.start_offset<=NEW.start_offset AND s.end_offset>=NEW.end_offset
    AND s.text_sha256=NEW.text_sha256 AND pg.text_sha256=NEW.page_sha256)
BEGIN SELECT RAISE(ABORT,'invalid query citation'); END
;

CREATE TRIGGER query_trace_evidence_sealed BEFORE INSERT ON query_trace_evidence WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed query trace'); END
;

CREATE TRIGGER query_trace_evidence_no_update BEFORE UPDATE ON query_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable query trace'); END
;

CREATE TRIGGER query_trace_evidence_no_delete BEFORE DELETE ON query_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable query trace'); END
;

CREATE TRIGGER document_tombstones_identity BEFORE INSERT ON document_tombstones
WHEN NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id)
BEGIN SELECT RAISE(ABORT,'invalid removal identity'); END
;

CREATE TRIGGER document_tombstones_no_replace BEFORE INSERT ON document_tombstones
WHEN EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id)
BEGIN SELECT RAISE(ABORT,'immutable removal request'); END
;

CREATE TRIGGER document_tombstones_no_update BEFORE UPDATE ON document_tombstones
BEGIN SELECT RAISE(ABORT,'immutable removal request'); END
;

CREATE TRIGGER document_tombstones_no_delete BEFORE DELETE ON document_tombstones
BEGIN SELECT RAISE(ABORT,'immutable removal request'); END
;

CREATE TRIGGER image_text_regions_identity BEFORE INSERT ON image_text_regions
WHEN NOT EXISTS(SELECT 1 FROM corpus_revisions r JOIN documents d ON d.id=r.document_id
  WHERE r.id=NEW.revision_id AND d.document_type='image')
  OR (NEW.ordinal>0 AND NOT EXISTS(SELECT 1 FROM image_text_regions p
    WHERE p.revision_id=NEW.revision_id AND p.ordinal=NEW.ordinal-1 AND p.end_offset<=NEW.start_offset))
BEGIN SELECT RAISE(ABORT,'invalid image region identity'); END
;

CREATE TRIGGER image_text_regions_frozen BEFORE INSERT ON image_text_regions
WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL)
BEGIN SELECT RAISE(ABORT,'immutable image regions'); END
;

CREATE TRIGGER image_text_regions_no_replace BEFORE INSERT ON image_text_regions
WHEN EXISTS(SELECT 1 FROM image_text_regions WHERE revision_id=NEW.revision_id AND ordinal=NEW.ordinal)
BEGIN SELECT RAISE(ABORT,'immutable image regions'); END
;

CREATE TRIGGER image_text_regions_no_update BEFORE UPDATE ON image_text_regions BEGIN SELECT RAISE(ABORT,'immutable image regions'); END
;

CREATE TRIGGER image_text_regions_no_delete BEFORE DELETE ON image_text_regions BEGIN SELECT RAISE(ABORT,'immutable image regions'); END
;

CREATE TRIGGER corpus_revision_visual_complete BEFORE UPDATE OF parsed_at ON corpus_revisions
WHEN NEW.parser_revision LIKE 'java-image-visual-v1:%' AND NEW.parsed_at IS NOT NULL AND
  (NEW.page_count!=0 OR NEW.segment_count!=0
    OR (SELECT COUNT(*) FROM image_evidence WHERE revision_id=NEW.id)!=1
    OR EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.id)
    OR EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.id))
BEGIN SELECT RAISE(ABORT,'incomplete visual preparation'); END
;

CREATE TRIGGER image_publication_entry_identity BEFORE INSERT ON image_publication_entries
WHEN NOT EXISTS(SELECT 1 FROM index_publications p
  JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
    AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN image_evidence i ON i.id=NEW.image_evidence_id AND i.revision_id=p.revision_id
  WHERE p.id=NEW.publication_id)
  OR EXISTS(SELECT 1 FROM index_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)
BEGIN SELECT RAISE(ABORT,'invalid image publication evidence'); END
;

CREATE TRIGGER image_publication_entries_no_replace BEFORE INSERT ON image_publication_entries
WHEN EXISTS(SELECT 1 FROM image_publication_entries WHERE
  (publication_id=NEW.publication_id AND image_evidence_id=NEW.image_evidence_id)
  OR physical_segment_id=NEW.physical_segment_id)
BEGIN SELECT RAISE(ABORT,'immutable image publication'); END
;

CREATE TRIGGER index_publication_entries_no_image_collision BEFORE INSERT ON index_publication_entries
WHEN EXISTS(SELECT 1 FROM image_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)
BEGIN SELECT RAISE(ABORT,'duplicate physical evidence identity'); END
;

CREATE TRIGGER image_trace_evidence_identity BEFORE INSERT ON image_trace_evidence
WHEN NOT EXISTS(SELECT 1 FROM image_publication_entries e JOIN index_publications p ON p.id=e.publication_id
  JOIN image_evidence i ON i.id=e.image_evidence_id AND i.revision_id=p.revision_id
  WHERE e.publication_id=NEW.publication_id AND e.image_evidence_id=NEW.image_evidence_id
    AND e.physical_segment_id=NEW.physical_segment_id AND p.source_sha256=NEW.source_sha256)
BEGIN SELECT RAISE(ABORT,'invalid image citation'); END
;

CREATE TRIGGER image_trace_evidence_sealed BEFORE INSERT ON image_trace_evidence
WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id)
BEGIN SELECT RAISE(ABORT,'sealed query trace'); END
;

CREATE TRIGGER image_trace_evidence_no_replace BEFORE INSERT ON image_trace_evidence
WHEN EXISTS(SELECT 1 FROM image_trace_evidence WHERE trace_id=NEW.trace_id AND citation_ordinal=NEW.citation_ordinal)
BEGIN SELECT RAISE(ABORT,'immutable image citation'); END
;

CREATE TRIGGER image_publication_entries_no_update BEFORE UPDATE ON image_publication_entries BEGIN SELECT RAISE(ABORT,'immutable visual evidence'); END
;

CREATE TRIGGER image_publication_entries_no_delete BEFORE DELETE ON image_publication_entries BEGIN SELECT RAISE(ABORT,'immutable visual evidence'); END
;

CREATE TRIGGER image_trace_evidence_no_update BEFORE UPDATE ON image_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable visual evidence'); END
;

CREATE TRIGGER image_trace_evidence_no_delete BEFORE DELETE ON image_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable visual evidence'); END
;

CREATE TRIGGER audio_compilations_identity BEFORE INSERT ON audio_compilations
WHEN NOT EXISTS(SELECT 1 FROM corpus_revisions r JOIN documents d ON d.id=r.document_id
  JOIN corpus_documents c ON c.document_id=d.id AND c.initial_revision_id=r.id
  JOIN ingestion_jobs j ON j.document_id=d.id AND j.revision_id=r.id AND j.state='processing'
  WHERE r.id=NEW.revision_id AND d.document_type='audio'
    AND r.source_sha256=d.source_sha256 AND r.source_sha256=NEW.source_sha256
    AND r.parser_revision=NEW.compiler_revision AND r.parser_revision LIKE 'java-audio-compiler-v1:%'
    AND r.page_count=0 AND r.segment_count=0)
  OR EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.revision_id)
  OR EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.revision_id)
  OR EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=NEW.revision_id)
BEGIN SELECT RAISE(ABORT,'invalid audio preparation identity'); END
;

CREATE TRIGGER audio_compilations_frozen BEFORE INSERT ON audio_compilations
WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL)
BEGIN SELECT RAISE(ABORT,'immutable audio preparation'); END
;

CREATE TRIGGER audio_compilations_no_replace BEFORE INSERT ON audio_compilations
WHEN EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=NEW.revision_id)
BEGIN SELECT RAISE(ABORT,'immutable audio preparation'); END
;

CREATE TRIGGER corpus_revision_audio_complete BEFORE UPDATE OF parsed_at ON corpus_revisions
WHEN NEW.parser_revision LIKE 'java-audio-compiler-v1:%' AND NEW.parsed_at IS NOT NULL AND
  (NEW.page_count!=0 OR NEW.segment_count!=0
    OR NOT EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=NEW.id
      AND h.source_sha256=NEW.source_sha256 AND h.compiler_revision=NEW.parser_revision
      AND h.span_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=NEW.id)
      AND h.projection_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=NEW.id AND index_ordinal IS NOT NULL)
      AND (SELECT MIN(ordinal) FROM audio_spans WHERE revision_id=NEW.id)=0
      AND (SELECT MAX(ordinal) FROM audio_spans WHERE revision_id=NEW.id)=h.span_count-1
      AND (SELECT MIN(start_ms) FROM audio_spans WHERE revision_id=NEW.id)=0
      AND (SELECT MAX(end_ms) FROM audio_spans WHERE revision_id=NEW.id)=h.duration_ms
      AND (SELECT MIN(index_ordinal) FROM audio_spans WHERE revision_id=NEW.id)=0
      AND (SELECT MAX(index_ordinal) FROM audio_spans WHERE revision_id=NEW.id)=h.projection_count-1)
    OR EXISTS(SELECT 1 FROM audio_spans s WHERE s.revision_id=NEW.id AND s.ordinal>0
      AND NOT EXISTS(SELECT 1 FROM audio_spans previous WHERE previous.revision_id=NEW.id
        AND previous.ordinal=s.ordinal-1 AND previous.end_ms=s.start_ms))
    OR EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.id)
    OR EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.id)
    OR EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=NEW.id))
BEGIN SELECT RAISE(ABORT,'incomplete audio preparation'); END
;

CREATE TRIGGER audio_publication_entry_identity BEFORE INSERT ON audio_publication_entries
WHEN NOT EXISTS(SELECT 1 FROM index_publications p
  JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
    AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN audio_spans s ON s.id=NEW.audio_span_id AND s.revision_id=p.revision_id AND s.index_ordinal IS NOT NULL
  WHERE p.id=NEW.publication_id)
  OR EXISTS(SELECT 1 FROM index_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)
  OR EXISTS(SELECT 1 FROM image_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)
BEGIN SELECT RAISE(ABORT,'invalid audio publication evidence'); END
;

CREATE TRIGGER audio_publication_entries_no_replace BEFORE INSERT ON audio_publication_entries
WHEN EXISTS(SELECT 1 FROM audio_publication_entries WHERE
  (publication_id=NEW.publication_id AND audio_span_id=NEW.audio_span_id)
  OR physical_segment_id=NEW.physical_segment_id)
BEGIN SELECT RAISE(ABORT,'immutable audio publication'); END
;

CREATE TRIGGER index_publication_entries_no_audio_collision BEFORE INSERT ON index_publication_entries WHEN EXISTS(SELECT 1 FROM audio_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'duplicate physical evidence identity'); END
;

CREATE TRIGGER image_publication_entries_no_audio_collision BEFORE INSERT ON image_publication_entries WHEN EXISTS(SELECT 1 FROM audio_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'duplicate physical evidence identity'); END
;

CREATE TRIGGER audio_compilations_no_update BEFORE UPDATE ON audio_compilations BEGIN SELECT RAISE(ABORT,'immutable audio evidence'); END
;

CREATE TRIGGER audio_compilations_no_delete BEFORE DELETE ON audio_compilations BEGIN SELECT RAISE(ABORT,'immutable audio evidence'); END
;

CREATE TRIGGER audio_publication_entries_no_update BEFORE UPDATE ON audio_publication_entries BEGIN SELECT RAISE(ABORT,'immutable audio evidence'); END
;

CREATE TRIGGER audio_publication_entries_no_delete BEFORE DELETE ON audio_publication_entries BEGIN SELECT RAISE(ABORT,'immutable audio evidence'); END
;

CREATE TRIGGER audio_trace_evidence_identity BEFORE INSERT ON audio_trace_evidence
WHEN NOT EXISTS(SELECT 1 FROM audio_publication_entries e
  JOIN index_publications p ON p.id=e.publication_id
  JOIN audio_spans s ON s.id=e.audio_span_id AND s.revision_id=p.revision_id AND s.index_ordinal IS NOT NULL
  JOIN audio_compilations h ON h.revision_id=s.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
  WHERE e.publication_id=NEW.publication_id AND e.audio_span_id=NEW.audio_span_id
    AND e.physical_segment_id=NEW.physical_segment_id AND p.source_sha256=NEW.source_sha256
    AND s.start_ms=NEW.start_ms AND s.end_ms=NEW.end_ms AND s.text_sha256=NEW.text_sha256
    AND h.transcript_sha256=NEW.transcript_sha256
    AND NEW.start_code_point>=(SELECT COALESCE(SUM(length(previous.text)+1),0) FROM audio_spans previous WHERE previous.revision_id=s.revision_id AND previous.ordinal<s.ordinal)
    AND NEW.end_code_point<=(SELECT COALESCE(SUM(length(previous.text)+1),0) FROM audio_spans previous WHERE previous.revision_id=s.revision_id AND previous.ordinal<s.ordinal)+length(s.text))
BEGIN SELECT RAISE(ABORT,'invalid audio citation'); END
;

CREATE TRIGGER audio_trace_evidence_sealed BEFORE INSERT ON audio_trace_evidence
WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id)
BEGIN SELECT RAISE(ABORT,'sealed query trace'); END
;

CREATE TRIGGER audio_trace_evidence_no_replace BEFORE INSERT ON audio_trace_evidence
WHEN EXISTS(SELECT 1 FROM audio_trace_evidence WHERE trace_id=NEW.trace_id AND citation_ordinal=NEW.citation_ordinal)
BEGIN SELECT RAISE(ABORT,'immutable audio citation'); END
;

CREATE TRIGGER audio_trace_evidence_no_update BEFORE UPDATE ON audio_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable audio citation'); END
;

CREATE TRIGGER audio_trace_evidence_no_delete BEFORE DELETE ON audio_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable audio citation'); END
;

CREATE TRIGGER video_evidence_groups_identity BEFORE INSERT ON video_evidence_groups
WHEN NOT EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.revision_id AND NEW.ordinal<h.group_count
  AND NEW.end_us<=((h.duration_us+999)/1000)*1000
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id))
  OR NEW.ordinal!=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=NEW.revision_id)
  OR EXISTS(SELECT 1 FROM video_evidence_groups WHERE revision_id=NEW.revision_id
    AND frame_id IS NEW.frame_id AND transcript_span_id IS NEW.transcript_span_id)
  OR NOT (
    (NEW.frame_id IS NOT NULL AND NEW.transcript_span_id IS NOT NULL AND EXISTS(
      SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
      WHERE f.revision_id=NEW.revision_id AND f.id=NEW.frame_id AND s.id=NEW.transcript_span_id
        AND NEW.start_us=max(f.presentation_us,s.start_ms*1000)
        AND NEW.end_us=min(f.presentation_us+f.duration_us,s.end_ms*1000)))
    OR (NEW.frame_id IS NOT NULL AND NEW.transcript_span_id IS NULL AND EXISTS(
      SELECT 1 FROM video_frames f WHERE f.revision_id=NEW.revision_id AND f.id=NEW.frame_id
        AND NEW.start_us=f.presentation_us AND NEW.end_us=f.presentation_us+f.duration_us
        AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=f.revision_id
          AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us)))
    OR (NEW.frame_id IS NULL AND NEW.transcript_span_id IS NOT NULL AND EXISTS(
      SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=NEW.revision_id AND s.id=NEW.transcript_span_id
        AND NEW.start_us=s.start_ms*1000 AND NEW.end_us=s.end_ms*1000
        AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=s.revision_id
          AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us))))
BEGIN SELECT RAISE(ABORT,'invalid video evidence group'); END
;

CREATE TRIGGER video_evidence_groups_frozen BEFORE INSERT ON video_evidence_groups WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable video preparation'); END
;

CREATE TRIGGER video_frame_publication_entries_identity BEFORE INSERT ON video_frame_publication_entries WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id JOIN video_frames s ON s.id=NEW.video_frame_id AND s.revision_id=p.revision_id WHERE p.id=NEW.publication_id) OR EXISTS(SELECT 1 FROM index_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM image_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM audio_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM video_transcript_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'invalid video publication evidence'); END
;

CREATE TRIGGER video_transcript_publication_entries_identity BEFORE INSERT ON video_transcript_publication_entries WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id JOIN video_transcript_spans s ON s.id=NEW.video_transcript_span_id AND s.revision_id=p.revision_id WHERE p.id=NEW.publication_id AND s.index_ordinal IS NOT NULL) OR EXISTS(SELECT 1 FROM index_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM image_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM audio_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM video_frame_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'invalid video publication evidence'); END
;

CREATE TRIGGER index_publication_entries_no_video_collision BEFORE INSERT ON index_publication_entries WHEN EXISTS(SELECT 1 FROM video_frame_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM video_transcript_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'duplicate physical evidence identity'); END
;

CREATE TRIGGER image_publication_entries_no_video_collision BEFORE INSERT ON image_publication_entries WHEN EXISTS(SELECT 1 FROM video_frame_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM video_transcript_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'duplicate physical evidence identity'); END
;

CREATE TRIGGER audio_publication_entries_no_video_collision BEFORE INSERT ON audio_publication_entries WHEN EXISTS(SELECT 1 FROM video_frame_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM video_transcript_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'duplicate physical evidence identity'); END
;

CREATE TRIGGER video_evidence_groups_no_replace BEFORE INSERT ON video_evidence_groups WHEN EXISTS(SELECT 1 FROM video_evidence_groups WHERE id=NEW.id OR (revision_id=NEW.revision_id AND ordinal=NEW.ordinal)) BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_evidence_groups_no_update BEFORE UPDATE ON video_evidence_groups BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_evidence_groups_no_delete BEFORE DELETE ON video_evidence_groups BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_frame_publication_entries_no_replace BEFORE INSERT ON video_frame_publication_entries WHEN EXISTS(SELECT 1 FROM video_frame_publication_entries WHERE publication_id=NEW.publication_id AND video_frame_id=NEW.video_frame_id OR physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_frame_publication_entries_no_update BEFORE UPDATE ON video_frame_publication_entries BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_frame_publication_entries_no_delete BEFORE DELETE ON video_frame_publication_entries BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_transcript_publication_entries_no_replace BEFORE INSERT ON video_transcript_publication_entries WHEN EXISTS(SELECT 1 FROM video_transcript_publication_entries WHERE publication_id=NEW.publication_id AND video_transcript_span_id=NEW.video_transcript_span_id OR physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_transcript_publication_entries_no_update BEFORE UPDATE ON video_transcript_publication_entries BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_transcript_publication_entries_no_delete BEFORE DELETE ON video_transcript_publication_entries BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_trace_proofs_identity BEFORE INSERT ON video_trace_proofs
WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN video_evidence_groups g ON g.revision_id=p.revision_id
  JOIN video_compilations h ON h.revision_id=p.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
  WHERE p.id=NEW.publication_id AND g.id=NEW.group_id)
BEGIN SELECT RAISE(ABORT,'invalid video proof group'); END
;

CREATE TRIGGER video_trace_evidence_identity BEFORE INSERT ON video_trace_evidence
WHEN NOT EXISTS(SELECT 1 FROM index_publications p
  JOIN video_compilations h ON h.revision_id=p.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
  JOIN video_evidence_groups g ON g.revision_id=p.revision_id AND g.id=NEW.group_id
  WHERE p.id=NEW.publication_id AND p.source_sha256=NEW.source_sha256 AND h.manifest_sha256=NEW.manifest_sha256
    AND ((NEW.kind='VISUAL' AND g.frame_id=NEW.frame_id AND EXISTS(
      SELECT 1 FROM video_frame_publication_entries e JOIN video_frames f ON f.id=e.video_frame_id AND f.revision_id=p.revision_id
      WHERE e.publication_id=p.id AND e.video_frame_id=NEW.frame_id AND e.physical_segment_id=NEW.physical_segment_id AND f.frame_sha256=NEW.frame_sha256))
    OR (NEW.kind='TRANSCRIPT' AND g.transcript_span_id=NEW.transcript_span_id AND EXISTS(
      SELECT 1 FROM video_transcript_publication_entries e JOIN video_transcript_spans s ON s.id=e.video_transcript_span_id AND s.revision_id=p.revision_id AND s.index_ordinal IS NOT NULL
      WHERE e.publication_id=p.id AND e.video_transcript_span_id=NEW.transcript_span_id AND e.physical_segment_id=NEW.physical_segment_id AND s.text_sha256=NEW.span_text_sha256
        AND NEW.start_code_point>=(SELECT COALESCE(SUM(length(previous.text)),0) FROM video_transcript_spans previous WHERE previous.revision_id=s.revision_id AND previous.ordinal<s.ordinal)+s.ordinal-(SELECT MIN(initial.ordinal) FROM video_transcript_spans initial WHERE initial.revision_id=s.revision_id AND length(initial.text)>0)
        AND NEW.end_code_point<=(SELECT COALESCE(SUM(length(previous.text)),0) FROM video_transcript_spans previous WHERE previous.revision_id=s.revision_id AND previous.ordinal<s.ordinal)+s.ordinal-(SELECT MIN(initial.ordinal) FROM video_transcript_spans initial WHERE initial.revision_id=s.revision_id AND length(initial.text)>0)+length(s.text)))))
BEGIN SELECT RAISE(ABORT,'invalid video citation'); END
;

CREATE TRIGGER video_trace_proofs_sealed BEFORE INSERT ON video_trace_proofs WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed query trace'); END
;

CREATE TRIGGER video_trace_proofs_no_replace BEFORE INSERT ON video_trace_proofs WHEN EXISTS(SELECT 1 FROM video_trace_proofs WHERE trace_id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'immutable video trace'); END
;

CREATE TRIGGER video_trace_proofs_no_update BEFORE UPDATE ON video_trace_proofs BEGIN SELECT RAISE(ABORT,'immutable video trace'); END
;

CREATE TRIGGER video_trace_proofs_no_delete BEFORE DELETE ON video_trace_proofs BEGIN SELECT RAISE(ABORT,'immutable video trace'); END
;

CREATE TRIGGER video_trace_facts_sealed BEFORE INSERT ON video_trace_facts WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed query trace'); END
;

CREATE TRIGGER video_trace_facts_no_replace BEFORE INSERT ON video_trace_facts WHEN EXISTS(SELECT 1 FROM video_trace_facts WHERE trace_id=NEW.trace_id AND (ordinal=NEW.ordinal OR fact_sha256=NEW.fact_sha256)) BEGIN SELECT RAISE(ABORT,'immutable video trace'); END
;

CREATE TRIGGER video_trace_facts_no_update BEFORE UPDATE ON video_trace_facts BEGIN SELECT RAISE(ABORT,'immutable video trace'); END
;

CREATE TRIGGER video_trace_facts_no_delete BEFORE DELETE ON video_trace_facts BEGIN SELECT RAISE(ABORT,'immutable video trace'); END
;

CREATE TRIGGER video_trace_evidence_sealed BEFORE INSERT ON video_trace_evidence WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed query trace'); END
;

CREATE TRIGGER video_trace_evidence_no_replace BEFORE INSERT ON video_trace_evidence WHEN EXISTS(SELECT 1 FROM video_trace_evidence WHERE trace_id=NEW.trace_id AND citation_ordinal=NEW.citation_ordinal) BEGIN SELECT RAISE(ABORT,'immutable video trace'); END
;

CREATE TRIGGER video_trace_evidence_no_update BEFORE UPDATE ON video_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable video trace'); END
;

CREATE TRIGGER video_trace_evidence_no_delete BEFORE DELETE ON video_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable video trace'); END
;

CREATE TRIGGER video_ocr_regions_identity BEFORE INSERT ON video_ocr_regions
WHEN NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=NEW.revision_id AND f.frame_id=NEW.frame_id
  AND NEW.ordinal<f.region_count AND NEW.end_offset<=length(f.text)
  AND NEW.right_pixel<=f.width AND NEW.bottom_pixel<=f.height)
  OR NEW.ordinal!=(SELECT COUNT(*) FROM video_ocr_regions WHERE frame_id=NEW.frame_id)
BEGIN SELECT RAISE(ABORT,'invalid video OCR region'); END
;

CREATE TRIGGER video_ocr_compilations_frozen BEFORE INSERT ON video_ocr_compilations WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable video OCR preparation'); END
;

CREATE TRIGGER video_ocr_regions_frozen BEFORE INSERT ON video_ocr_regions WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable video OCR preparation'); END
;

CREATE TRIGGER index_publication_entries_no_ocr_collision BEFORE INSERT ON index_publication_entries WHEN EXISTS(SELECT 1 FROM video_ocr_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END
;

CREATE TRIGGER image_publication_entries_no_ocr_collision BEFORE INSERT ON image_publication_entries WHEN EXISTS(SELECT 1 FROM video_ocr_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END
;

CREATE TRIGGER audio_publication_entries_no_ocr_collision BEFORE INSERT ON audio_publication_entries WHEN EXISTS(SELECT 1 FROM video_ocr_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END
;

CREATE TRIGGER video_frame_publication_entries_no_ocr_collision BEFORE INSERT ON video_frame_publication_entries WHEN EXISTS(SELECT 1 FROM video_ocr_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END
;

CREATE TRIGGER video_transcript_publication_entries_no_ocr_collision BEFORE INSERT ON video_transcript_publication_entries WHEN EXISTS(SELECT 1 FROM video_ocr_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END
;

CREATE TRIGGER video_ocr_publication_entries_identity BEFORE INSERT ON video_ocr_publication_entries WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id JOIN video_ocr_segments s ON s.revision_id=p.revision_id AND s.id=NEW.video_ocr_segment_id WHERE p.id=NEW.publication_id) OR EXISTS(SELECT 1 FROM index_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM image_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM audio_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM video_frame_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM video_transcript_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'invalid video OCR publication'); END
;

CREATE TRIGGER index_publication_video_ocr_complete BEFORE INSERT ON index_publications WHEN NEW.parser_revision LIKE 'java-video-compiler-v2:%' AND NOT EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=NEW.revision_id AND r.parsed_at IS NOT NULL AND EXISTS(SELECT 1 FROM video_ocr_compilations o JOIN video_compilations h ON h.revision_id=o.revision_id
  WHERE o.revision_id=r.id AND o.base_manifest_sha256=h.manifest_sha256 AND o.frame_count=h.frame_count
    AND o.frame_count=(SELECT COUNT(*) FROM video_frame_ocr WHERE revision_id=o.revision_id)
    AND o.projection_count=(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=o.revision_id)
    AND NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=o.revision_id
      AND (f.segment_count!=(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.frame_id=f.frame_id)
        OR f.region_count!=(SELECT COUNT(*) FROM video_ocr_regions b WHERE b.frame_id=f.frame_id)))
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=o.revision_id)<=1500000)
) BEGIN SELECT RAISE(ABORT,'incomplete video OCR publication'); END
;

CREATE TRIGGER video_ocr_trace_evidence_identity BEFORE INSERT ON video_ocr_trace_evidence
WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN video_ocr_publication_entries e ON e.publication_id=p.id
  JOIN video_ocr_segments s ON s.id=e.video_ocr_segment_id AND s.revision_id=p.revision_id
  JOIN video_frame_ocr f ON f.frame_id=s.frame_id AND f.revision_id=p.revision_id
  JOIN video_ocr_compilations o ON o.revision_id=p.revision_id
  WHERE p.id=NEW.publication_id AND s.id=NEW.segment_id AND f.frame_id=NEW.frame_id
    AND e.physical_segment_id=NEW.physical_segment_id AND p.source_sha256=NEW.source_sha256
    AND f.frame_sha256=NEW.frame_sha256 AND f.text_sha256=NEW.frame_text_sha256 AND o.manifest_sha256=NEW.ocr_manifest_sha256
    AND NEW.start_code_point>=s.start_offset AND NEW.end_code_point<=s.end_offset)
BEGIN SELECT RAISE(ABORT,'invalid video OCR citation'); END
;

CREATE TRIGGER video_ocr_trace_evidence_sealed BEFORE INSERT ON video_ocr_trace_evidence WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed video OCR trace'); END
;

CREATE TRIGGER video_ocr_compilations_no_replace BEFORE INSERT ON video_ocr_compilations WHEN EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=NEW.revision_id) BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_compilations_no_update BEFORE UPDATE ON video_ocr_compilations BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_compilations_no_delete BEFORE DELETE ON video_ocr_compilations BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_regions_no_replace BEFORE INSERT ON video_ocr_regions WHEN EXISTS(SELECT 1 FROM video_ocr_regions WHERE frame_id=NEW.frame_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_regions_no_update BEFORE UPDATE ON video_ocr_regions BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_regions_no_delete BEFORE DELETE ON video_ocr_regions BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_publication_entries_no_replace BEFORE INSERT ON video_ocr_publication_entries WHEN EXISTS(SELECT 1 FROM video_ocr_publication_entries WHERE publication_id=NEW.publication_id AND video_ocr_segment_id=NEW.video_ocr_segment_id OR physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_publication_entries_no_update BEFORE UPDATE ON video_ocr_publication_entries BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_publication_entries_no_delete BEFORE DELETE ON video_ocr_publication_entries BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_trace_evidence_no_replace BEFORE INSERT ON video_ocr_trace_evidence WHEN EXISTS(SELECT 1 FROM video_ocr_trace_evidence WHERE trace_id=NEW.trace_id AND citation_ordinal=NEW.citation_ordinal) BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_trace_evidence_no_update BEFORE UPDATE ON video_ocr_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_trace_evidence_no_delete BEFORE DELETE ON video_ocr_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_subtitle_compilations_frozen BEFORE INSERT ON video_subtitle_compilations WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable video subtitle preparation'); END
;

CREATE TRIGGER index_publication_entries_no_subtitle_collision BEFORE INSERT ON index_publication_entries WHEN EXISTS(SELECT 1 FROM video_subtitle_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END
;

CREATE TRIGGER image_publication_entries_no_subtitle_collision BEFORE INSERT ON image_publication_entries WHEN EXISTS(SELECT 1 FROM video_subtitle_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END
;

CREATE TRIGGER audio_publication_entries_no_subtitle_collision BEFORE INSERT ON audio_publication_entries WHEN EXISTS(SELECT 1 FROM video_subtitle_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END
;

CREATE TRIGGER video_frame_publication_entries_no_subtitle_collision BEFORE INSERT ON video_frame_publication_entries WHEN EXISTS(SELECT 1 FROM video_subtitle_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END
;

CREATE TRIGGER video_transcript_publication_entries_no_subtitle_collision BEFORE INSERT ON video_transcript_publication_entries WHEN EXISTS(SELECT 1 FROM video_subtitle_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END
;

CREATE TRIGGER video_ocr_publication_entries_no_subtitle_collision BEFORE INSERT ON video_ocr_publication_entries WHEN EXISTS(SELECT 1 FROM video_subtitle_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END
;

CREATE TRIGGER video_subtitle_publication_entries_identity BEFORE INSERT ON video_subtitle_publication_entries WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id JOIN video_subtitle_cues c ON c.revision_id=p.revision_id AND c.id=NEW.video_subtitle_cue_id AND c.index_ordinal IS NOT NULL WHERE p.id=NEW.publication_id) OR EXISTS(SELECT 1 FROM index_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM image_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM audio_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM video_frame_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM video_transcript_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) OR EXISTS(SELECT 1 FROM video_ocr_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'invalid video subtitle publication'); END
;

CREATE TRIGGER index_publication_video_subtitle_complete BEFORE INSERT ON index_publications WHEN NEW.parser_revision LIKE 'java-video-compiler-v3:%' AND NOT EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=NEW.revision_id AND r.parsed_at IS NOT NULL AND EXISTS(SELECT 1 FROM video_subtitle_compilations s JOIN video_compilations h ON h.revision_id=s.revision_id
  WHERE s.revision_id=r.id AND s.base_manifest_sha256=h.manifest_sha256
    AND s.track_count=(SELECT COUNT(*) FROM video_subtitle_tracks WHERE revision_id=s.revision_id)
    AND s.cue_count=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=s.revision_id)
    AND s.cue_count=(SELECT COALESCE(SUM(cue_count),0) FROM video_subtitle_tracks WHERE revision_id=s.revision_id)
    AND s.projection_count=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=s.revision_id AND index_ordinal IS NOT NULL)
    AND h.projection_count+s.projection_count+COALESCE((SELECT projection_count FROM video_ocr_compilations WHERE revision_id=s.revision_id),0)<=4096
    AND NOT EXISTS(SELECT 1 FROM video_subtitle_tracks t WHERE t.revision_id=s.revision_id AND (
      t.cue_count!=(SELECT COUNT(*) FROM video_subtitle_cues WHERE track_id=t.id)
      OR length(t.text)!=(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE track_id=t.id)+max(t.cue_count-1,0)
      OR (t.cue_count>0 AND (t.cue_count-1!=(SELECT MAX(ordinal) FROM video_subtitle_cues WHERE track_id=t.id)
        OR length(t.text)!=(SELECT MAX(end_offset) FROM video_subtitle_cues WHERE track_id=t.id)))))
    AND NOT EXISTS(SELECT 1 FROM video_subtitle_cues c WHERE c.revision_id=s.revision_id AND c.index_ordinal IS NOT NULL
      AND c.index_ordinal!=(SELECT COUNT(*) FROM video_subtitle_cues previous WHERE previous.revision_id=c.revision_id AND previous.index_ordinal IS NOT NULL
        AND (previous.stream_index<c.stream_index OR (previous.stream_index=c.stream_index AND previous.ordinal<c.ordinal))))
    AND (SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=s.revision_id)<=500000
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=s.revision_id AND index_ordinal IS NOT NULL)<=1500000
    AND ((s.ocr_expected=0 AND NOT EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=s.revision_id))
      OR (s.ocr_expected=1 AND EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=s.revision_id AND manifest_sha256=s.ocr_manifest_sha256) AND EXISTS(SELECT 1 FROM video_ocr_compilations o JOIN video_compilations h ON h.revision_id=o.revision_id
  WHERE o.revision_id=r.id AND o.base_manifest_sha256=h.manifest_sha256 AND o.frame_count=h.frame_count
    AND o.frame_count=(SELECT COUNT(*) FROM video_frame_ocr WHERE revision_id=o.revision_id)
    AND o.projection_count=(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=o.revision_id)
    AND NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=o.revision_id
      AND (f.segment_count!=(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.frame_id=f.frame_id)
        OR f.region_count!=(SELECT COUNT(*) FROM video_ocr_regions b WHERE b.frame_id=f.frame_id)))
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=o.revision_id)<=1500000)
)))
) BEGIN SELECT RAISE(ABORT,'incomplete video subtitle publication'); END
;

CREATE TRIGGER video_subtitle_trace_evidence_identity BEFORE INSERT ON video_subtitle_trace_evidence
WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN video_subtitle_publication_entries e ON e.publication_id=p.id
  JOIN video_subtitle_cues c ON c.id=e.video_subtitle_cue_id AND c.revision_id=p.revision_id AND c.index_ordinal IS NOT NULL
  JOIN video_subtitle_tracks t ON t.id=c.track_id AND t.revision_id=p.revision_id
  JOIN video_subtitle_compilations s ON s.revision_id=p.revision_id
  WHERE p.id=NEW.publication_id AND c.id=NEW.cue_id AND t.id=NEW.track_id AND e.physical_segment_id=NEW.physical_segment_id
    AND p.source_sha256=NEW.source_sha256 AND s.manifest_sha256=NEW.subtitle_manifest_sha256
    AND s.native_manifest_sha256=NEW.native_manifest_sha256 AND t.text_sha256=NEW.track_text_sha256
    AND c.payload_sha256=NEW.payload_sha256 AND NEW.start_code_point>=c.start_offset AND NEW.end_code_point<=c.end_offset)
BEGIN SELECT RAISE(ABORT,'invalid video subtitle citation'); END
;

CREATE TRIGGER video_subtitle_trace_evidence_sealed BEFORE INSERT ON video_subtitle_trace_evidence WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed video subtitle trace'); END
;

CREATE TRIGGER synopsis_input_evidence_identity BEFORE INSERT ON synopsis_input_evidence
WHEN NOT EXISTS(SELECT 1 FROM synopsis_tasks WHERE id=NEW.task_id AND state='queued')
  OR NEW.ordinal!=(SELECT COUNT(*) FROM synopsis_input_evidence WHERE task_id=NEW.task_id)
  OR NOT (
    (NEW.kind IN ('TEXT','IMAGE_OCR') AND EXISTS(SELECT 1 FROM index_publication_entries e JOIN corpus_segments s ON s.id=e.source_segment_id JOIN index_publications p ON p.id=e.publication_id JOIN documents d ON d.id=p.document_id
      WHERE e.publication_id=NEW.publication_id AND e.source_segment_id=NEW.source_segment_id AND e.physical_segment_id=NEW.physical_segment_id AND s.text_sha256=NEW.content_sha256 AND NEW.kind=CASE WHEN d.document_type='image' THEN 'IMAGE_OCR' ELSE 'TEXT' END))
    OR (NEW.kind='IMAGE' AND EXISTS(SELECT 1 FROM image_publication_entries e JOIN index_publications p ON p.id=e.publication_id
      WHERE e.publication_id=NEW.publication_id AND e.image_evidence_id=NEW.image_evidence_id AND e.physical_segment_id=NEW.physical_segment_id AND p.source_sha256=NEW.content_sha256))
    OR (NEW.kind='AUDIO_TRANSCRIPT' AND EXISTS(SELECT 1 FROM audio_publication_entries e JOIN audio_spans s ON s.id=e.audio_span_id
      WHERE e.publication_id=NEW.publication_id AND e.audio_span_id=NEW.audio_span_id AND e.physical_segment_id=NEW.physical_segment_id AND s.text_sha256=NEW.content_sha256 AND s.start_ms*1000=NEW.start_us AND s.end_ms*1000=NEW.end_us))
    OR (NEW.kind='VIDEO_FRAME' AND EXISTS(SELECT 1 FROM video_frame_publication_entries e JOIN video_frames f ON f.id=e.video_frame_id
      WHERE e.publication_id=NEW.publication_id AND e.video_frame_id=NEW.video_frame_id AND e.physical_segment_id=NEW.physical_segment_id AND f.frame_sha256=NEW.content_sha256 AND f.presentation_us=NEW.start_us AND f.presentation_us+f.duration_us=NEW.end_us))
    OR (NEW.kind='VIDEO_TRANSCRIPT' AND EXISTS(SELECT 1 FROM video_transcript_publication_entries e JOIN video_transcript_spans s ON s.id=e.video_transcript_span_id
      WHERE e.publication_id=NEW.publication_id AND e.video_transcript_span_id=NEW.video_transcript_span_id AND e.physical_segment_id=NEW.physical_segment_id AND s.text_sha256=NEW.content_sha256 AND s.start_ms*1000=NEW.start_us AND s.end_ms*1000=NEW.end_us))
    OR (NEW.kind='VIDEO_OCR' AND EXISTS(SELECT 1 FROM video_ocr_publication_entries e JOIN video_ocr_segments s ON s.id=e.video_ocr_segment_id JOIN video_frames f ON f.id=s.frame_id
      WHERE e.publication_id=NEW.publication_id AND e.video_ocr_segment_id=NEW.video_ocr_segment_id AND e.physical_segment_id=NEW.physical_segment_id AND s.text_sha256=NEW.content_sha256 AND f.presentation_us=NEW.start_us AND f.presentation_us+f.duration_us=NEW.end_us)) OR (NEW.kind='VIDEO_SUBTITLE' AND EXISTS(SELECT 1 FROM video_subtitle_publication_entries e JOIN video_subtitle_cues c ON c.id=e.video_subtitle_cue_id WHERE e.publication_id=NEW.publication_id AND e.video_subtitle_cue_id=NEW.video_subtitle_cue_id AND e.physical_segment_id=NEW.physical_segment_id AND c.text_sha256=NEW.content_sha256 AND c.start_us=NEW.start_us AND c.end_us=NEW.end_us)))
BEGIN SELECT RAISE(ABORT,'invalid synopsis source identity'); END
;

CREATE TRIGGER synopsis_input_evidence_no_delete BEFORE DELETE ON synopsis_input_evidence BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END
;

CREATE TRIGGER synopsis_input_evidence_no_replace BEFORE INSERT ON synopsis_input_evidence WHEN EXISTS(SELECT 1 FROM synopsis_input_evidence WHERE task_id=NEW.task_id AND (ordinal=NEW.ordinal OR physical_segment_id=NEW.physical_segment_id)) BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END
;

CREATE TRIGGER synopsis_input_evidence_no_update BEFORE UPDATE ON synopsis_input_evidence BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END
;

CREATE TRIGGER synopsis_references_identity BEFORE INSERT ON synopsis_references
WHEN NOT EXISTS(SELECT 1 FROM synopsis_tasks WHERE id=NEW.task_id AND state='processing')
  OR NEW.source_ordinal!=(SELECT COUNT(*) FROM synopsis_references WHERE task_id=NEW.task_id AND entry_ordinal=NEW.entry_ordinal)
BEGIN SELECT RAISE(ABORT,'sealed synopsis references'); END
;

CREATE TRIGGER synopsis_references_no_delete BEFORE DELETE ON synopsis_references BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END
;

CREATE TRIGGER synopsis_references_no_replace BEFORE INSERT ON synopsis_references WHEN EXISTS(SELECT 1 FROM synopsis_references WHERE task_id=NEW.task_id AND entry_ordinal=NEW.entry_ordinal AND (source_ordinal=NEW.source_ordinal OR physical_segment_id=NEW.physical_segment_id)) BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END
;

CREATE TRIGGER synopsis_references_no_update BEFORE UPDATE ON synopsis_references BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END
;

CREATE TRIGGER synopsis_tasks_create BEFORE INSERT ON synopsis_tasks
WHEN NEW.state!='queued' OR NEW.input_count!=0 OR NEW.entry_count!=0
  OR NOT EXISTS(SELECT 1 FROM documents d JOIN active_corpus_publications a ON a.document_id=d.id
    WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id AND a.publication_id=NEW.publication_id)
BEGIN SELECT RAISE(ABORT,'invalid synopsis task'); END
;

CREATE TRIGGER synopsis_tasks_identity BEFORE UPDATE OF id,workspace_id,document_id,revision_id,publication_id,model_revision,policy_revision,created_by,created_at ON synopsis_tasks
BEGIN SELECT RAISE(ABORT,'immutable synopsis identity'); END
;

CREATE TRIGGER synopsis_tasks_no_delete BEFORE DELETE ON synopsis_tasks BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END
;

CREATE TRIGGER synopsis_tasks_no_replace BEFORE INSERT ON synopsis_tasks WHEN EXISTS(SELECT 1 FROM synopsis_tasks WHERE id=NEW.id OR (document_id=NEW.document_id AND state IN ('queued','processing'))) BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END
;

CREATE TRIGGER synopsis_tasks_seal BEFORE UPDATE ON synopsis_tasks
WHEN (NEW.state='processing' AND (NEW.input_count!=(SELECT segment_count FROM index_publications WHERE id=NEW.publication_id)
    OR NEW.input_count!=(SELECT COUNT(*) FROM synopsis_input_evidence WHERE task_id=NEW.id)))
  OR (NEW.state IN ('unavailable','cancelled') AND EXISTS(SELECT 1 FROM synopsis_entries WHERE task_id=NEW.id))
  OR (NEW.state='available' AND (
    NEW.entry_count!=(SELECT COUNT(*) FROM synopsis_entries WHERE task_id=NEW.id)
    OR (SELECT COUNT(*) FROM synopsis_entries WHERE task_id=NEW.id AND section='OVERVIEW')!=1
    OR NOT EXISTS(SELECT 1 FROM synopsis_entries WHERE task_id=NEW.id AND section='TOPIC')
    OR NOT EXISTS(SELECT 1 FROM synopsis_entries WHERE task_id=NEW.id AND section='TERM')
    OR EXISTS(SELECT 1 FROM synopsis_entries e WHERE e.task_id=NEW.id AND e.reference_count!=(SELECT COUNT(*) FROM synopsis_references r WHERE r.task_id=e.task_id AND r.entry_ordinal=e.ordinal))
    OR EXISTS(SELECT 1 FROM synopsis_input_evidence WHERE task_id=NEW.id AND start_us IS NOT NULL)!=EXISTS(SELECT 1 FROM synopsis_entries WHERE task_id=NEW.id AND section='TIMELINE')
    OR EXISTS(SELECT 1 FROM synopsis_entries e WHERE e.task_id=NEW.id AND e.section='TIMELINE' AND (
      e.start_us IS NOT (SELECT MIN(i.start_us) FROM synopsis_references r JOIN synopsis_input_evidence i ON i.task_id=r.task_id AND i.physical_segment_id=r.physical_segment_id WHERE r.task_id=e.task_id AND r.entry_ordinal=e.ordinal)
      OR e.end_us IS NOT (SELECT MAX(i.end_us) FROM synopsis_references r JOIN synopsis_input_evidence i ON i.task_id=r.task_id AND i.physical_segment_id=r.physical_segment_id WHERE r.task_id=e.task_id AND r.entry_ordinal=e.ordinal)
      OR EXISTS(SELECT 1 FROM synopsis_references r JOIN synopsis_input_evidence i ON i.task_id=r.task_id AND i.physical_segment_id=r.physical_segment_id WHERE r.task_id=e.task_id AND r.entry_ordinal=e.ordinal AND i.start_us IS NULL)))))
BEGIN SELECT RAISE(ABORT,'incomplete synopsis result'); END
;

CREATE TRIGGER synopsis_tasks_transition BEFORE UPDATE ON synopsis_tasks
WHEN NOT ((OLD.state='queued' AND NEW.state IN ('processing','unavailable','cancelled')) OR (OLD.state='processing' AND NEW.state IN ('available','unavailable','cancelled')))
  OR (OLD.input_fingerprint IS NOT NULL AND (NEW.input_fingerprint IS NOT OLD.input_fingerprint OR NEW.input_count!=OLD.input_count))
BEGIN SELECT RAISE(ABORT,'invalid synopsis transition'); END
;

CREATE TRIGGER video_subtitle_compilations_no_replace BEFORE INSERT ON video_subtitle_compilations WHEN EXISTS(SELECT 1 FROM video_subtitle_compilations WHERE revision_id=NEW.revision_id) BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_compilations_no_update BEFORE UPDATE ON video_subtitle_compilations BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_compilations_no_delete BEFORE DELETE ON video_subtitle_compilations BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_publication_entries_no_replace BEFORE INSERT ON video_subtitle_publication_entries WHEN EXISTS(SELECT 1 FROM video_subtitle_publication_entries WHERE publication_id=NEW.publication_id AND video_subtitle_cue_id=NEW.video_subtitle_cue_id OR physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_publication_entries_no_update BEFORE UPDATE ON video_subtitle_publication_entries BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_publication_entries_no_delete BEFORE DELETE ON video_subtitle_publication_entries BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_trace_evidence_no_replace BEFORE INSERT ON video_subtitle_trace_evidence WHEN EXISTS(SELECT 1 FROM video_subtitle_trace_evidence WHERE trace_id=NEW.trace_id AND citation_ordinal=NEW.citation_ordinal) BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_trace_evidence_no_update BEFORE UPDATE ON video_subtitle_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_trace_evidence_no_delete BEFORE DELETE ON video_subtitle_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER query_trace_preparations_sealed BEFORE INSERT ON query_trace_preparations WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed query preparation trace'); END
;

CREATE TRIGGER query_trace_preparations_no_replace BEFORE INSERT ON query_trace_preparations WHEN EXISTS(SELECT 1 FROM query_trace_preparations WHERE trace_id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'immutable query preparation trace'); END
;

CREATE TRIGGER query_trace_preparations_no_update BEFORE UPDATE ON query_trace_preparations BEGIN SELECT RAISE(ABORT,'immutable query preparation trace'); END
;

CREATE TRIGGER query_trace_preparations_no_delete BEFORE DELETE ON query_trace_preparations BEGIN SELECT RAISE(ABORT,'immutable query preparation trace'); END
;

CREATE TRIGGER query_trace_attachments_sealed BEFORE INSERT ON query_trace_attachments WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed query preparation trace'); END
;

CREATE TRIGGER query_trace_attachments_no_replace BEFORE INSERT ON query_trace_attachments WHEN EXISTS(SELECT 1 FROM query_trace_attachments WHERE trace_id=NEW.trace_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable query preparation trace'); END
;

CREATE TRIGGER query_trace_attachments_no_update BEFORE UPDATE ON query_trace_attachments BEGIN SELECT RAISE(ABORT,'immutable query preparation trace'); END
;

CREATE TRIGGER query_trace_attachments_no_delete BEFORE DELETE ON query_trace_attachments BEGIN SELECT RAISE(ABORT,'immutable query preparation trace'); END
;

CREATE TRIGGER query_trace_attachment_images_sealed BEFORE INSERT ON query_trace_attachment_images WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed query preparation trace'); END
;

CREATE TRIGGER query_trace_attachment_images_no_replace BEFORE INSERT ON query_trace_attachment_images WHEN EXISTS(SELECT 1 FROM query_trace_attachment_images WHERE trace_id=NEW.trace_id AND attachment_ordinal=NEW.attachment_ordinal AND (ordinal=NEW.ordinal OR image_sha256=NEW.image_sha256)) BEGIN SELECT RAISE(ABORT,'immutable query preparation trace'); END
;

CREATE TRIGGER query_trace_attachment_images_no_update BEFORE UPDATE ON query_trace_attachment_images BEGIN SELECT RAISE(ABORT,'immutable query preparation trace'); END
;

CREATE TRIGGER query_trace_attachment_images_no_delete BEFORE DELETE ON query_trace_attachment_images BEGIN SELECT RAISE(ABORT,'immutable query preparation trace'); END
;

CREATE TRIGGER image_vector_publications_identity BEFORE INSERT ON image_vector_publications
WHEN NOT EXISTS(SELECT 1 FROM index_publications p
  JOIN image_publication_entries e ON e.publication_id=p.id
  JOIN image_evidence i ON i.id=e.image_evidence_id AND i.revision_id=p.revision_id
  JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
  JOIN corpus_documents c ON c.document_id=p.document_id AND c.initial_revision_id=p.revision_id AND c.parsed_revision_id=p.revision_id
  JOIN documents d ON d.id=p.document_id AND d.source_sha256=p.source_sha256
  WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.source_revision_id
    AND p.source_sha256=NEW.source_sha256 AND e.image_evidence_id=NEW.image_evidence_id
    AND e.physical_segment_id=NEW.base_physical_segment_id AND d.document_type='image'
    AND d.mime_type IN ('image/png','image/jpeg') AND length(c.original_blob) BETWEEN 1 AND 10485760
    AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id))
BEGIN SELECT RAISE(ABORT,'invalid image vector identity'); END
;

CREATE TRIGGER image_vector_publications_no_replace BEFORE INSERT ON image_vector_publications
WHEN EXISTS(SELECT 1 FROM image_vector_publications WHERE id=NEW.id OR vector_physical_segment_id=NEW.vector_physical_segment_id
  OR (publication_id=NEW.publication_id AND embedding_identity=NEW.embedding_identity AND projection_identity=NEW.projection_identity AND model_revision=NEW.model_revision AND dimensions=NEW.dimensions))
BEGIN SELECT RAISE(ABORT,'immutable image vector publication'); END
;

CREATE TRIGGER image_vector_publications_no_update BEFORE UPDATE ON image_vector_publications BEGIN SELECT RAISE(ABORT,'immutable image vector publication'); END
;

CREATE TRIGGER image_vector_publications_no_delete BEFORE DELETE ON image_vector_publications BEGIN SELECT RAISE(ABORT,'immutable image vector publication'); END
;

CREATE TRIGGER audio_vector_publications_identity BEFORE INSERT ON audio_vector_publications
WHEN NOT EXISTS(SELECT 1 FROM index_publications p
  JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
  JOIN audio_compilations h ON h.revision_id=p.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
  JOIN corpus_documents c ON c.document_id=p.document_id AND c.initial_revision_id=p.revision_id AND c.parsed_revision_id=p.revision_id
  JOIN documents d ON d.id=p.document_id AND d.source_sha256=p.source_sha256
  WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.source_revision_id
    AND p.source_sha256=NEW.source_sha256 AND p.projection_generation_id!=NEW.vector_generation_id
    AND h.decoder_revision=NEW.decoder_revision AND h.projection_count=NEW.segment_count AND p.segment_count=NEW.segment_count
    AND d.document_type='audio' AND d.mime_type IN ('audio/wav','audio/mpeg','audio/flac','audio/ogg','audio/mp4','audio/webm')
    AND length(c.original_blob) BETWEEN 1 AND 20971520
    AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
    AND NEW.segment_count=(SELECT COUNT(*) FROM audio_publication_entries e WHERE e.publication_id=p.id)
    AND NEW.segment_count=(SELECT COUNT(*) FROM audio_vector_entries v WHERE v.audio_vector_publication_id=NEW.id)
    AND NEW.segment_count=(SELECT COUNT(*) FROM audio_vector_entries v
      JOIN audio_spans s ON s.id=v.audio_evidence_id AND s.revision_id=p.revision_id AND s.index_ordinal IS NOT NULL
      JOIN audio_publication_entries e ON e.publication_id=p.id AND e.audio_span_id=s.id AND e.physical_segment_id=v.base_physical_segment_id
      WHERE v.audio_vector_publication_id=NEW.id AND v.ordinal=s.ordinal AND v.start_sample=s.start_ms*16
        AND ((s.ordinal<h.span_count-1 AND v.end_sample=s.end_ms*16)
          OR (s.ordinal=h.span_count-1 AND v.end_sample>(s.end_ms-1)*16 AND v.end_sample<=s.end_ms*16))))
BEGIN SELECT RAISE(ABORT,'incomplete audio vector identity'); END
;

CREATE TRIGGER audio_vector_publications_no_replace BEFORE INSERT ON audio_vector_publications
WHEN EXISTS(SELECT 1 FROM audio_vector_publications WHERE id=NEW.id OR vector_generation_id=NEW.vector_generation_id
  OR (publication_id=NEW.publication_id AND embedding_identity=NEW.embedding_identity AND projection_identity=NEW.projection_identity AND model_revision=NEW.model_revision AND dimensions=NEW.dimensions AND decoder_revision=NEW.decoder_revision))
BEGIN SELECT RAISE(ABORT,'immutable audio vector publication'); END
;

CREATE TRIGGER audio_vector_entries_sealed BEFORE INSERT ON audio_vector_entries
WHEN EXISTS(SELECT 1 FROM audio_vector_publications WHERE id=NEW.audio_vector_publication_id)
BEGIN SELECT RAISE(ABORT,'sealed audio vector publication'); END
;

CREATE TRIGGER audio_vector_entries_no_replace BEFORE INSERT ON audio_vector_entries
WHEN EXISTS(SELECT 1 FROM audio_vector_entries WHERE vector_physical_segment_id=NEW.vector_physical_segment_id
  OR (audio_vector_publication_id=NEW.audio_vector_publication_id AND (audio_evidence_id=NEW.audio_evidence_id OR ordinal=NEW.ordinal OR base_physical_segment_id=NEW.base_physical_segment_id)))
BEGIN SELECT RAISE(ABORT,'immutable audio vector entry'); END
;

CREATE TRIGGER audio_vector_publications_no_update BEFORE UPDATE ON audio_vector_publications BEGIN SELECT RAISE(ABORT,'immutable audio vector publication'); END
;

CREATE TRIGGER audio_vector_publications_no_delete BEFORE DELETE ON audio_vector_publications BEGIN SELECT RAISE(ABORT,'immutable audio vector publication'); END
;

CREATE TRIGGER audio_vector_entries_no_update BEFORE UPDATE ON audio_vector_entries BEGIN SELECT RAISE(ABORT,'immutable audio vector publication'); END
;

CREATE TRIGGER audio_vector_entries_no_delete BEFORE DELETE ON audio_vector_entries BEGIN SELECT RAISE(ABORT,'immutable audio vector publication'); END
;

CREATE TRIGGER sound_publications_identity BEFORE INSERT ON sound_publications
WHEN NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id
  AND d.active_revision_id=NEW.source_revision_id AND d.source_sha256=NEW.source_sha256
  AND d.filename=NEW.filename AND d.mime_type=NEW.media_type AND d.size_bytes=NEW.size_bytes AND d.document_type='audio'
  AND d.mime_type IN ('audio/wav','audio/mpeg','audio/flac','audio/ogg','audio/mp4','audio/webm')
  AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
  AND (EXISTS(SELECT 1 FROM sound_originals o WHERE o.document_id=d.id AND o.source_revision_id=NEW.source_revision_id
    AND o.source_sha256=NEW.source_sha256 AND length(o.original_blob)=NEW.size_bytes)
    OR EXISTS(SELECT 1 FROM corpus_documents c JOIN corpus_revisions r ON r.id=c.initial_revision_id AND r.document_id=c.document_id
      WHERE c.document_id=d.id AND c.initial_revision_id=NEW.source_revision_id AND r.source_sha256=NEW.source_sha256
        AND length(c.original_blob)=NEW.size_bytes)))
  OR NEW.span_count!=(SELECT COUNT(*) FROM sound_spans WHERE publication_id=NEW.id)
  OR NOT EXISTS(SELECT 1 FROM sound_spans WHERE publication_id=NEW.id AND ordinal=0 AND start_sample=0)
  OR NOT EXISTS(SELECT 1 FROM sound_spans WHERE publication_id=NEW.id AND ordinal=NEW.span_count-1 AND end_sample=NEW.sample_count)
  OR EXISTS(SELECT 1 FROM sound_spans s WHERE s.publication_id=NEW.id
    AND (s.ordinal>=NEW.span_count OR s.end_sample-s.start_sample>NEW.chunk_seconds*16000
      OR (s.ordinal<NEW.span_count-1 AND s.end_sample-s.start_sample!=NEW.chunk_seconds*16000)
      OR (s.ordinal>0 AND NOT EXISTS(SELECT 1 FROM sound_spans previous WHERE previous.publication_id=NEW.id
        AND previous.ordinal=s.ordinal-1 AND previous.end_sample=s.start_sample))))
BEGIN SELECT RAISE(ABORT,'incomplete sound publication'); END
;

CREATE TRIGGER sound_publications_no_replace BEFORE INSERT ON sound_publications
WHEN EXISTS(SELECT 1 FROM sound_publications WHERE id=NEW.id OR generation_id=NEW.generation_id
  OR (document_id=NEW.document_id AND source_revision_id=NEW.source_revision_id AND profile_fingerprint=NEW.profile_fingerprint))
BEGIN SELECT RAISE(ABORT,'immutable sound publication'); END
;

CREATE TRIGGER sound_publications_no_update BEFORE UPDATE ON sound_publications BEGIN SELECT RAISE(ABORT,'immutable sound authority'); END
;

CREATE TRIGGER sound_publications_no_delete BEFORE DELETE ON sound_publications BEGIN SELECT RAISE(ABORT,'immutable sound authority'); END
;

CREATE TRIGGER video_av_publications_identity BEFORE INSERT ON video_av_publications
WHEN NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id
  AND d.active_revision_id=NEW.source_revision_id AND d.source_sha256=NEW.source_sha256
  AND d.filename=NEW.filename AND d.mime_type=NEW.media_type AND d.size_bytes=NEW.size_bytes AND d.document_type='video'
  AND d.mime_type IN ('video/mp4','video/quicktime','video/webm','video/x-matroska')
  AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
  AND (EXISTS(SELECT 1 FROM video_av_originals o WHERE o.document_id=d.id AND o.source_revision_id=NEW.source_revision_id
    AND o.source_sha256=NEW.source_sha256 AND length(o.original_blob)=NEW.size_bytes)
    OR EXISTS(SELECT 1 FROM corpus_documents c JOIN corpus_revisions r ON r.id=c.initial_revision_id AND r.document_id=c.document_id
      WHERE c.document_id=d.id AND c.initial_revision_id=NEW.source_revision_id AND r.source_sha256=NEW.source_sha256 AND length(c.original_blob)=NEW.size_bytes)))
  OR NEW.duration_tick/NEW.ticks_per_second>600 OR (NEW.duration_tick/NEW.ticks_per_second=600 AND NEW.duration_tick%NEW.ticks_per_second!=0)
  OR NEW.window_count!=(SELECT COUNT(*) FROM video_av_windows WHERE publication_id=NEW.id)
  OR NEW.visual_entry_count!=(SELECT COUNT(*) FROM video_av_windows WHERE publication_id=NEW.id AND clip_sha256 IS NOT NULL)
  OR NEW.audio_entry_count!=(SELECT COUNT(*) FROM video_av_windows WHERE publication_id=NEW.id AND pcm_sha256 IS NOT NULL)
  OR NEW.has_audio!=(NEW.audio_entry_count>0)
  OR NOT EXISTS(SELECT 1 FROM video_av_windows WHERE publication_id=NEW.id AND ordinal=0 AND start_tick=0)
  OR NOT EXISTS(SELECT 1 FROM video_av_windows WHERE publication_id=NEW.id AND ordinal=NEW.window_count-1 AND end_tick=NEW.duration_tick)
  OR EXISTS(SELECT 1 FROM video_av_windows w WHERE w.publication_id=NEW.id
    AND (w.ordinal>=NEW.window_count OR w.end_tick-w.start_tick>NEW.chunk_seconds*NEW.ticks_per_second
      OR (w.ordinal>0 AND NOT EXISTS(SELECT 1 FROM video_av_windows previous WHERE previous.publication_id=NEW.id AND previous.ordinal=w.ordinal-1 AND previous.end_tick=w.start_tick))
      OR (w.pcm_sha256 IS NOT NULL AND (w.audio_start_sample!=w.start_tick/(NEW.ticks_per_second/16000) OR w.audio_end_sample>w.end_tick/(NEW.ticks_per_second/16000)
        OR (w.audio_start_sample>0 AND NOT EXISTS(SELECT 1 FROM video_av_windows previous WHERE previous.publication_id=NEW.id AND previous.ordinal=w.ordinal-1 AND previous.audio_end_sample=w.audio_start_sample))))))
BEGIN SELECT RAISE(ABORT,'incomplete video audiovisual publication'); END
;

CREATE TRIGGER video_av_publications_no_replace BEFORE INSERT ON video_av_publications
WHEN EXISTS(SELECT 1 FROM video_av_publications WHERE id=NEW.id
  OR (document_id=NEW.document_id AND source_revision_id=NEW.source_revision_id AND profile_fingerprint=NEW.profile_fingerprint))
BEGIN SELECT RAISE(ABORT,'immutable video audiovisual publication'); END
;

CREATE TRIGGER video_av_windows_sealed BEFORE INSERT ON video_av_windows WHEN EXISTS(SELECT 1 FROM video_av_publications WHERE id=NEW.publication_id) BEGIN SELECT RAISE(ABORT,'sealed video audiovisual publication'); END
;

CREATE TRIGGER video_av_windows_no_replace BEFORE INSERT ON video_av_windows WHEN EXISTS(SELECT 1 FROM video_av_windows WHERE (NEW.visual_physical_id IS NOT NULL AND visual_physical_id=NEW.visual_physical_id) OR (NEW.audio_physical_id IS NOT NULL AND (audio_physical_id=NEW.audio_physical_id OR visual_physical_id=NEW.audio_physical_id)) OR (NEW.visual_physical_id IS NOT NULL AND audio_physical_id=NEW.visual_physical_id) OR (publication_id=NEW.publication_id AND (id=NEW.id OR ordinal=NEW.ordinal))) BEGIN SELECT RAISE(ABORT,'immutable video audiovisual window'); END
;

CREATE TRIGGER video_av_publications_no_update BEFORE UPDATE ON video_av_publications BEGIN SELECT RAISE(ABORT,'immutable video audiovisual authority'); END
;

CREATE TRIGGER video_av_publications_no_delete BEFORE DELETE ON video_av_publications BEGIN SELECT RAISE(ABORT,'immutable video audiovisual authority'); END
;

CREATE TRIGGER video_av_windows_no_update BEFORE UPDATE ON video_av_windows BEGIN SELECT RAISE(ABORT,'immutable video audiovisual authority'); END
;

CREATE TRIGGER video_av_windows_no_delete BEFORE DELETE ON video_av_windows BEGIN SELECT RAISE(ABORT,'immutable video audiovisual authority'); END
;

CREATE TRIGGER video_av_query_preparations_complete BEFORE INSERT ON video_av_query_preparations
WHEN EXISTS(SELECT 1 FROM video_av_traces WHERE id=NEW.trace_id)
  OR NEW.attachment_count!=(SELECT COUNT(*) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)
  OR (SELECT MIN(ordinal) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)!=0
  OR (SELECT MAX(ordinal) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)!=NEW.attachment_count-1
  OR (SELECT COUNT(DISTINCT status) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)!=1
  OR (SELECT COUNT(DISTINCT compiler_revision) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)!=1
  OR EXISTS(SELECT 1 FROM video_av_query_attachments WHERE trace_id=NEW.trace_id AND used_mode!=NEW.mode)
  OR (SELECT SUM(window_count) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)>1201
BEGIN SELECT RAISE(ABORT,'incomplete video query preparation'); END
;

CREATE TRIGGER video_av_query_preparations_no_replace BEFORE INSERT ON video_av_query_preparations
WHEN EXISTS(SELECT 1 FROM video_av_query_preparations WHERE trace_id=NEW.trace_id)
BEGIN SELECT RAISE(ABORT,'immutable video query preparation'); END
;

CREATE TRIGGER video_av_query_attachments_sealed BEFORE INSERT ON video_av_query_attachments
WHEN EXISTS(SELECT 1 FROM video_av_query_preparations WHERE trace_id=NEW.trace_id)
  OR EXISTS(SELECT 1 FROM video_av_traces WHERE id=NEW.trace_id)
BEGIN SELECT RAISE(ABORT,'sealed video query preparation'); END
;

CREATE TRIGGER video_av_query_attachments_no_replace BEFORE INSERT ON video_av_query_attachments
WHEN EXISTS(SELECT 1 FROM video_av_query_attachments WHERE trace_id=NEW.trace_id AND ordinal=NEW.ordinal)
BEGIN SELECT RAISE(ABORT,'immutable video query attachment'); END
;

CREATE TRIGGER video_av_query_preparations_no_update BEFORE UPDATE ON video_av_query_preparations BEGIN SELECT RAISE(ABORT,'immutable video query authority'); END
;

CREATE TRIGGER video_av_query_preparations_no_delete BEFORE DELETE ON video_av_query_preparations BEGIN SELECT RAISE(ABORT,'immutable video query authority'); END
;

CREATE TRIGGER video_av_query_attachments_no_update BEFORE UPDATE ON video_av_query_attachments BEGIN SELECT RAISE(ABORT,'immutable video query authority'); END
;

CREATE TRIGGER video_av_query_attachments_no_delete BEFORE DELETE ON video_av_query_attachments BEGIN SELECT RAISE(ABORT,'immutable video query authority'); END
;

CREATE TRIGGER cleanup_new_document AFTER INSERT ON documents
BEGIN INSERT INTO cleanup_document_provenance(document_id,inventory_known) VALUES(NEW.id,1); END
;

CREATE TRIGGER cleanup_request_identity BEFORE INSERT ON document_cleanups
WHEN NOT EXISTS(SELECT 1 FROM documents d JOIN document_tombstones t ON t.document_id=d.id
  WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id AND d.source_sha256=NEW.source_sha256)
BEGIN SELECT RAISE(ABORT,'cleanup requires removed source'); END
;

CREATE TRIGGER cleanup_request_update BEFORE UPDATE ON document_cleanups
WHEN NEW.id IS NOT OLD.id OR NEW.document_id IS NOT OLD.document_id OR NEW.workspace_id IS NOT OLD.workspace_id
  OR NEW.source_sha256 IS NOT OLD.source_sha256 OR NEW.requested_by IS NOT OLD.requested_by OR NEW.requested_at IS NOT OLD.requested_at
  OR OLD.state='completed' OR (NEW.state='completed' AND ((SELECT COUNT(*) FROM cleanup_resources WHERE cleanup_id=OLD.id)!=9
  OR EXISTS(SELECT 1 FROM cleanup_resources WHERE cleanup_id=OLD.id AND status NOT IN ('completed','not_applicable'))))
BEGIN SELECT RAISE(ABORT,'immutable cleanup identity or incomplete result'); END
;

CREATE TRIGGER cleanup_plan_identity BEFORE INSERT ON cleanup_plans
WHEN NOT EXISTS(SELECT 1 FROM document_cleanups c WHERE c.id=NEW.cleanup_id AND c.document_id=NEW.document_id
  AND c.workspace_id=NEW.workspace_id AND c.source_sha256=NEW.source_sha256 AND c.state='running')
  OR NEW.payload_count!=(SELECT COUNT(*) FROM cleanup_payloads WHERE cleanup_id=NEW.cleanup_id)
BEGIN SELECT RAISE(ABORT,'incomplete cleanup plan'); END
;

CREATE TRIGGER cleanup_payload_sealed BEFORE INSERT ON cleanup_payloads
WHEN EXISTS(SELECT 1 FROM cleanup_plans WHERE cleanup_id=NEW.cleanup_id)
BEGIN SELECT RAISE(ABORT,'sealed cleanup body inventory'); END
;

CREATE TRIGGER cleanup_projection_identity BEFORE INSERT ON cleanup_projection_attempts
WHEN NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id
  AND d.active_revision_id=NEW.source_revision_id AND d.source_sha256=NEW.source_sha256)
  OR EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id)
BEGIN SELECT RAISE(ABORT,'invalid projection resource identity'); END
;

CREATE TRIGGER cleanup_projection_update BEFORE UPDATE ON cleanup_projection_attempts
WHEN NEW.document_id IS NOT OLD.document_id OR NEW.workspace_id IS NOT OLD.workspace_id
  OR NEW.source_revision_id IS NOT OLD.source_revision_id OR NEW.source_sha256 IS NOT OLD.source_sha256
  OR NEW.generation_id IS NOT OLD.generation_id OR NEW.route IS NOT OLD.route
  OR NEW.endpoint IS NOT OLD.endpoint OR NEW.database_name IS NOT OLD.database_name
  OR NEW.collection_name IS NOT OLD.collection_name OR NEW.embedding_identity IS NOT OLD.embedding_identity
  OR NEW.dimensions IS NOT OLD.dimensions OR NEW.projection_identity IS NOT OLD.projection_identity
  OR NEW.write_issued<OLD.write_issued
BEGIN SELECT RAISE(ABORT,'immutable projection attempt'); END
;

CREATE TRIGGER cleanup_resource_update BEFORE UPDATE ON cleanup_resources
WHEN NEW.cleanup_id IS NOT OLD.cleanup_id OR NEW.kind IS NOT OLD.kind
  OR EXISTS(SELECT 1 FROM document_cleanups WHERE id=OLD.cleanup_id AND state='completed')
BEGIN SELECT RAISE(ABORT,'immutable cleanup resource'); END
;

CREATE TRIGGER cleanup_backup_update BEFORE UPDATE ON cleanup_managed_backups
WHEN NEW.relative_path IS NOT OLD.relative_path
BEGIN SELECT RAISE(ABORT,'immutable managed backup identity'); END
;

CREATE TRIGGER cleanup_plans_no_update BEFORE UPDATE ON cleanup_plans BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END
;

CREATE TRIGGER cleanup_plans_no_delete BEFORE DELETE ON cleanup_plans BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END
;

CREATE TRIGGER cleanup_payloads_no_update BEFORE UPDATE ON cleanup_payloads BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END
;

CREATE TRIGGER cleanup_payloads_no_delete BEFORE DELETE ON cleanup_payloads BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END
;

CREATE TRIGGER cleanup_document_provenance_no_update BEFORE UPDATE ON cleanup_document_provenance BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END
;

CREATE TRIGGER cleanup_document_provenance_no_delete BEFORE DELETE ON cleanup_document_provenance BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END
;

CREATE TRIGGER cleanup_schema_objects_no_delete BEFORE DELETE ON cleanup_schema_objects BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END
;

CREATE TRIGGER document_cleanups_no_delete BEFORE DELETE ON document_cleanups BEGIN SELECT RAISE(ABORT,'immutable cleanup inventory'); END
;

CREATE TRIGGER cleanup_resources_no_delete BEFORE DELETE ON cleanup_resources BEGIN SELECT RAISE(ABORT,'immutable cleanup inventory'); END
;

CREATE TRIGGER cleanup_projection_attempts_no_delete BEFORE DELETE ON cleanup_projection_attempts BEGIN SELECT RAISE(ABORT,'immutable cleanup inventory'); END
;

CREATE TRIGGER cleanup_managed_backups_no_delete BEFORE DELETE ON cleanup_managed_backups BEGIN SELECT RAISE(ABORT,'immutable cleanup inventory'); END
;

CREATE TRIGGER cleanup_projection_attempts_no_replace BEFORE INSERT ON cleanup_projection_attempts WHEN EXISTS(SELECT 1 FROM cleanup_projection_attempts WHERE generation_id=NEW.generation_id AND route=NEW.route) BEGIN SELECT RAISE(ABORT,'immutable cleanup identity'); END
;

CREATE TRIGGER cleanup_resources_no_replace BEFORE INSERT ON cleanup_resources WHEN EXISTS(SELECT 1 FROM cleanup_resources WHERE cleanup_id=NEW.cleanup_id AND kind=NEW.kind) BEGIN SELECT RAISE(ABORT,'immutable cleanup identity'); END
;

CREATE TRIGGER cleanup_managed_backups_no_replace BEFORE INSERT ON cleanup_managed_backups WHEN EXISTS(SELECT 1 FROM cleanup_managed_backups WHERE relative_path=NEW.relative_path) BEGIN SELECT RAISE(ABORT,'immutable cleanup identity'); END
;

CREATE TRIGGER cleanup_document_provenance_no_replace BEFORE INSERT ON cleanup_document_provenance WHEN EXISTS(SELECT 1 FROM cleanup_document_provenance WHERE document_id=NEW.document_id) BEGIN SELECT RAISE(ABORT,'immutable cleanup identity'); END
;

CREATE TRIGGER cleanup_schema_objects_no_replace BEFORE INSERT ON cleanup_schema_objects WHEN EXISTS(SELECT 1 FROM cleanup_schema_objects WHERE name=NEW.name) BEGIN SELECT RAISE(ABORT,'immutable cleanup identity'); END
;

CREATE TRIGGER document_cleanups_no_replace BEFORE INSERT ON document_cleanups WHEN EXISTS(SELECT 1 FROM document_cleanups WHERE id=NEW.id OR document_id=NEW.document_id) BEGIN SELECT RAISE(ABORT,'immutable cleanup identity'); END
;

CREATE TRIGGER cleanup_payloads_no_replace BEFORE INSERT ON cleanup_payloads WHEN EXISTS(SELECT 1 FROM cleanup_payloads WHERE cleanup_id=NEW.cleanup_id AND table_name=NEW.table_name AND row_key=NEW.row_key AND body_column=NEW.body_column) BEGIN SELECT RAISE(ABORT,'immutable cleanup identity'); END
;

CREATE TRIGGER cleanup_plans_no_replace BEFORE INSERT ON cleanup_plans WHEN EXISTS(SELECT 1 FROM cleanup_plans WHERE cleanup_id=NEW.cleanup_id) BEGIN SELECT RAISE(ABORT,'immutable cleanup identity'); END
;

CREATE TRIGGER corpus_documents_no_delete BEFORE DELETE ON corpus_documents BEGIN SELECT RAISE(ABORT,'immutable corpus history'); END
;

CREATE TRIGGER cleanup_corpus_documents_insert BEFORE INSERT ON corpus_documents WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_corpus_documents_replace BEFORE INSERT ON corpus_documents WHEN EXISTS(SELECT 1 FROM corpus_documents prior WHERE hex(CAST(prior.document_id AS BLOB))=hex(CAST(NEW.document_id AS BLOB)) OR (prior.document_id=NEW.document_id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER sound_originals_identity BEFORE INSERT ON sound_originals
WHEN length(NEW.original_blob)!=NEW.size_bytes OR NOT EXISTS(SELECT 1 FROM documents d
  WHERE d.id=NEW.document_id AND d.active_revision_id=NEW.source_revision_id
    AND d.source_sha256=NEW.source_sha256 AND d.filename=NEW.filename
    AND d.document_type='audio' AND d.mime_type=NEW.media_type AND d.size_bytes=NEW.size_bytes
    AND d.mime_type IN ('audio/wav','audio/mpeg','audio/flac','audio/ogg','audio/mp4','audio/webm')
    AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id))
BEGIN SELECT RAISE(ABORT,'invalid sound original identity'); END
;

CREATE TRIGGER sound_originals_no_delete BEFORE DELETE ON sound_originals BEGIN SELECT RAISE(ABORT,'immutable sound original'); END
;

CREATE TRIGGER sound_originals_no_replace BEFORE INSERT ON sound_originals
WHEN EXISTS(SELECT 1 FROM sound_originals WHERE document_id=NEW.document_id OR source_revision_id=NEW.source_revision_id)
BEGIN SELECT RAISE(ABORT,'immutable sound original'); END
;

CREATE TRIGGER cleanup_sound_originals_insert BEFORE INSERT ON sound_originals WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_sound_originals_replace BEFORE INSERT ON sound_originals WHEN EXISTS(SELECT 1 FROM sound_originals prior WHERE hex(CAST(prior.document_id AS BLOB))=hex(CAST(NEW.document_id AS BLOB)) OR (prior.source_revision_id=NEW.source_revision_id) OR (prior.document_id=NEW.document_id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER video_av_originals_identity BEFORE INSERT ON video_av_originals
WHEN length(NEW.original_blob)!=NEW.size_bytes OR NOT EXISTS(SELECT 1 FROM documents d
  WHERE d.id=NEW.document_id AND d.active_revision_id=NEW.source_revision_id
    AND d.source_sha256=NEW.source_sha256 AND d.filename=NEW.filename
    AND d.document_type='video' AND d.mime_type=NEW.media_type AND d.size_bytes=NEW.size_bytes
    AND d.mime_type IN ('video/mp4','video/quicktime','video/webm','video/x-matroska')
    AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id))
BEGIN SELECT RAISE(ABORT,'invalid video audiovisual original identity'); END
;

CREATE TRIGGER video_av_originals_no_delete BEFORE DELETE ON video_av_originals BEGIN SELECT RAISE(ABORT,'immutable video audiovisual original'); END
;

CREATE TRIGGER video_av_originals_no_replace BEFORE INSERT ON video_av_originals
WHEN EXISTS(SELECT 1 FROM video_av_originals WHERE document_id=NEW.document_id OR source_revision_id=NEW.source_revision_id)
BEGIN SELECT RAISE(ABORT,'immutable video audiovisual original'); END
;

CREATE TRIGGER cleanup_video_av_originals_insert BEFORE INSERT ON video_av_originals WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_video_av_originals_replace BEFORE INSERT ON video_av_originals WHEN EXISTS(SELECT 1 FROM video_av_originals prior WHERE hex(CAST(prior.document_id AS BLOB))=hex(CAST(NEW.document_id AS BLOB)) OR (prior.source_revision_id=NEW.source_revision_id) OR (prior.document_id=NEW.document_id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER corpus_pages_frozen BEFORE INSERT ON corpus_pages WHEN EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=NEW.revision_id AND r.parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable parsed evidence'); END
;

CREATE TRIGGER corpus_pages_no_delete BEFORE DELETE ON corpus_pages BEGIN SELECT RAISE(ABORT,'immutable corpus history'); END
;

CREATE TRIGGER corpus_pages_no_update BEFORE UPDATE ON corpus_pages  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.revision_id IS OLD.revision_id AND NEW.page_number IS OLD.page_number AND NEW.text_sha256 IS OLD.text_sha256 AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'corpus_pages',hex(CAST(OLD.revision_id AS BLOB))||':'||hex(CAST(OLD.page_number AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable corpus evidence'); END
;

CREATE TRIGGER cleanup_corpus_pages_insert BEFORE INSERT ON corpus_pages WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_corpus_pages_replace BEFORE INSERT ON corpus_pages WHEN EXISTS(SELECT 1 FROM corpus_pages prior WHERE hex(CAST(prior.revision_id AS BLOB))||':'||hex(CAST(prior.page_number AS BLOB))=hex(CAST(NEW.revision_id AS BLOB))||':'||hex(CAST(NEW.page_number AS BLOB)) OR (prior.revision_id=NEW.revision_id AND prior.page_number=NEW.page_number)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_corpus_pages_purge BEFORE UPDATE ON corpus_pages WHEN (NEW.text IS NOT OLD.text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.revision_id IS OLD.revision_id AND NEW.page_number IS OLD.page_number AND NEW.text_sha256 IS OLD.text_sha256 AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'corpus_pages',hex(CAST(OLD.revision_id AS BLOB))||':'||hex(CAST(OLD.page_number AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER corpus_segments_frozen BEFORE INSERT ON corpus_segments WHEN EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=NEW.revision_id AND r.parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable parsed evidence'); END
;

CREATE TRIGGER corpus_segments_no_delete BEFORE DELETE ON corpus_segments BEGIN SELECT RAISE(ABORT,'immutable corpus history'); END
;

CREATE TRIGGER corpus_segments_no_update BEFORE UPDATE ON corpus_segments  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.ordinal IS OLD.ordinal AND NEW.page_number IS OLD.page_number AND NEW.start_offset IS OLD.start_offset AND NEW.end_offset IS OLD.end_offset AND NEW.text_sha256 IS OLD.text_sha256 AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'corpus_segments',hex(CAST(OLD.id AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable corpus evidence'); END
;

CREATE TRIGGER cleanup_corpus_segments_insert BEFORE INSERT ON corpus_segments WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_corpus_segments_replace BEFORE INSERT ON corpus_segments WHEN EXISTS(SELECT 1 FROM corpus_segments prior WHERE hex(CAST(prior.id AS BLOB))=hex(CAST(NEW.id AS BLOB)) OR (prior.revision_id=NEW.revision_id AND prior.ordinal=NEW.ordinal) OR (prior.id=NEW.id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_corpus_segments_purge BEFORE UPDATE ON corpus_segments WHEN (NEW.text IS NOT OLD.text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.ordinal IS OLD.ordinal AND NEW.page_number IS OLD.page_number AND NEW.start_offset IS OLD.start_offset AND NEW.end_offset IS OLD.end_offset AND NEW.text_sha256 IS OLD.text_sha256 AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'corpus_segments',hex(CAST(OLD.id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER image_evidence_frozen BEFORE INSERT ON image_evidence
WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL)
BEGIN SELECT RAISE(ABORT,'immutable image evidence'); END
;

CREATE TRIGGER image_evidence_identity BEFORE INSERT ON image_evidence
WHEN NOT EXISTS(SELECT 1 FROM corpus_revisions r JOIN documents d ON d.id=r.document_id
  JOIN corpus_documents c ON c.document_id=d.id AND c.initial_revision_id=r.id
  JOIN ingestion_jobs j ON j.document_id=d.id AND j.revision_id=r.id AND j.state='processing'
  WHERE r.id=NEW.revision_id AND d.document_type='image' AND d.mime_type IN ('image/png','image/jpeg')
    AND r.source_sha256=d.source_sha256
    AND r.parser_revision='java-image-visual-v1:'||NEW.description_revision
    AND r.page_count=0 AND r.segment_count=0)
  OR EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.revision_id)
  OR EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.revision_id)
BEGIN SELECT RAISE(ABORT,'invalid image evidence identity'); END
;

CREATE TRIGGER image_evidence_no_delete BEFORE DELETE ON image_evidence BEGIN SELECT RAISE(ABORT,'immutable visual evidence'); END
;

CREATE TRIGGER image_evidence_no_replace BEFORE INSERT ON image_evidence
WHEN EXISTS(SELECT 1 FROM image_evidence WHERE id=NEW.id OR revision_id=NEW.revision_id)
BEGIN SELECT RAISE(ABORT,'immutable image evidence'); END
;

CREATE TRIGGER image_evidence_no_update BEFORE UPDATE ON image_evidence  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.recall_text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.width IS OLD.width AND NEW.height IS OLD.height AND NEW.recall_sha256 IS OLD.recall_sha256 AND NEW.description_revision IS OLD.description_revision AND NEW.created_at IS OLD.created_at AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'image_evidence',hex(CAST(OLD.id AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable visual evidence'); END
;

CREATE TRIGGER cleanup_image_evidence_insert BEFORE INSERT ON image_evidence WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_image_evidence_replace BEFORE INSERT ON image_evidence WHEN EXISTS(SELECT 1 FROM image_evidence prior WHERE hex(CAST(prior.id AS BLOB))=hex(CAST(NEW.id AS BLOB)) OR (prior.revision_id=NEW.revision_id) OR (prior.id=NEW.id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_image_evidence_purge BEFORE UPDATE ON image_evidence WHEN (NEW.recall_text IS NOT OLD.recall_text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.recall_text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.width IS OLD.width AND NEW.height IS OLD.height AND NEW.recall_sha256 IS OLD.recall_sha256 AND NEW.description_revision IS OLD.description_revision AND NEW.created_at IS OLD.created_at AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'image_evidence',hex(CAST(OLD.id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER audio_spans_frozen BEFORE INSERT ON audio_spans
WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL)
BEGIN SELECT RAISE(ABORT,'immutable audio span'); END
;

CREATE TRIGGER audio_spans_identity BEFORE INSERT ON audio_spans
WHEN NOT EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=NEW.revision_id
  AND NEW.ordinal<h.span_count AND NEW.end_ms<=h.duration_ms
  AND (NEW.index_ordinal IS NULL OR NEW.index_ordinal<h.projection_count))
  OR NEW.ordinal!=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=NEW.revision_id)
  OR NEW.start_ms!=COALESCE((SELECT MAX(end_ms) FROM audio_spans WHERE revision_id=NEW.revision_id),0)
  OR (NEW.index_ordinal IS NOT NULL AND NEW.index_ordinal!=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=NEW.revision_id AND index_ordinal IS NOT NULL))
BEGIN SELECT RAISE(ABORT,'invalid audio span identity'); END
;

CREATE TRIGGER audio_spans_no_delete BEFORE DELETE ON audio_spans BEGIN SELECT RAISE(ABORT,'immutable audio evidence'); END
;

CREATE TRIGGER audio_spans_no_replace BEFORE INSERT ON audio_spans
WHEN EXISTS(SELECT 1 FROM audio_spans WHERE id=NEW.id
  OR (revision_id=NEW.revision_id AND (ordinal=NEW.ordinal OR index_ordinal=NEW.index_ordinal)))
BEGIN SELECT RAISE(ABORT,'immutable audio span'); END
;

CREATE TRIGGER audio_spans_no_update BEFORE UPDATE ON audio_spans  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.ordinal IS OLD.ordinal AND NEW.start_ms IS OLD.start_ms AND NEW.end_ms IS OLD.end_ms AND NEW.text_sha256 IS OLD.text_sha256 AND NEW.index_ordinal IS OLD.index_ordinal AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'audio_spans',hex(CAST(OLD.id AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable audio evidence'); END
;

CREATE TRIGGER cleanup_audio_spans_insert BEFORE INSERT ON audio_spans WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_audio_spans_replace BEFORE INSERT ON audio_spans WHEN EXISTS(SELECT 1 FROM audio_spans prior WHERE hex(CAST(prior.id AS BLOB))=hex(CAST(NEW.id AS BLOB)) OR (prior.revision_id=NEW.revision_id AND prior.index_ordinal=NEW.index_ordinal) OR (prior.revision_id=NEW.revision_id AND prior.ordinal=NEW.ordinal) OR (prior.id=NEW.id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_audio_spans_purge BEFORE UPDATE ON audio_spans WHEN (NEW.text IS NOT OLD.text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.ordinal IS OLD.ordinal AND NEW.start_ms IS OLD.start_ms AND NEW.end_ms IS OLD.end_ms AND NEW.text_sha256 IS OLD.text_sha256 AND NEW.index_ordinal IS OLD.index_ordinal AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'audio_spans',hex(CAST(OLD.id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER video_transcript_spans_frozen BEFORE INSERT ON video_transcript_spans WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable video preparation'); END
;

CREATE TRIGGER video_transcript_spans_no_delete BEFORE DELETE ON video_transcript_spans BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_transcript_spans_no_replace BEFORE INSERT ON video_transcript_spans WHEN EXISTS(SELECT 1 FROM video_transcript_spans WHERE id=NEW.id OR (revision_id=NEW.revision_id AND ordinal=NEW.ordinal)) BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_transcript_spans_no_update BEFORE UPDATE ON video_transcript_spans  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.ordinal IS OLD.ordinal AND NEW.start_ms IS OLD.start_ms AND NEW.end_ms IS OLD.end_ms AND NEW.text_sha256 IS OLD.text_sha256 AND NEW.index_ordinal IS OLD.index_ordinal AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_transcript_spans',hex(CAST(OLD.id AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER cleanup_video_transcript_spans_insert BEFORE INSERT ON video_transcript_spans WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_video_transcript_spans_replace BEFORE INSERT ON video_transcript_spans WHEN EXISTS(SELECT 1 FROM video_transcript_spans prior WHERE hex(CAST(prior.id AS BLOB))=hex(CAST(NEW.id AS BLOB)) OR (prior.revision_id=NEW.revision_id AND prior.index_ordinal=NEW.index_ordinal) OR (prior.revision_id=NEW.revision_id AND prior.ordinal=NEW.ordinal) OR (prior.revision_id=NEW.revision_id AND prior.id=NEW.id) OR (prior.id=NEW.id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_video_transcript_spans_purge BEFORE UPDATE ON video_transcript_spans WHEN (NEW.text IS NOT OLD.text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.ordinal IS OLD.ordinal AND NEW.start_ms IS OLD.start_ms AND NEW.end_ms IS OLD.end_ms AND NEW.text_sha256 IS OLD.text_sha256 AND NEW.index_ordinal IS OLD.index_ordinal AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_transcript_spans',hex(CAST(OLD.id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER video_frame_ocr_frozen BEFORE INSERT ON video_frame_ocr WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable video OCR preparation'); END
;

CREATE TRIGGER video_frame_ocr_identity BEFORE INSERT ON video_frame_ocr
WHEN NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.id=NEW.frame_id AND f.revision_id=NEW.revision_id
  AND f.ordinal=NEW.ordinal AND f.frame_sha256=NEW.frame_sha256 AND f.width=NEW.width AND f.height=NEW.height)
  OR NEW.ordinal!=(SELECT COUNT(*) FROM video_frame_ocr WHERE revision_id=NEW.revision_id)
BEGIN SELECT RAISE(ABORT,'invalid video OCR frame'); END
;

CREATE TRIGGER video_frame_ocr_no_delete BEFORE DELETE ON video_frame_ocr BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_frame_ocr_no_replace BEFORE INSERT ON video_frame_ocr WHEN EXISTS(SELECT 1 FROM video_frame_ocr WHERE frame_id=NEW.frame_id OR (revision_id=NEW.revision_id AND ordinal=NEW.ordinal)) BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_frame_ocr_no_update BEFORE UPDATE ON video_frame_ocr  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.revision_id IS OLD.revision_id AND NEW.frame_id IS OLD.frame_id AND NEW.ordinal IS OLD.ordinal AND NEW.frame_sha256 IS OLD.frame_sha256 AND NEW.width IS OLD.width AND NEW.height IS OLD.height AND NEW.text_sha256 IS OLD.text_sha256 AND NEW.segment_count IS OLD.segment_count AND NEW.region_count IS OLD.region_count AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_frame_ocr',hex(CAST(OLD.frame_id AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER cleanup_video_frame_ocr_insert BEFORE INSERT ON video_frame_ocr WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_video_frame_ocr_replace BEFORE INSERT ON video_frame_ocr WHEN EXISTS(SELECT 1 FROM video_frame_ocr prior WHERE hex(CAST(prior.frame_id AS BLOB))=hex(CAST(NEW.frame_id AS BLOB)) OR (prior.revision_id=NEW.revision_id AND prior.ordinal=NEW.ordinal) OR (prior.revision_id=NEW.revision_id AND prior.frame_id=NEW.frame_id) OR (prior.frame_id=NEW.frame_id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_video_frame_ocr_purge BEFORE UPDATE ON video_frame_ocr WHEN (NEW.text IS NOT OLD.text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.revision_id IS OLD.revision_id AND NEW.frame_id IS OLD.frame_id AND NEW.ordinal IS OLD.ordinal AND NEW.frame_sha256 IS OLD.frame_sha256 AND NEW.width IS OLD.width AND NEW.height IS OLD.height AND NEW.text_sha256 IS OLD.text_sha256 AND NEW.segment_count IS OLD.segment_count AND NEW.region_count IS OLD.region_count AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_frame_ocr',hex(CAST(OLD.frame_id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER video_ocr_segments_frozen BEFORE INSERT ON video_ocr_segments WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable video OCR preparation'); END
;

CREATE TRIGGER video_ocr_segments_identity BEFORE INSERT ON video_ocr_segments
WHEN NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=NEW.revision_id AND f.frame_id=NEW.frame_id
  AND NEW.ordinal<f.segment_count AND NEW.end_offset<=length(f.text)
  AND substr(f.text,NEW.start_offset+1,NEW.end_offset-NEW.start_offset)=NEW.text)
  OR NEW.ordinal!=(SELECT COUNT(*) FROM video_ocr_segments WHERE frame_id=NEW.frame_id)
BEGIN SELECT RAISE(ABORT,'invalid video OCR segment'); END
;

CREATE TRIGGER video_ocr_segments_no_delete BEFORE DELETE ON video_ocr_segments BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_segments_no_replace BEFORE INSERT ON video_ocr_segments WHEN EXISTS(SELECT 1 FROM video_ocr_segments WHERE id=NEW.id OR (frame_id=NEW.frame_id AND ordinal=NEW.ordinal)) BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER video_ocr_segments_no_update BEFORE UPDATE ON video_ocr_segments  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.frame_id IS OLD.frame_id AND NEW.ordinal IS OLD.ordinal AND NEW.start_offset IS OLD.start_offset AND NEW.end_offset IS OLD.end_offset AND NEW.text_sha256 IS OLD.text_sha256 AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_ocr_segments',hex(CAST(OLD.id AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END
;

CREATE TRIGGER cleanup_video_ocr_segments_insert BEFORE INSERT ON video_ocr_segments WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_video_ocr_segments_replace BEFORE INSERT ON video_ocr_segments WHEN EXISTS(SELECT 1 FROM video_ocr_segments prior WHERE hex(CAST(prior.id AS BLOB))=hex(CAST(NEW.id AS BLOB)) OR (prior.revision_id=NEW.revision_id AND prior.id=NEW.id) OR (prior.frame_id=NEW.frame_id AND prior.ordinal=NEW.ordinal) OR (prior.id=NEW.id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_video_ocr_segments_purge BEFORE UPDATE ON video_ocr_segments WHEN (NEW.text IS NOT OLD.text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.frame_id IS OLD.frame_id AND NEW.ordinal IS OLD.ordinal AND NEW.start_offset IS OLD.start_offset AND NEW.end_offset IS OLD.end_offset AND NEW.text_sha256 IS OLD.text_sha256 AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_ocr_segments',hex(CAST(OLD.id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER video_subtitle_tracks_frozen BEFORE INSERT ON video_subtitle_tracks WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable video subtitle preparation'); END
;

CREATE TRIGGER video_subtitle_tracks_identity BEFORE INSERT ON video_subtitle_tracks
WHEN NOT EXISTS(SELECT 1 FROM video_subtitle_compilations h WHERE h.revision_id=NEW.revision_id
  AND (SELECT COUNT(*) FROM video_subtitle_tracks WHERE revision_id=NEW.revision_id)<h.track_count
  AND NEW.cue_count+(SELECT COALESCE(SUM(cue_count),0) FROM video_subtitle_tracks WHERE revision_id=NEW.revision_id)<=h.cue_count)
  OR NEW.stream_index<=COALESCE((SELECT MAX(stream_index) FROM video_subtitle_tracks WHERE revision_id=NEW.revision_id),-1)
BEGIN SELECT RAISE(ABORT,'invalid video subtitle track'); END
;

CREATE TRIGGER video_subtitle_tracks_no_delete BEFORE DELETE ON video_subtitle_tracks BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_tracks_no_replace BEFORE INSERT ON video_subtitle_tracks WHEN EXISTS(SELECT 1 FROM video_subtitle_tracks WHERE id=NEW.id OR (revision_id=NEW.revision_id AND stream_index=NEW.stream_index)) BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_tracks_no_update BEFORE UPDATE ON video_subtitle_tracks  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.stream_index IS OLD.stream_index AND NEW.codec IS OLD.codec AND NEW.time_base_numerator IS OLD.time_base_numerator AND NEW.time_base_denominator IS OLD.time_base_denominator AND NEW.language IS OLD.language AND NEW.cue_count IS OLD.cue_count AND NEW.text_sha256 IS OLD.text_sha256 AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_subtitle_tracks',hex(CAST(OLD.id AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER cleanup_video_subtitle_tracks_insert BEFORE INSERT ON video_subtitle_tracks WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_video_subtitle_tracks_replace BEFORE INSERT ON video_subtitle_tracks WHEN EXISTS(SELECT 1 FROM video_subtitle_tracks prior WHERE hex(CAST(prior.id AS BLOB))=hex(CAST(NEW.id AS BLOB)) OR (prior.revision_id=NEW.revision_id AND prior.stream_index=NEW.stream_index) OR (prior.revision_id=NEW.revision_id AND prior.id=NEW.id) OR (prior.id=NEW.id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_video_subtitle_tracks_purge BEFORE UPDATE ON video_subtitle_tracks WHEN (NEW.text IS NOT OLD.text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.stream_index IS OLD.stream_index AND NEW.codec IS OLD.codec AND NEW.time_base_numerator IS OLD.time_base_numerator AND NEW.time_base_denominator IS OLD.time_base_denominator AND NEW.language IS OLD.language AND NEW.cue_count IS OLD.cue_count AND NEW.text_sha256 IS OLD.text_sha256 AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_subtitle_tracks',hex(CAST(OLD.id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER video_subtitle_cues_frozen BEFORE INSERT ON video_subtitle_cues WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable video subtitle preparation'); END
;

CREATE TRIGGER video_subtitle_cues_identity BEFORE INSERT ON video_subtitle_cues
WHEN NOT EXISTS(SELECT 1 FROM video_subtitle_tracks t JOIN video_compilations h ON h.revision_id=t.revision_id
  WHERE t.id=NEW.track_id AND t.revision_id=NEW.revision_id AND t.stream_index=NEW.stream_index
    AND NEW.ordinal<t.cue_count AND NEW.end_offset<=length(t.text)
    AND substr(t.text,NEW.start_offset+1,NEW.end_offset-NEW.start_offset)=NEW.text
    AND (NEW.end_us IS NULL OR NEW.end_us<=h.duration_us))
  OR NEW.ordinal!=(SELECT COUNT(*) FROM video_subtitle_cues WHERE track_id=NEW.track_id)
  OR NEW.start_offset!=COALESCE((SELECT MAX(end_offset)+1 FROM video_subtitle_cues WHERE track_id=NEW.track_id),0)
  OR (NEW.ordinal>0 AND NOT EXISTS(SELECT 1 FROM video_subtitle_tracks WHERE id=NEW.track_id AND substr(text,NEW.start_offset,1)=char(10)))
  OR (NEW.index_ordinal IS NOT NULL AND NEW.index_ordinal!=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=NEW.revision_id AND index_ordinal IS NOT NULL))
  OR length(NEW.text)+(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=NEW.revision_id)>500000
BEGIN SELECT RAISE(ABORT,'invalid video subtitle cue'); END
;

CREATE TRIGGER video_subtitle_cues_no_delete BEFORE DELETE ON video_subtitle_cues BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_cues_no_replace BEFORE INSERT ON video_subtitle_cues WHEN EXISTS(SELECT 1 FROM video_subtitle_cues WHERE id=NEW.id OR (track_id=NEW.track_id AND ordinal=NEW.ordinal) OR (revision_id=NEW.revision_id AND index_ordinal=NEW.index_ordinal)) BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER video_subtitle_cues_no_update BEFORE UPDATE ON video_subtitle_cues  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.track_id IS OLD.track_id AND NEW.stream_index IS OLD.stream_index AND NEW.ordinal IS OLD.ordinal AND NEW.pts IS OLD.pts AND NEW.duration IS OLD.duration AND NEW.text_sha256 IS OLD.text_sha256 AND NEW.payload_sha256 IS OLD.payload_sha256 AND NEW.start_offset IS OLD.start_offset AND NEW.end_offset IS OLD.end_offset AND NEW.start_us IS OLD.start_us AND NEW.end_us IS OLD.end_us AND NEW.index_ordinal IS OLD.index_ordinal AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_subtitle_cues',hex(CAST(OLD.id AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END
;

CREATE TRIGGER cleanup_video_subtitle_cues_insert BEFORE INSERT ON video_subtitle_cues WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_video_subtitle_cues_replace BEFORE INSERT ON video_subtitle_cues WHEN EXISTS(SELECT 1 FROM video_subtitle_cues prior WHERE hex(CAST(prior.id AS BLOB))=hex(CAST(NEW.id AS BLOB)) OR (prior.revision_id=NEW.revision_id AND prior.index_ordinal=NEW.index_ordinal) OR (prior.track_id=NEW.track_id AND prior.ordinal=NEW.ordinal) OR (prior.revision_id=NEW.revision_id AND prior.id=NEW.id) OR (prior.id=NEW.id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_video_subtitle_cues_purge BEFORE UPDATE ON video_subtitle_cues WHEN (NEW.text IS NOT OLD.text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.track_id IS OLD.track_id AND NEW.stream_index IS OLD.stream_index AND NEW.ordinal IS OLD.ordinal AND NEW.pts IS OLD.pts AND NEW.duration IS OLD.duration AND NEW.text_sha256 IS OLD.text_sha256 AND NEW.payload_sha256 IS OLD.payload_sha256 AND NEW.start_offset IS OLD.start_offset AND NEW.end_offset IS OLD.end_offset AND NEW.start_us IS OLD.start_us AND NEW.end_us IS OLD.end_us AND NEW.index_ordinal IS OLD.index_ordinal AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_subtitle_cues',hex(CAST(OLD.id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER synopsis_entries_identity BEFORE INSERT ON synopsis_entries
WHEN NOT EXISTS(SELECT 1 FROM synopsis_tasks WHERE id=NEW.task_id AND state='processing')
  OR NEW.ordinal!=(SELECT COUNT(*) FROM synopsis_entries WHERE task_id=NEW.task_id)
BEGIN SELECT RAISE(ABORT,'sealed synopsis entries'); END
;

CREATE TRIGGER synopsis_entries_no_delete BEFORE DELETE ON synopsis_entries BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END
;

CREATE TRIGGER synopsis_entries_no_replace BEFORE INSERT ON synopsis_entries WHEN EXISTS(SELECT 1 FROM synopsis_entries WHERE task_id=NEW.task_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END
;

CREATE TRIGGER synopsis_entries_no_update BEFORE UPDATE ON synopsis_entries  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.task_id IS OLD.task_id AND NEW.ordinal IS OLD.ordinal AND NEW.section IS OLD.section AND NEW.start_us IS OLD.start_us AND NEW.end_us IS OLD.end_us AND NEW.reference_count IS OLD.reference_count AND java_cleanup_authorized((SELECT document_id FROM synopsis_tasks WHERE id=OLD.task_id),'synopsis_entries',hex(CAST(OLD.task_id AS BLOB))||':'||hex(CAST(OLD.ordinal AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END
;

CREATE TRIGGER cleanup_synopsis_entries_insert BEFORE INSERT ON synopsis_entries WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_synopsis_entries_replace BEFORE INSERT ON synopsis_entries WHEN EXISTS(SELECT 1 FROM synopsis_entries prior WHERE hex(CAST(prior.task_id AS BLOB))||':'||hex(CAST(prior.ordinal AS BLOB))=hex(CAST(NEW.task_id AS BLOB))||':'||hex(CAST(NEW.ordinal AS BLOB)) OR (prior.task_id=NEW.task_id AND prior.ordinal=NEW.ordinal)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_synopsis_entries_purge BEFORE UPDATE ON synopsis_entries WHEN (NEW.text IS NOT OLD.text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.text IS '' AND NEW.task_id IS OLD.task_id AND NEW.ordinal IS OLD.ordinal AND NEW.section IS OLD.section AND NEW.start_us IS OLD.start_us AND NEW.end_us IS OLD.end_us AND NEW.reference_count IS OLD.reference_count AND java_cleanup_authorized((SELECT document_id FROM synopsis_tasks WHERE id=OLD.task_id),'synopsis_entries',hex(CAST(OLD.task_id AS BLOB))||':'||hex(CAST(OLD.ordinal AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER sound_spans_no_delete BEFORE DELETE ON sound_spans BEGIN SELECT RAISE(ABORT,'immutable sound authority'); END
;

CREATE TRIGGER sound_spans_no_replace BEFORE INSERT ON sound_spans WHEN EXISTS(SELECT 1 FROM sound_spans WHERE physical_segment_id=NEW.physical_segment_id OR (publication_id=NEW.publication_id AND (id=NEW.id OR ordinal=NEW.ordinal))) BEGIN SELECT RAISE(ABORT,'immutable sound span'); END
;

CREATE TRIGGER sound_spans_no_update BEFORE UPDATE ON sound_spans  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.recall_text IS '' AND NEW.publication_id IS OLD.publication_id AND NEW.id IS OLD.id AND NEW.ordinal IS OLD.ordinal AND NEW.start_sample IS OLD.start_sample AND NEW.end_sample IS OLD.end_sample AND NEW.pcm_sha256 IS OLD.pcm_sha256 AND NEW.physical_segment_id IS OLD.physical_segment_id AND NEW.entry_sha256 IS OLD.entry_sha256 AND java_cleanup_authorized((SELECT document_id FROM sound_publications WHERE id=OLD.publication_id),'sound_spans',hex(CAST(OLD.publication_id AS BLOB))||':'||hex(CAST(OLD.id AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable sound authority'); END
;

CREATE TRIGGER sound_spans_sealed BEFORE INSERT ON sound_spans WHEN EXISTS(SELECT 1 FROM sound_publications WHERE id=NEW.publication_id) BEGIN SELECT RAISE(ABORT,'sealed sound publication'); END
;

CREATE TRIGGER cleanup_sound_spans_insert BEFORE INSERT ON sound_spans WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_sound_spans_replace BEFORE INSERT ON sound_spans WHEN EXISTS(SELECT 1 FROM sound_spans prior WHERE hex(CAST(prior.publication_id AS BLOB))||':'||hex(CAST(prior.id AS BLOB))=hex(CAST(NEW.publication_id AS BLOB))||':'||hex(CAST(NEW.id AS BLOB)) OR (prior.publication_id=NEW.publication_id AND prior.ordinal=NEW.ordinal) OR (prior.publication_id=NEW.publication_id AND prior.id=NEW.id) OR (prior.physical_segment_id=NEW.physical_segment_id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_sound_spans_purge BEFORE UPDATE ON sound_spans WHEN (NEW.recall_text IS NOT OLD.recall_text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.recall_text IS '' AND NEW.publication_id IS OLD.publication_id AND NEW.id IS OLD.id AND NEW.ordinal IS OLD.ordinal AND NEW.start_sample IS OLD.start_sample AND NEW.end_sample IS OLD.end_sample AND NEW.pcm_sha256 IS OLD.pcm_sha256 AND NEW.physical_segment_id IS OLD.physical_segment_id AND NEW.entry_sha256 IS OLD.entry_sha256 AND java_cleanup_authorized((SELECT document_id FROM sound_publications WHERE id=OLD.publication_id),'sound_spans',hex(CAST(OLD.publication_id AS BLOB))||':'||hex(CAST(OLD.id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER sound_trace_evidence_no_delete BEFORE DELETE ON sound_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable sound authority'); END
;

CREATE TRIGGER sound_trace_evidence_no_replace BEFORE INSERT ON sound_trace_evidence WHEN EXISTS(SELECT 1 FROM sound_trace_evidence WHERE trace_id=NEW.trace_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable sound trace child'); END
;

CREATE TRIGGER sound_trace_evidence_no_update BEFORE UPDATE ON sound_trace_evidence  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.facts_json IS '[]' AND NEW.trace_id IS OLD.trace_id AND NEW.ordinal IS OLD.ordinal AND NEW.publication_id IS OLD.publication_id AND NEW.span_id IS OLD.span_id AND NEW.facts_sha256 IS OLD.facts_sha256 AND NEW.proof_sha256 IS OLD.proof_sha256 AND java_cleanup_authorized((SELECT document_id FROM sound_publications WHERE id=OLD.publication_id),'sound_trace_evidence',hex(CAST(OLD.trace_id AS BLOB))||':'||hex(CAST(OLD.ordinal AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable sound authority'); END
;

CREATE TRIGGER sound_trace_evidence_sealed BEFORE INSERT ON sound_trace_evidence WHEN EXISTS(SELECT 1 FROM sound_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed sound trace'); END
;

CREATE TRIGGER cleanup_sound_trace_evidence_insert BEFORE INSERT ON sound_trace_evidence WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_sound_trace_evidence_replace BEFORE INSERT ON sound_trace_evidence WHEN EXISTS(SELECT 1 FROM sound_trace_evidence prior WHERE hex(CAST(prior.trace_id AS BLOB))||':'||hex(CAST(prior.ordinal AS BLOB))=hex(CAST(NEW.trace_id AS BLOB))||':'||hex(CAST(NEW.ordinal AS BLOB)) OR (prior.trace_id=NEW.trace_id AND prior.ordinal=NEW.ordinal)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_sound_trace_evidence_purge BEFORE UPDATE ON sound_trace_evidence WHEN (NEW.facts_json IS NOT OLD.facts_json OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.facts_json IS '[]' AND NEW.trace_id IS OLD.trace_id AND NEW.ordinal IS OLD.ordinal AND NEW.publication_id IS OLD.publication_id AND NEW.span_id IS OLD.span_id AND NEW.facts_sha256 IS OLD.facts_sha256 AND NEW.proof_sha256 IS OLD.proof_sha256 AND java_cleanup_authorized((SELECT document_id FROM sound_publications WHERE id=OLD.publication_id),'sound_trace_evidence',hex(CAST(OLD.trace_id AS BLOB))||':'||hex(CAST(OLD.ordinal AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER video_av_trace_evidence_no_delete BEFORE DELETE ON video_av_trace_evidence BEGIN SELECT RAISE(ABORT,'immutable video audiovisual authority'); END
;

CREATE TRIGGER video_av_trace_evidence_no_replace BEFORE INSERT ON video_av_trace_evidence WHEN EXISTS(SELECT 1 FROM video_av_trace_evidence WHERE trace_id=NEW.trace_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable video audiovisual trace child'); END
;

CREATE TRIGGER video_av_trace_evidence_no_update BEFORE UPDATE ON video_av_trace_evidence  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.facts_json IS '[]' AND NEW.trace_id IS OLD.trace_id AND NEW.ordinal IS OLD.ordinal AND NEW.publication_id IS OLD.publication_id AND NEW.window_id IS OLD.window_id AND NEW.facts_sha256 IS OLD.facts_sha256 AND NEW.proof_sha256 IS OLD.proof_sha256 AND java_cleanup_authorized((SELECT document_id FROM video_av_publications WHERE id=OLD.publication_id),'video_av_trace_evidence',hex(CAST(OLD.trace_id AS BLOB))||':'||hex(CAST(OLD.ordinal AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable video audiovisual authority'); END
;

CREATE TRIGGER video_av_trace_evidence_sealed BEFORE INSERT ON video_av_trace_evidence WHEN EXISTS(SELECT 1 FROM video_av_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed video audiovisual trace'); END
;

CREATE TRIGGER cleanup_video_av_trace_evidence_insert BEFORE INSERT ON video_av_trace_evidence WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_video_av_trace_evidence_replace BEFORE INSERT ON video_av_trace_evidence WHEN EXISTS(SELECT 1 FROM video_av_trace_evidence prior WHERE hex(CAST(prior.trace_id AS BLOB))||':'||hex(CAST(prior.ordinal AS BLOB))=hex(CAST(NEW.trace_id AS BLOB))||':'||hex(CAST(NEW.ordinal AS BLOB)) OR (prior.trace_id=NEW.trace_id AND prior.ordinal=NEW.ordinal)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER cleanup_video_av_trace_evidence_purge BEFORE UPDATE ON video_av_trace_evidence WHEN (NEW.facts_json IS NOT OLD.facts_json OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.facts_json IS '[]' AND NEW.trace_id IS OLD.trace_id AND NEW.ordinal IS OLD.ordinal AND NEW.publication_id IS OLD.publication_id AND NEW.window_id IS OLD.window_id AND NEW.facts_sha256 IS OLD.facts_sha256 AND NEW.proof_sha256 IS OLD.proof_sha256 AND java_cleanup_authorized((SELECT document_id FROM video_av_publications WHERE id=OLD.publication_id),'video_av_trace_evidence',hex(CAST(OLD.trace_id AS BLOB))||':'||hex(CAST(OLD.ordinal AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER cleanup_completed_payloads BEFORE UPDATE ON document_cleanups WHEN NEW.state='completed' AND (NOT EXISTS(SELECT 1 FROM cleanup_plans p WHERE p.cleanup_id=NEW.id AND p.document_id=NEW.document_id AND p.source_sha256=NEW.source_sha256) OR EXISTS(SELECT 1 FROM corpus_documents p WHERE p.document_id=NEW.document_id AND (payload_purged!=1 OR NOT (p.original_blob IS x''))) OR EXISTS(SELECT 1 FROM sound_originals p WHERE p.document_id=NEW.document_id AND (payload_purged!=1 OR NOT (p.original_blob IS x''))) OR EXISTS(SELECT 1 FROM video_av_originals p WHERE p.document_id=NEW.document_id AND (payload_purged!=1 OR NOT (p.original_blob IS x''))) OR EXISTS(SELECT 1 FROM corpus_pages p WHERE (SELECT document_id FROM corpus_revisions WHERE id=p.revision_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.text IS ''))) OR EXISTS(SELECT 1 FROM corpus_segments p WHERE (SELECT document_id FROM corpus_revisions WHERE id=p.revision_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.text IS ''))) OR EXISTS(SELECT 1 FROM image_evidence p WHERE (SELECT document_id FROM corpus_revisions WHERE id=p.revision_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.recall_text IS ''))) OR EXISTS(SELECT 1 FROM audio_spans p WHERE (SELECT document_id FROM corpus_revisions WHERE id=p.revision_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.text IS ''))) OR EXISTS(SELECT 1 FROM video_frames p WHERE (SELECT document_id FROM corpus_revisions WHERE id=p.revision_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.frame_blob IS x'' AND p.recall_text IS ''))) OR EXISTS(SELECT 1 FROM video_transcript_spans p WHERE (SELECT document_id FROM corpus_revisions WHERE id=p.revision_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.text IS ''))) OR EXISTS(SELECT 1 FROM video_frame_ocr p WHERE (SELECT document_id FROM corpus_revisions WHERE id=p.revision_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.text IS ''))) OR EXISTS(SELECT 1 FROM video_ocr_segments p WHERE (SELECT document_id FROM corpus_revisions WHERE id=p.revision_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.text IS ''))) OR EXISTS(SELECT 1 FROM video_subtitle_tracks p WHERE (SELECT document_id FROM corpus_revisions WHERE id=p.revision_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.text IS ''))) OR EXISTS(SELECT 1 FROM video_subtitle_cues p WHERE (SELECT document_id FROM corpus_revisions WHERE id=p.revision_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.text IS ''))) OR EXISTS(SELECT 1 FROM synopsis_entries p WHERE (SELECT document_id FROM synopsis_tasks WHERE id=p.task_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.text IS ''))) OR EXISTS(SELECT 1 FROM sound_spans p WHERE (SELECT document_id FROM sound_publications WHERE id=p.publication_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.recall_text IS ''))) OR EXISTS(SELECT 1 FROM sound_trace_evidence p WHERE (SELECT document_id FROM sound_publications WHERE id=p.publication_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.facts_json IS '[]'))) OR EXISTS(SELECT 1 FROM video_av_trace_evidence p WHERE (SELECT document_id FROM video_av_publications WHERE id=p.publication_id)=NEW.document_id AND (payload_purged!=1 OR NOT (p.facts_json IS '[]')))) BEGIN SELECT RAISE(ABORT,'cleanup payloads remain'); END
;

CREATE TRIGGER image_vector_bindings_no_replace BEFORE INSERT ON image_vector_bindings WHEN EXISTS(SELECT 1 FROM image_vector_bindings WHERE publication_id=NEW.publication_id AND origin_vector_publication_id=NEW.origin_vector_publication_id) BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END
;

CREATE TRIGGER image_vector_bindings_no_update BEFORE UPDATE ON image_vector_bindings BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END
;

CREATE TRIGGER image_vector_bindings_no_delete BEFORE DELETE ON image_vector_bindings BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END
;

CREATE TRIGGER image_vector_publications_no_binding_shadow BEFORE INSERT ON image_vector_publications
WHEN EXISTS(SELECT 1 FROM image_vector_bindings b JOIN image_vector_publications v ON v.id=b.origin_vector_publication_id WHERE b.publication_id=NEW.publication_id
  AND v.embedding_identity=NEW.embedding_identity AND v.projection_identity=NEW.projection_identity AND v.model_revision=NEW.model_revision AND v.dimensions=NEW.dimensions)
BEGIN SELECT RAISE(ABORT,'duplicate effective vector profile'); END
;

CREATE TRIGGER audio_vector_bindings_no_replace BEFORE INSERT ON audio_vector_bindings WHEN EXISTS(SELECT 1 FROM audio_vector_bindings WHERE publication_id=NEW.publication_id AND origin_vector_publication_id=NEW.origin_vector_publication_id) BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END
;

CREATE TRIGGER audio_vector_bindings_no_update BEFORE UPDATE ON audio_vector_bindings BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END
;

CREATE TRIGGER audio_vector_bindings_no_delete BEFORE DELETE ON audio_vector_bindings BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END
;

CREATE TRIGGER audio_vector_publications_no_binding_shadow BEFORE INSERT ON audio_vector_publications
WHEN EXISTS(SELECT 1 FROM audio_vector_bindings b JOIN audio_vector_publications v ON v.id=b.origin_vector_publication_id WHERE b.publication_id=NEW.publication_id
  AND v.embedding_identity=NEW.embedding_identity AND v.projection_identity=NEW.projection_identity AND v.model_revision=NEW.model_revision AND v.dimensions=NEW.dimensions)
BEGIN SELECT RAISE(ABORT,'duplicate effective vector profile'); END
;

CREATE TRIGGER ingestion_jobs_no_delete BEFORE DELETE ON ingestion_jobs BEGIN SELECT RAISE(ABORT,'immutable corpus history'); END
;

CREATE TRIGGER ingestion_transition BEFORE UPDATE ON ingestion_jobs WHEN NOT ((OLD.state='queued' AND NEW.state IN ('processing','cancelled') AND NEW.attempt=OLD.attempt) OR (OLD.state='processing' AND NEW.state IN ('parsed','failed','cancelled') AND NEW.attempt=OLD.attempt) OR (OLD.state IN ('failed','cancelled') AND NEW.state='queued' AND NEW.attempt=OLD.attempt+1)) BEGIN SELECT RAISE(ABORT,'invalid ingestion transition'); END
;

CREATE TRIGGER immutable_identity BEFORE UPDATE OF filename,workspace_id,document_type,mime_type,active_revision_id,source_sha256,size_bytes ON documents  WHEN (java_replacement_authorized(OLD.id)=0) AND (1) BEGIN SELECT RAISE(ABORT,'immutable source identity'); END
;

CREATE TRIGGER corpus_pointer_identity BEFORE UPDATE OF parsed_revision_id ON corpus_documents   WHEN (java_replacement_authorized(OLD.document_id)=0) AND (NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.original_blob IS x'' AND NEW.document_id IS OLD.document_id AND NEW.initial_revision_id IS OLD.initial_revision_id AND NEW.parsed_revision_id IS OLD.parsed_revision_id AND NEW.active_revision_id IS OLD.active_revision_id AND java_cleanup_authorized(OLD.document_id,'corpus_documents',hex(CAST(OLD.document_id AS BLOB)))=1) AND (OLD.parsed_revision_id IS NOT NULL OR NEW.parsed_revision_id IS NULL OR NEW.parsed_revision_id!=OLD.initial_revision_id OR NOT EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=NEW.parsed_revision_id AND r.parsed_at IS NOT NULL))) BEGIN SELECT RAISE(ABORT,'invalid parsed revision'); END
;

CREATE TRIGGER corpus_source_identity BEFORE UPDATE OF document_id,original_blob,initial_revision_id ON corpus_documents   WHEN (java_replacement_authorized(OLD.document_id)=0) AND (NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.original_blob IS x'' AND NEW.document_id IS OLD.document_id AND NEW.initial_revision_id IS OLD.initial_revision_id AND NEW.parsed_revision_id IS OLD.parsed_revision_id AND NEW.active_revision_id IS OLD.active_revision_id AND java_cleanup_authorized(OLD.document_id,'corpus_documents',hex(CAST(OLD.document_id AS BLOB)))=1) AND (1)) BEGIN SELECT RAISE(ABORT,'immutable corpus source'); END
;

CREATE TRIGGER cleanup_corpus_documents_purge BEFORE UPDATE ON corpus_documents  WHEN (java_replacement_authorized(OLD.document_id)=0) AND ((NEW.original_blob IS NOT OLD.original_blob OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.original_blob IS x'' AND NEW.document_id IS OLD.document_id AND NEW.initial_revision_id IS OLD.initial_revision_id AND NEW.parsed_revision_id IS OLD.parsed_revision_id AND NEW.active_revision_id IS OLD.active_revision_id AND java_cleanup_authorized(OLD.document_id,'corpus_documents',hex(CAST(OLD.document_id AS BLOB)))=1)) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER sound_originals_no_update BEFORE UPDATE ON sound_originals
  WHEN (java_replacement_authorized(OLD.document_id)=0) AND (NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.original_blob IS x'' AND NEW.document_id IS OLD.document_id AND NEW.source_revision_id IS OLD.source_revision_id AND NEW.source_sha256 IS OLD.source_sha256 AND NEW.filename IS OLD.filename AND NEW.media_type IS OLD.media_type AND NEW.size_bytes IS OLD.size_bytes AND NEW.created_at IS OLD.created_at AND java_cleanup_authorized(OLD.document_id,'sound_originals',hex(CAST(OLD.document_id AS BLOB)))=1) AND (NEW.document_id IS NOT OLD.document_id OR NEW.source_revision_id IS NOT OLD.source_revision_id
  OR NEW.source_sha256 IS NOT OLD.source_sha256 OR NEW.filename IS NOT OLD.filename
  OR NEW.media_type IS NOT OLD.media_type OR NEW.size_bytes IS NOT OLD.size_bytes
  OR NEW.created_at IS NOT OLD.created_at
  OR (NEW.original_blob IS NOT OLD.original_blob AND NOT(length(NEW.original_blob)=0
    AND EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=OLD.document_id))))) BEGIN SELECT RAISE(ABORT,'immutable sound original'); END
;

CREATE TRIGGER cleanup_sound_originals_purge BEFORE UPDATE ON sound_originals  WHEN (java_replacement_authorized(OLD.document_id)=0) AND ((NEW.original_blob IS NOT OLD.original_blob OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.original_blob IS x'' AND NEW.document_id IS OLD.document_id AND NEW.source_revision_id IS OLD.source_revision_id AND NEW.source_sha256 IS OLD.source_sha256 AND NEW.filename IS OLD.filename AND NEW.media_type IS OLD.media_type AND NEW.size_bytes IS OLD.size_bytes AND NEW.created_at IS OLD.created_at AND java_cleanup_authorized(OLD.document_id,'sound_originals',hex(CAST(OLD.document_id AS BLOB)))=1)) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER video_av_originals_no_update BEFORE UPDATE ON video_av_originals
  WHEN (java_replacement_authorized(OLD.document_id)=0) AND (NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.original_blob IS x'' AND NEW.document_id IS OLD.document_id AND NEW.source_revision_id IS OLD.source_revision_id AND NEW.source_sha256 IS OLD.source_sha256 AND NEW.filename IS OLD.filename AND NEW.media_type IS OLD.media_type AND NEW.size_bytes IS OLD.size_bytes AND NEW.created_at IS OLD.created_at AND java_cleanup_authorized(OLD.document_id,'video_av_originals',hex(CAST(OLD.document_id AS BLOB)))=1) AND (NEW.document_id IS NOT OLD.document_id OR NEW.source_revision_id IS NOT OLD.source_revision_id
  OR NEW.source_sha256 IS NOT OLD.source_sha256 OR NEW.filename IS NOT OLD.filename
  OR NEW.media_type IS NOT OLD.media_type OR NEW.size_bytes IS NOT OLD.size_bytes
  OR NEW.created_at IS NOT OLD.created_at
  OR (NEW.original_blob IS NOT OLD.original_blob AND NOT(length(NEW.original_blob)=0
    AND EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=OLD.document_id))))) BEGIN SELECT RAISE(ABORT,'immutable video audiovisual original'); END
;

CREATE TRIGGER cleanup_video_av_originals_purge BEFORE UPDATE ON video_av_originals  WHEN (java_replacement_authorized(OLD.document_id)=0) AND ((NEW.original_blob IS NOT OLD.original_blob OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.original_blob IS x'' AND NEW.document_id IS OLD.document_id AND NEW.source_revision_id IS OLD.source_revision_id AND NEW.source_sha256 IS OLD.source_sha256 AND NEW.filename IS OLD.filename AND NEW.media_type IS OLD.media_type AND NEW.size_bytes IS OLD.size_bytes AND NEW.created_at IS OLD.created_at AND java_cleanup_authorized(OLD.document_id,'video_av_originals',hex(CAST(OLD.document_id AS BLOB)))=1)) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER ingestion_replacement_identity BEFORE INSERT ON ingestion_jobs WHEN NEW.replacement_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM document_replacements u JOIN corpus_revisions r ON r.id=u.candidate_revision_id AND r.document_id=u.document_id JOIN document_original_revisions o ON o.revision_id=r.id AND o.document_id=r.document_id JOIN documents d ON d.id=u.document_id WHERE u.id=NEW.replacement_id AND u.pipeline='corpus' AND u.document_id=NEW.document_id AND u.candidate_revision_id=NEW.revision_id AND u.state='stored' AND d.active_revision_id=u.base_revision_id AND o.source_sha256=r.source_sha256 AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id)) BEGIN SELECT RAISE(ABORT,'invalid replacement ingestion identity'); END
;

CREATE TRIGGER ingestion_identity BEFORE UPDATE OF id,document_id,revision_id,created_by,created_at,replacement_id ON ingestion_jobs BEGIN SELECT RAISE(ABORT,'immutable ingestion identity'); END
;

CREATE TRIGGER active_corpus_replacement_identity BEFORE UPDATE ON active_corpus_publications WHEN java_replacement_authorized(OLD.document_id)=1 AND NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id JOIN document_replacements u ON u.id=j.replacement_id JOIN documents d ON d.id=u.document_id WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id AND NEW.document_id=OLD.document_id AND u.document_id=OLD.document_id AND u.base_publication_id=OLD.publication_id AND u.base_revision_id=OLD.revision_id AND u.candidate_revision_id=NEW.revision_id AND d.active_revision_id=NEW.revision_id AND d.source_sha256=p.source_sha256 AND u.state='indexing') BEGIN SELECT RAISE(ABORT,'invalid replacement publication switch'); END
;

CREATE TRIGGER active_corpus_replacement_initial BEFORE INSERT ON active_corpus_publications WHEN java_replacement_authorized(NEW.document_id)=1 AND NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id JOIN document_replacements u ON u.id=j.replacement_id WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id AND u.base_publication_id IS NULL AND u.candidate_revision_id=NEW.revision_id AND u.state='indexing') BEGIN SELECT RAISE(ABORT,'invalid replacement initial switch'); END
;

CREATE TRIGGER document_original_revisions_no_replace BEFORE INSERT ON document_original_revisions WHEN EXISTS(SELECT 1 FROM document_original_revisions WHERE revision_id=NEW.revision_id) BEGIN SELECT RAISE(ABORT,'immutable original revision'); END
;

CREATE TRIGGER document_original_revisions_no_delete BEFORE DELETE ON document_original_revisions BEGIN SELECT RAISE(ABORT,'immutable original revision'); END
;

CREATE TRIGGER document_original_revisions_no_update BEFORE UPDATE ON document_original_revisions WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND length(NEW.original_blob)=0 AND NEW.revision_id IS OLD.revision_id AND NEW.document_id IS OLD.document_id AND NEW.filename IS OLD.filename AND NEW.document_type IS OLD.document_type AND NEW.media_type IS OLD.media_type AND NEW.source_sha256 IS OLD.source_sha256 AND NEW.size_bytes IS OLD.size_bytes AND NEW.created_at IS OLD.created_at AND java_cleanup_authorized(OLD.document_id,'document_original_revisions',hex(CAST(OLD.revision_id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'immutable original revision'); END
;

CREATE TRIGGER document_replacements_identity BEFORE UPDATE ON document_replacements WHEN NEW.id IS NOT OLD.id OR NEW.document_id IS NOT OLD.document_id OR NEW.base_revision_id IS NOT OLD.base_revision_id OR NEW.base_publication_id IS NOT OLD.base_publication_id OR NEW.candidate_revision_id IS NOT OLD.candidate_revision_id OR NEW.pipeline IS NOT OLD.pipeline OR NEW.created_by IS NOT OLD.created_by OR NEW.created_at IS NOT OLD.created_at OR OLD.state='published' OR (NEW.state='published' AND java_replacement_authorized(OLD.document_id)!=1) BEGIN SELECT RAISE(ABORT,'immutable replacement identity'); END
;

CREATE TRIGGER document_replacements_no_replace BEFORE INSERT ON document_replacements WHEN EXISTS(SELECT 1 FROM document_replacements WHERE id=NEW.id OR candidate_revision_id=NEW.candidate_revision_id) BEGIN SELECT RAISE(ABORT,'immutable replacement'); END
;

CREATE TRIGGER document_replacements_no_delete BEFORE DELETE ON document_replacements BEGIN SELECT RAISE(ABORT,'immutable replacement'); END
;

CREATE TRIGGER cleanup_completed_original_revisions BEFORE UPDATE ON document_cleanups WHEN NEW.state='completed' AND EXISTS(SELECT 1 FROM document_original_revisions o WHERE o.document_id=NEW.document_id AND (o.payload_purged!=1 OR length(o.original_blob)!=0)) BEGIN SELECT RAISE(ABORT,'original revision payload remains'); END
;

CREATE TRIGGER active_corpus_publications_no_update BEFORE UPDATE ON active_corpus_publications
  WHEN (java_model_rebuild_authorized(OLD.document_id)=0) AND ((java_replacement_authorized(OLD.document_id)=0) AND (NEW.document_id IS NOT OLD.document_id OR NEW.revision_id IS NOT OLD.revision_id
  OR NOT EXISTS(SELECT 1 FROM index_publications p
    JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
      AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
    JOIN index_publications base ON base.id=OLD.publication_id AND base.document_id=OLD.document_id AND base.revision_id=OLD.revision_id
    WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id
      AND j.rebuild_sequence>0 AND j.base_publication_id=OLD.publication_id
      AND p.id!=base.id AND p.projection_generation_id!=base.projection_generation_id
      AND p.source_sha256=base.source_sha256 AND p.parser_revision=base.parser_revision
      AND p.embedding_identity=base.embedding_identity AND p.projection_identity=base.projection_identity
      AND p.model_revision=base.model_revision AND p.dimensions=base.dimensions
      AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id)
      AND (j.base_vector_set_sha256 IS NOT NULL OR (NOT EXISTS(SELECT 1 FROM image_vector_publications WHERE publication_id=OLD.publication_id) AND NOT EXISTS(SELECT 1 FROM image_vector_bindings WHERE publication_id=OLD.publication_id) AND NOT EXISTS(SELECT 1 FROM audio_vector_bindings WHERE publication_id=OLD.publication_id)
      AND NOT EXISTS(SELECT 1 FROM audio_vector_publications WHERE publication_id=OLD.publication_id))) AND NOT EXISTS(SELECT 1 FROM image_vector_publications v WHERE (v.publication_id=OLD.publication_id OR EXISTS(SELECT 1 FROM image_vector_bindings b WHERE b.publication_id=OLD.publication_id AND b.origin_vector_publication_id=v.id)) AND NOT EXISTS(SELECT 1 FROM image_vector_bindings n WHERE n.publication_id=NEW.publication_id AND n.origin_vector_publication_id=v.id AND n.inherited_from_publication_id=OLD.publication_id)) AND NOT EXISTS(SELECT 1 FROM image_vector_bindings n WHERE n.publication_id=NEW.publication_id AND NOT EXISTS(SELECT 1 FROM image_vector_publications v WHERE v.id=n.origin_vector_publication_id AND (v.publication_id=OLD.publication_id OR EXISTS(SELECT 1 FROM image_vector_bindings b WHERE b.publication_id=OLD.publication_id AND b.origin_vector_publication_id=v.id)))) AND NOT EXISTS(SELECT 1 FROM audio_vector_publications v WHERE (v.publication_id=OLD.publication_id OR EXISTS(SELECT 1 FROM audio_vector_bindings b WHERE b.publication_id=OLD.publication_id AND b.origin_vector_publication_id=v.id)) AND NOT EXISTS(SELECT 1 FROM audio_vector_bindings n WHERE n.publication_id=NEW.publication_id AND n.origin_vector_publication_id=v.id AND n.inherited_from_publication_id=OLD.publication_id)) AND NOT EXISTS(SELECT 1 FROM audio_vector_bindings n WHERE n.publication_id=NEW.publication_id AND NOT EXISTS(SELECT 1 FROM audio_vector_publications v WHERE v.id=n.origin_vector_publication_id AND (v.publication_id=OLD.publication_id OR EXISTS(SELECT 1 FROM audio_vector_bindings b WHERE b.publication_id=OLD.publication_id AND b.origin_vector_publication_id=v.id))))))) BEGIN SELECT RAISE(ABORT,'immutable index publication'); END
;

CREATE TRIGGER active_corpus_model_rebuild_initial BEFORE INSERT ON active_corpus_publications WHEN java_model_rebuild_authorized(NEW.document_id)=1 AND NOT (EXISTS(SELECT 1 FROM model_rebuild_items i JOIN model_rebuilds b ON b.id=i.batch_id
  JOIN index_publications p ON p.id=i.publication_id
  JOIN indexing_jobs j ON j.id=i.job_id AND j.model_rebuild_id=b.id AND j.state='prepared'
    AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN documents d ON d.id=i.document_id AND d.workspace_id=b.workspace_id
  JOIN corpus_documents c ON c.document_id=d.id AND c.parsed_revision_id=i.revision_id
  WHERE i.document_id=NEW.document_id AND i.publication_id=NEW.publication_id AND i.revision_id=NEW.revision_id
    AND b.state='applying' AND i.state='prepared' AND java_model_rebuild_batch_authorized(b.id)=1
    AND p.job_id=j.id AND p.document_id=i.document_id AND p.revision_id=i.revision_id
    AND p.source_sha256=i.source_sha256 AND d.active_revision_id=i.revision_id AND d.source_sha256=i.source_sha256
    AND p.parser_revision=i.parser_revision AND p.embedding_identity=b.embedding_identity
    AND p.projection_identity=b.projection_identity AND p.model_revision=b.model_revision AND p.dimensions=b.dimensions
    AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id)
 AND i.base_publication_id IS NULL)) BEGIN SELECT RAISE(ABORT,'invalid model rebuild initial publication'); END
;

CREATE TRIGGER active_corpus_model_rebuild_switch BEFORE UPDATE ON active_corpus_publications WHEN java_model_rebuild_authorized(NEW.document_id)=1 AND (NEW.document_id IS NOT OLD.document_id OR NEW.revision_id IS NOT OLD.revision_id OR NOT (EXISTS(SELECT 1 FROM model_rebuild_items i JOIN model_rebuilds b ON b.id=i.batch_id
  JOIN index_publications p ON p.id=i.publication_id
  JOIN indexing_jobs j ON j.id=i.job_id AND j.model_rebuild_id=b.id AND j.state='prepared'
    AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN documents d ON d.id=i.document_id AND d.workspace_id=b.workspace_id
  JOIN corpus_documents c ON c.document_id=d.id AND c.parsed_revision_id=i.revision_id
  WHERE i.document_id=NEW.document_id AND i.publication_id=NEW.publication_id AND i.revision_id=NEW.revision_id
    AND b.state='applying' AND i.state='prepared' AND java_model_rebuild_batch_authorized(b.id)=1
    AND p.job_id=j.id AND p.document_id=i.document_id AND p.revision_id=i.revision_id
    AND p.source_sha256=i.source_sha256 AND d.active_revision_id=i.revision_id AND d.source_sha256=i.source_sha256
    AND p.parser_revision=i.parser_revision AND p.embedding_identity=b.embedding_identity
    AND p.projection_identity=b.projection_identity AND p.model_revision=b.model_revision AND p.dimensions=b.dimensions
    AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id)
 AND i.base_publication_id=OLD.publication_id))) BEGIN SELECT RAISE(ABORT,'invalid model rebuild publication switch'); END
;

CREATE TRIGGER text_runtime_selection_batch BEFORE UPDATE ON text_runtime_selection
WHEN NEW.batch_id IS NOT NULL AND (java_model_rebuild_batch_authorized(NEW.batch_id)!=1 OR NOT EXISTS(
  SELECT 1 FROM model_rebuilds b WHERE b.id=NEW.batch_id AND b.state='applying'
    AND b.target_version=NEW.active_version AND b.configuration_sha256=NEW.configuration_sha256 AND b.anchor_sha256=NEW.anchor_sha256
    AND b.total_documents=(SELECT COUNT(*) FROM model_rebuild_items WHERE batch_id=b.id)
    AND NOT EXISTS(SELECT 1 FROM model_rebuild_items i LEFT JOIN active_corpus_publications a ON a.document_id=i.document_id
      WHERE i.batch_id=b.id AND (i.state!='published' OR a.publication_id IS NOT i.publication_id OR a.revision_id IS NOT i.revision_id))))
BEGIN SELECT RAISE(ABORT,'incomplete model rebuild configuration switch'); END
;

CREATE TRIGGER text_runtime_selection_pending BEFORE UPDATE ON text_runtime_selection WHEN NEW.batch_id IS NULL AND EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER text_runtime_selection_initialized BEFORE UPDATE ON text_runtime_selection WHEN OLD.initialized=1 AND NEW.initialized!=1 BEGIN SELECT RAISE(ABORT,'immutable runtime selection initialization'); END
;

CREATE TRIGGER text_runtime_selection_no_delete BEFORE DELETE ON text_runtime_selection BEGIN SELECT RAISE(ABORT,'immutable runtime selection'); END
;

CREATE TRIGGER text_runtime_selection_no_replace BEFORE INSERT ON text_runtime_selection WHEN EXISTS(SELECT 1 FROM text_runtime_selection) BEGIN SELECT RAISE(ABORT,'immutable runtime selection'); END
;

CREATE TRIGGER image_vector_bindings_identity BEFORE INSERT ON image_vector_bindings
 WHEN (NEW.model_rebuild_id IS NULL) AND (NOT EXISTS(SELECT 1 FROM index_publications p
  JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN index_publications prior ON prior.id=j.base_publication_id
  JOIN active_corpus_publications a ON a.publication_id=prior.id AND a.document_id=prior.document_id AND a.revision_id=prior.revision_id
  JOIN image_vector_publications v ON v.id=NEW.origin_vector_publication_id
  JOIN index_publications origin ON origin.id=v.publication_id
  WHERE p.id=NEW.publication_id AND NEW.inherited_from_publication_id=prior.id
    AND j.rebuild_sequence>0 AND j.base_vector_set_sha256 IS NOT NULL
    AND p.document_id=prior.document_id AND p.revision_id=prior.revision_id AND p.source_sha256=prior.source_sha256 AND p.parser_revision=prior.parser_revision
    AND p.embedding_identity=prior.embedding_identity AND p.projection_identity=prior.projection_identity AND p.model_revision=prior.model_revision AND p.dimensions=prior.dimensions
    AND origin.document_id=p.document_id AND origin.revision_id=p.revision_id AND origin.source_sha256=p.source_sha256 AND origin.parser_revision=p.parser_revision
    AND origin.embedding_identity=p.embedding_identity AND origin.projection_identity=p.projection_identity AND origin.model_revision=p.model_revision AND origin.dimensions=p.dimensions
    AND origin.segment_count=p.segment_count AND v.document_id=p.document_id AND v.source_revision_id=p.revision_id AND v.source_sha256=p.source_sha256
    AND (v.publication_id=prior.id OR EXISTS(SELECT 1 FROM image_vector_bindings b WHERE b.publication_id=prior.id AND b.origin_vector_publication_id=v.id))
    AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=p.document_id)
    AND EXISTS(SELECT 1 FROM image_publication_entries olde JOIN image_publication_entries newe ON newe.image_evidence_id=olde.image_evidence_id
      JOIN image_evidence e ON e.id=newe.image_evidence_id AND e.revision_id=p.revision_id
      WHERE olde.publication_id=origin.id AND olde.image_evidence_id=v.image_evidence_id AND olde.physical_segment_id=v.base_physical_segment_id AND newe.publication_id=p.id)
    AND NOT EXISTS(SELECT 1 FROM image_vector_publications direct WHERE direct.publication_id=p.id AND direct.embedding_identity=v.embedding_identity AND direct.projection_identity=v.projection_identity AND direct.model_revision=v.model_revision AND direct.dimensions=v.dimensions)
    AND NOT EXISTS(SELECT 1 FROM image_vector_bindings b JOIN image_vector_publications same ON same.id=b.origin_vector_publication_id WHERE b.publication_id=p.id AND same.embedding_identity=v.embedding_identity AND same.projection_identity=v.projection_identity AND same.model_revision=v.model_revision AND same.dimensions=v.dimensions))) BEGIN SELECT RAISE(ABORT,'invalid vector inheritance'); END
;

CREATE TRIGGER image_vector_bindings_model_rebuild_identity BEFORE INSERT ON image_vector_bindings
 WHEN (NEW.model_rebuild_id IS NOT NULL) AND (NOT EXISTS(SELECT 1 FROM index_publications p
  JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN index_publications prior ON prior.id=j.base_publication_id
  JOIN active_corpus_publications a ON a.publication_id=prior.id AND a.document_id=prior.document_id AND a.revision_id=prior.revision_id
  JOIN image_vector_publications v ON v.id=NEW.origin_vector_publication_id
  JOIN index_publications origin ON origin.id=v.publication_id
  WHERE p.id=NEW.publication_id AND NEW.inherited_from_publication_id=prior.id
    AND j.rebuild_sequence>0 AND j.base_vector_set_sha256 IS NOT NULL AND EXISTS(SELECT 1 FROM model_rebuilds grant_batch WHERE grant_batch.id=NEW.model_rebuild_id AND (
  (j.model_rebuild_id=grant_batch.id AND grant_batch.state IN ('queued','running')
    AND p.embedding_identity=grant_batch.embedding_identity AND p.projection_identity=grant_batch.projection_identity
    AND p.model_revision=grant_batch.model_revision AND p.dimensions=grant_batch.dimensions
    AND EXISTS(SELECT 1 FROM model_rebuild_items i WHERE i.batch_id=grant_batch.id AND i.job_id=j.id
      AND i.document_id=p.document_id AND i.base_publication_id=prior.id AND i.base_vector_set_sha256=j.base_vector_set_sha256))
  OR (j.model_rebuild_id IS NULL AND grant_batch.state='completed'
    AND p.embedding_identity=prior.embedding_identity AND p.projection_identity=prior.projection_identity
    AND p.model_revision=prior.model_revision AND p.dimensions=prior.dimensions
    AND EXISTS(SELECT 1 FROM image_vector_bindings previous WHERE previous.publication_id=prior.id
      AND previous.origin_vector_publication_id=v.id AND previous.model_rebuild_id=grant_batch.id))))

    AND p.document_id=prior.document_id AND p.revision_id=prior.revision_id AND p.source_sha256=prior.source_sha256 AND p.parser_revision=prior.parser_revision
    
    AND origin.document_id=p.document_id AND origin.revision_id=p.revision_id AND origin.source_sha256=p.source_sha256 AND origin.parser_revision=p.parser_revision
    
    AND origin.segment_count=p.segment_count AND v.document_id=p.document_id AND v.source_revision_id=p.revision_id AND v.source_sha256=p.source_sha256
    AND (v.publication_id=prior.id OR EXISTS(SELECT 1 FROM image_vector_bindings b WHERE b.publication_id=prior.id AND b.origin_vector_publication_id=v.id))
    AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=p.document_id)
    AND EXISTS(SELECT 1 FROM image_publication_entries olde JOIN image_publication_entries newe ON newe.image_evidence_id=olde.image_evidence_id
      JOIN image_evidence e ON e.id=newe.image_evidence_id AND e.revision_id=p.revision_id
      WHERE olde.publication_id=origin.id AND olde.image_evidence_id=v.image_evidence_id AND olde.physical_segment_id=v.base_physical_segment_id AND newe.publication_id=p.id)
    AND NOT EXISTS(SELECT 1 FROM image_vector_publications direct WHERE direct.publication_id=p.id AND direct.embedding_identity=v.embedding_identity AND direct.projection_identity=v.projection_identity AND direct.model_revision=v.model_revision AND direct.dimensions=v.dimensions)
    AND NOT EXISTS(SELECT 1 FROM image_vector_bindings b JOIN image_vector_publications same ON same.id=b.origin_vector_publication_id WHERE b.publication_id=p.id AND same.embedding_identity=v.embedding_identity AND same.projection_identity=v.projection_identity AND same.model_revision=v.model_revision AND same.dimensions=v.dimensions))) BEGIN SELECT RAISE(ABORT,'invalid vector inheritance'); END
;

CREATE TRIGGER audio_vector_bindings_identity BEFORE INSERT ON audio_vector_bindings
 WHEN (NEW.model_rebuild_id IS NULL) AND (NOT EXISTS(SELECT 1 FROM index_publications p
  JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN index_publications prior ON prior.id=j.base_publication_id
  JOIN active_corpus_publications a ON a.publication_id=prior.id AND a.document_id=prior.document_id AND a.revision_id=prior.revision_id
  JOIN audio_vector_publications v ON v.id=NEW.origin_vector_publication_id
  JOIN index_publications origin ON origin.id=v.publication_id
  WHERE p.id=NEW.publication_id AND NEW.inherited_from_publication_id=prior.id
    AND j.rebuild_sequence>0 AND j.base_vector_set_sha256 IS NOT NULL
    AND p.document_id=prior.document_id AND p.revision_id=prior.revision_id AND p.source_sha256=prior.source_sha256 AND p.parser_revision=prior.parser_revision
    AND p.embedding_identity=prior.embedding_identity AND p.projection_identity=prior.projection_identity AND p.model_revision=prior.model_revision AND p.dimensions=prior.dimensions
    AND origin.document_id=p.document_id AND origin.revision_id=p.revision_id AND origin.source_sha256=p.source_sha256 AND origin.parser_revision=p.parser_revision
    AND origin.embedding_identity=p.embedding_identity AND origin.projection_identity=p.projection_identity AND origin.model_revision=p.model_revision AND origin.dimensions=p.dimensions
    AND origin.segment_count=p.segment_count AND v.document_id=p.document_id AND v.source_revision_id=p.revision_id AND v.source_sha256=p.source_sha256
    AND (v.publication_id=prior.id OR EXISTS(SELECT 1 FROM audio_vector_bindings b WHERE b.publication_id=prior.id AND b.origin_vector_publication_id=v.id))
    AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=p.document_id)
    AND v.segment_count=(SELECT COUNT(*) FROM audio_vector_entries WHERE audio_vector_publication_id=v.id)
    AND v.segment_count=(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)
    AND NOT EXISTS(SELECT 1 FROM audio_vector_entries ve
      WHERE ve.audio_vector_publication_id=v.id AND NOT EXISTS(SELECT 1 FROM audio_publication_entries olde
        JOIN audio_publication_entries newe ON newe.audio_span_id=olde.audio_span_id
        JOIN audio_spans e ON e.id=newe.audio_span_id AND e.revision_id=p.revision_id AND e.ordinal=ve.ordinal AND e.index_ordinal IS NOT NULL
        WHERE olde.publication_id=origin.id AND olde.audio_span_id=ve.audio_evidence_id AND olde.physical_segment_id=ve.base_physical_segment_id AND newe.publication_id=p.id))
    AND NOT EXISTS(SELECT 1 FROM audio_vector_publications direct WHERE direct.publication_id=p.id AND direct.embedding_identity=v.embedding_identity AND direct.projection_identity=v.projection_identity AND direct.model_revision=v.model_revision AND direct.dimensions=v.dimensions)
    AND NOT EXISTS(SELECT 1 FROM audio_vector_bindings b JOIN audio_vector_publications same ON same.id=b.origin_vector_publication_id WHERE b.publication_id=p.id AND same.embedding_identity=v.embedding_identity AND same.projection_identity=v.projection_identity AND same.model_revision=v.model_revision AND same.dimensions=v.dimensions))) BEGIN SELECT RAISE(ABORT,'invalid vector inheritance'); END
;

CREATE TRIGGER audio_vector_bindings_model_rebuild_identity BEFORE INSERT ON audio_vector_bindings
 WHEN (NEW.model_rebuild_id IS NOT NULL) AND (NOT EXISTS(SELECT 1 FROM index_publications p
  JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN index_publications prior ON prior.id=j.base_publication_id
  JOIN active_corpus_publications a ON a.publication_id=prior.id AND a.document_id=prior.document_id AND a.revision_id=prior.revision_id
  JOIN audio_vector_publications v ON v.id=NEW.origin_vector_publication_id
  JOIN index_publications origin ON origin.id=v.publication_id
  WHERE p.id=NEW.publication_id AND NEW.inherited_from_publication_id=prior.id
    AND j.rebuild_sequence>0 AND j.base_vector_set_sha256 IS NOT NULL AND EXISTS(SELECT 1 FROM model_rebuilds grant_batch WHERE grant_batch.id=NEW.model_rebuild_id AND (
  (j.model_rebuild_id=grant_batch.id AND grant_batch.state IN ('queued','running')
    AND p.embedding_identity=grant_batch.embedding_identity AND p.projection_identity=grant_batch.projection_identity
    AND p.model_revision=grant_batch.model_revision AND p.dimensions=grant_batch.dimensions
    AND EXISTS(SELECT 1 FROM model_rebuild_items i WHERE i.batch_id=grant_batch.id AND i.job_id=j.id
      AND i.document_id=p.document_id AND i.base_publication_id=prior.id AND i.base_vector_set_sha256=j.base_vector_set_sha256))
  OR (j.model_rebuild_id IS NULL AND grant_batch.state='completed'
    AND p.embedding_identity=prior.embedding_identity AND p.projection_identity=prior.projection_identity
    AND p.model_revision=prior.model_revision AND p.dimensions=prior.dimensions
    AND EXISTS(SELECT 1 FROM audio_vector_bindings previous WHERE previous.publication_id=prior.id
      AND previous.origin_vector_publication_id=v.id AND previous.model_rebuild_id=grant_batch.id))))

    AND p.document_id=prior.document_id AND p.revision_id=prior.revision_id AND p.source_sha256=prior.source_sha256 AND p.parser_revision=prior.parser_revision
    
    AND origin.document_id=p.document_id AND origin.revision_id=p.revision_id AND origin.source_sha256=p.source_sha256 AND origin.parser_revision=p.parser_revision
    
    AND origin.segment_count=p.segment_count AND v.document_id=p.document_id AND v.source_revision_id=p.revision_id AND v.source_sha256=p.source_sha256
    AND (v.publication_id=prior.id OR EXISTS(SELECT 1 FROM audio_vector_bindings b WHERE b.publication_id=prior.id AND b.origin_vector_publication_id=v.id))
    AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=p.document_id)
    AND v.segment_count=(SELECT COUNT(*) FROM audio_vector_entries WHERE audio_vector_publication_id=v.id)
    AND v.segment_count=(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)
    AND NOT EXISTS(SELECT 1 FROM audio_vector_entries ve
      WHERE ve.audio_vector_publication_id=v.id AND NOT EXISTS(SELECT 1 FROM audio_publication_entries olde
        JOIN audio_publication_entries newe ON newe.audio_span_id=olde.audio_span_id
        JOIN audio_spans e ON e.id=newe.audio_span_id AND e.revision_id=p.revision_id AND e.ordinal=ve.ordinal AND e.index_ordinal IS NOT NULL
        WHERE olde.publication_id=origin.id AND olde.audio_span_id=ve.audio_evidence_id AND olde.physical_segment_id=ve.base_physical_segment_id AND newe.publication_id=p.id))
    AND NOT EXISTS(SELECT 1 FROM audio_vector_publications direct WHERE direct.publication_id=p.id AND direct.embedding_identity=v.embedding_identity AND direct.projection_identity=v.projection_identity AND direct.model_revision=v.model_revision AND direct.dimensions=v.dimensions)
    AND NOT EXISTS(SELECT 1 FROM audio_vector_bindings b JOIN audio_vector_publications same ON same.id=b.origin_vector_publication_id WHERE b.publication_id=p.id AND same.embedding_identity=v.embedding_identity AND same.projection_identity=v.projection_identity AND same.model_revision=v.model_revision AND same.dimensions=v.dimensions))) BEGIN SELECT RAISE(ABORT,'invalid vector inheritance'); END
;

CREATE TRIGGER model_rebuild_identity BEFORE UPDATE ON model_rebuilds WHEN
  NEW.id IS NOT OLD.id OR NEW.workspace_id IS NOT OLD.workspace_id OR NEW.created_by IS NOT OLD.created_by
  OR NEW.base_active_version IS NOT OLD.base_active_version OR NEW.base_configuration_sha256 IS NOT OLD.base_configuration_sha256
  OR NEW.base_anchor_sha256 IS NOT OLD.base_anchor_sha256 OR NEW.base_batch_id IS NOT OLD.base_batch_id OR NEW.base_selected_at IS NOT OLD.base_selected_at
  OR NEW.target_version IS NOT OLD.target_version OR NEW.configuration_sha256 IS NOT OLD.configuration_sha256 OR NEW.anchor_sha256 IS NOT OLD.anchor_sha256
  OR NEW.embedding_identity IS NOT OLD.embedding_identity OR NEW.projection_identity IS NOT OLD.projection_identity
  OR NEW.model_revision IS NOT OLD.model_revision OR NEW.dimensions IS NOT OLD.dimensions OR NEW.total_documents IS NOT OLD.total_documents OR NEW.created_at IS NOT OLD.created_at
  OR NOT ((OLD.state='queued' AND NEW.state IN ('running','applying','failed'))
    OR (OLD.state='running' AND NEW.state IN ('running','applying','failed'))
    OR (OLD.state='applying' AND NEW.state='failed')
    OR (OLD.state='applying' AND NEW.state='completed' AND java_model_rebuild_batch_authorized(OLD.id)=1))
BEGIN SELECT RAISE(ABORT,'immutable model rebuild'); END
;

CREATE TRIGGER model_rebuild_item_identity BEFORE UPDATE ON model_rebuild_items WHEN
  NEW.batch_id IS NOT OLD.batch_id OR NEW.ordinal IS NOT OLD.ordinal OR NEW.document_id IS NOT OLD.document_id
  OR NEW.revision_id IS NOT OLD.revision_id OR NEW.source_sha256 IS NOT OLD.source_sha256 OR NEW.parser_revision IS NOT OLD.parser_revision
  OR NEW.base_publication_id IS NOT OLD.base_publication_id OR NEW.base_vector_set_sha256 IS NOT OLD.base_vector_set_sha256 OR NEW.job_id IS NOT OLD.job_id
  OR NOT ((OLD.state='queued' AND NEW.state='prepared' AND OLD.publication_id IS NULL AND NEW.publication_id IS NOT NULL
      AND EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
        WHERE p.id=NEW.publication_id AND p.job_id=OLD.job_id AND p.document_id=OLD.document_id AND p.revision_id=OLD.revision_id))
    OR (OLD.state IN ('queued','prepared') AND NEW.state='failed' AND NEW.publication_id IS OLD.publication_id)
    OR (OLD.state='prepared' AND NEW.state='published' AND NEW.publication_id IS OLD.publication_id
      AND java_model_rebuild_authorized(OLD.document_id)=1))
BEGIN SELECT RAISE(ABORT,'immutable model rebuild item'); END
;

CREATE TRIGGER model_rebuilds_no_replace BEFORE INSERT ON model_rebuilds WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'immutable model rebuild history'); END
;

CREATE TRIGGER model_rebuilds_no_delete BEFORE DELETE ON model_rebuilds BEGIN SELECT RAISE(ABORT,'immutable model rebuild history'); END
;

CREATE TRIGGER model_rebuild_items_no_replace BEFORE INSERT ON model_rebuild_items WHEN EXISTS(SELECT 1 FROM model_rebuild_items WHERE batch_id=NEW.batch_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable model rebuild history'); END
;

CREATE TRIGGER model_rebuild_items_no_delete BEFORE DELETE ON model_rebuild_items BEGIN SELECT RAISE(ABORT,'immutable model rebuild history'); END
;

CREATE TRIGGER model_rebuild_item_initial BEFORE INSERT ON model_rebuild_items WHEN NEW.state!='queued' OR NEW.publication_id IS NOT NULL
  OR NOT EXISTS(SELECT 1 FROM model_rebuilds b JOIN documents d ON d.id=NEW.document_id AND d.workspace_id=b.workspace_id
    JOIN corpus_documents c ON c.document_id=d.id AND c.parsed_revision_id=NEW.revision_id
    JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=d.id
    WHERE b.id=NEW.batch_id AND b.state='queued' AND NEW.ordinal<b.total_documents
      AND d.active_revision_id=r.id AND d.source_sha256=NEW.source_sha256 AND r.source_sha256=NEW.source_sha256
      AND r.parser_revision=NEW.parser_revision AND r.parsed_at IS NOT NULL
      AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id))
BEGIN SELECT RAISE(ABORT,'invalid model rebuild source'); END
;

CREATE TRIGGER model_rebuild_block_ingestion_retry BEFORE UPDATE OF state ON ingestion_jobs WHEN NEW.state='queued' AND EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_ingestion_jobs BEFORE INSERT ON ingestion_jobs WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_document_replacements BEFORE INSERT ON document_replacements WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_document_tombstones BEFORE INSERT ON document_tombstones WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_document_cleanups BEFORE INSERT ON document_cleanups WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_documents BEFORE INSERT ON documents WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_sound_originals BEFORE INSERT ON sound_originals WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_video_av_originals BEFORE INSERT ON video_av_originals WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_image_vector_publications BEFORE INSERT ON image_vector_publications WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_audio_vector_publications BEFORE INSERT ON audio_vector_publications WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_sound_publications BEFORE INSERT ON sound_publications WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_video_av_publications BEFORE INSERT ON video_av_publications WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_source_update BEFORE UPDATE OF active_revision_id,source_sha256,document_type ON documents WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_active_insert BEFORE INSERT ON active_corpus_publications WHEN java_model_rebuild_authorized(NEW.document_id)=0 AND EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_active_update BEFORE UPDATE ON active_corpus_publications WHEN java_model_rebuild_authorized(NEW.document_id)=0 AND EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER video_compilations_frozen BEFORE INSERT ON video_compilations WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable video preparation'); END
;

CREATE TRIGGER video_compilations_no_delete BEFORE DELETE ON video_compilations BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_compilations_no_replace BEFORE INSERT ON video_compilations WHEN EXISTS(SELECT 1 FROM video_compilations WHERE revision_id=NEW.revision_id) BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_compilations_no_update BEFORE UPDATE ON video_compilations BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER cleanup_video_frames_insert BEFORE INSERT ON video_frames WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END
;

CREATE TRIGGER cleanup_video_frames_purge BEFORE UPDATE ON video_frames WHEN (NEW.frame_blob IS NOT OLD.frame_blob OR NEW.recall_text IS NOT OLD.recall_text OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.frame_blob IS x'' AND NEW.recall_text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.ordinal IS OLD.ordinal AND NEW.presentation_us IS OLD.presentation_us AND NEW.duration_us IS OLD.duration_us AND NEW.media_type IS OLD.media_type AND NEW.frame_sha256 IS OLD.frame_sha256 AND NEW.width IS OLD.width AND NEW.height IS OLD.height AND NEW.recall_sha256 IS OLD.recall_sha256 AND NEW.description_revision IS OLD.description_revision AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_frames',hex(CAST(OLD.id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END
;

CREATE TRIGGER cleanup_video_frames_replace BEFORE INSERT ON video_frames WHEN EXISTS(SELECT 1 FROM video_frames prior WHERE hex(CAST(prior.id AS BLOB))=hex(CAST(NEW.id AS BLOB)) OR (prior.revision_id=NEW.revision_id AND prior.ordinal=NEW.ordinal) OR (prior.revision_id=NEW.revision_id AND prior.id=NEW.id) OR (prior.id=NEW.id)) BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END
;

CREATE TRIGGER video_frames_frozen BEFORE INSERT ON video_frames WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable video preparation'); END
;

CREATE TRIGGER video_frames_identity BEFORE INSERT ON video_frames
WHEN NOT EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.revision_id
  AND NEW.ordinal<h.frame_count AND NEW.presentation_us<=h.duration_us-NEW.duration_us)
  OR NEW.ordinal!=(SELECT COUNT(*) FROM video_frames WHERE revision_id=NEW.revision_id)
  OR (NEW.ordinal=0 AND NEW.presentation_us!=0)
  OR (NEW.ordinal>0 AND NEW.presentation_us<=(SELECT MAX(presentation_us) FROM video_frames WHERE revision_id=NEW.revision_id))
  OR length(NEW.frame_blob)+(SELECT COALESCE(SUM(length(frame_blob)),0) FROM video_frames WHERE revision_id=NEW.revision_id)>33554432
BEGIN SELECT RAISE(ABORT,'invalid video frame identity'); END
;

CREATE TRIGGER video_frames_no_delete BEFORE DELETE ON video_frames BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_frames_no_replace BEFORE INSERT ON video_frames WHEN EXISTS(SELECT 1 FROM video_frames WHERE id=NEW.id OR (revision_id=NEW.revision_id AND ordinal=NEW.ordinal)) BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER video_frames_no_update BEFORE UPDATE ON video_frames  WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND NEW.frame_blob IS x'' AND NEW.recall_text IS '' AND NEW.id IS OLD.id AND NEW.revision_id IS OLD.revision_id AND NEW.ordinal IS OLD.ordinal AND NEW.presentation_us IS OLD.presentation_us AND NEW.duration_us IS OLD.duration_us AND NEW.media_type IS OLD.media_type AND NEW.frame_sha256 IS OLD.frame_sha256 AND NEW.width IS OLD.width AND NEW.height IS OLD.height AND NEW.recall_sha256 IS OLD.recall_sha256 AND NEW.description_revision IS OLD.description_revision AND java_cleanup_authorized((SELECT document_id FROM corpus_revisions WHERE id=OLD.revision_id),'video_frames',hex(CAST(OLD.id AS BLOB)))=1) AND (1) BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END
;

CREATE TRIGGER active_corpus_publication_complete BEFORE INSERT ON active_corpus_publications
 WHEN (java_model_rebuild_authorized(NEW.document_id)=0) AND (NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
  AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=p.document_id
  WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id
    AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_frame_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_transcript_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_ocr_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_subtitle_publication_entries WHERE publication_id=p.id) AND (r.parser_revision NOT LIKE 'java-audio-compiler-v1:%' OR (r.parsed_at IS NOT NULL
  AND r.page_count=0 AND r.segment_count=0
  AND EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=r.id
    AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
    AND h.span_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id)
    AND h.projection_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL))
  AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)))
 AND (r.parser_revision NOT GLOB 'java-video-compiler-v[1234]:*' OR (r.parsed_at IS NOT NULL AND r.page_count=0 AND r.segment_count=0
AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=r.id
  AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
  AND h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
  AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
    AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
    AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
  AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
    +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
  AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
    WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
      AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=r.id)
)))) BEGIN SELECT RAISE(ABORT,'incomplete index publication'); END
;

CREATE TRIGGER active_corpus_publication_complete_model_rebuild BEFORE INSERT ON active_corpus_publications
 WHEN (java_model_rebuild_authorized(NEW.document_id)=1) AND (NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='prepared'
  AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=p.document_id
  WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id
    AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_frame_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_transcript_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_ocr_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_subtitle_publication_entries WHERE publication_id=p.id) AND (r.parser_revision NOT LIKE 'java-audio-compiler-v1:%' OR (r.parsed_at IS NOT NULL
  AND r.page_count=0 AND r.segment_count=0
  AND EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=r.id
    AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
    AND h.span_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id)
    AND h.projection_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL))
  AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)))
 AND (r.parser_revision NOT GLOB 'java-video-compiler-v[1234]:*' OR (r.parsed_at IS NOT NULL AND r.page_count=0 AND r.segment_count=0
AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=r.id
  AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
  AND h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
  AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
    AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
    AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
  AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
    +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
  AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
    WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
      AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=r.id)
)))) BEGIN SELECT RAISE(ABORT,'incomplete index publication'); END
;

CREATE TRIGGER active_corpus_publication_rebuild_complete BEFORE UPDATE ON active_corpus_publications
 WHEN (java_model_rebuild_authorized(NEW.document_id)=0) AND (NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
  AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=p.document_id
  WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id
    AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_frame_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_transcript_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_ocr_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_subtitle_publication_entries WHERE publication_id=p.id) AND (r.parser_revision NOT LIKE 'java-audio-compiler-v1:%' OR (r.parsed_at IS NOT NULL
  AND r.page_count=0 AND r.segment_count=0
  AND EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=r.id
    AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
    AND h.span_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id)
    AND h.projection_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL))
  AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)))
 AND (r.parser_revision NOT GLOB 'java-video-compiler-v[1234]:*' OR (r.parsed_at IS NOT NULL AND r.page_count=0 AND r.segment_count=0
AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=r.id
  AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
  AND h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
  AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
    AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
    AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
  AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
    +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
  AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
    WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
      AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=r.id)
)))) BEGIN SELECT RAISE(ABORT,'incomplete index publication'); END
;

CREATE TRIGGER active_corpus_publication_rebuild_complete_model_rebuild BEFORE UPDATE ON active_corpus_publications
 WHEN (java_model_rebuild_authorized(NEW.document_id)=1) AND (NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='prepared'
  AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
  JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=p.document_id
  WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id
    AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_frame_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_transcript_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_ocr_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_subtitle_publication_entries WHERE publication_id=p.id) AND (r.parser_revision NOT LIKE 'java-audio-compiler-v1:%' OR (r.parsed_at IS NOT NULL
  AND r.page_count=0 AND r.segment_count=0
  AND EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=r.id
    AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
    AND h.span_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id)
    AND h.projection_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL))
  AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)))
 AND (r.parser_revision NOT GLOB 'java-video-compiler-v[1234]:*' OR (r.parsed_at IS NOT NULL AND r.page_count=0 AND r.segment_count=0
AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=r.id
  AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
  AND h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
  AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
    AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
    AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
  AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
    +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
  AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
    WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
      AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=r.id)
)))) BEGIN SELECT RAISE(ABORT,'incomplete index publication'); END
;

CREATE TRIGGER audio_compilations_no_video_identity BEFORE INSERT ON audio_compilations WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parser_revision GLOB 'java-video-compiler-v[1234]:*') BEGIN SELECT RAISE(ABORT,'video evidence cannot use another source type'); END
;

CREATE TRIGGER corpus_pages_no_video_identity BEFORE INSERT ON corpus_pages WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parser_revision GLOB 'java-video-compiler-v[1234]:*') BEGIN SELECT RAISE(ABORT,'video evidence cannot use another source type'); END
;

CREATE TRIGGER corpus_revision_video_complete BEFORE UPDATE OF parsed_at ON corpus_revisions WHEN NEW.parser_revision LIKE 'java-video-compiler-v1:%' AND NEW.parsed_at IS NOT NULL AND NOT (NEW.page_count=0 AND NEW.segment_count=0
AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.id
  AND h.source_sha256=NEW.source_sha256 AND h.compiler_revision=NEW.parser_revision
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
  AND h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
  AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
    AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
    AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
  AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
    +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
  AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
    WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
      AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=NEW.id)
) BEGIN SELECT RAISE(ABORT,'incomplete video preparation'); END
;

CREATE TRIGGER corpus_revision_video_ocr_complete BEFORE UPDATE OF parsed_at ON corpus_revisions WHEN NEW.parser_revision LIKE 'java-video-compiler-v2:%' AND NEW.parsed_at IS NOT NULL AND NOT (NEW.page_count=0 AND NEW.segment_count=0
AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.id
  AND h.source_sha256=NEW.source_sha256 AND h.compiler_revision=NEW.parser_revision
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
  AND h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
  AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
    AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
    AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
  AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
    +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
  AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
    WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
      AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=NEW.id)
 AND EXISTS(SELECT 1 FROM video_ocr_compilations o JOIN video_compilations h ON h.revision_id=o.revision_id
  WHERE o.revision_id=NEW.id AND o.base_manifest_sha256=h.manifest_sha256 AND o.frame_count=h.frame_count
    AND o.frame_count=(SELECT COUNT(*) FROM video_frame_ocr WHERE revision_id=o.revision_id)
    AND o.projection_count=(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=o.revision_id)
    AND NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=o.revision_id
      AND (f.segment_count!=(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.frame_id=f.frame_id)
        OR f.region_count!=(SELECT COUNT(*) FROM video_ocr_regions b WHERE b.frame_id=f.frame_id)))
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=o.revision_id)<=1500000)
) BEGIN SELECT RAISE(ABORT,'incomplete video OCR preparation'); END
;

CREATE TRIGGER corpus_revision_video_subtitle_complete BEFORE UPDATE OF parsed_at ON corpus_revisions WHEN NEW.parser_revision LIKE 'java-video-compiler-v3:%' AND NEW.parsed_at IS NOT NULL AND NOT (NEW.page_count=0 AND NEW.segment_count=0
AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.id
  AND h.source_sha256=NEW.source_sha256 AND h.compiler_revision=NEW.parser_revision
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
  AND h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
  AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
    AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
    AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
  AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
    +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
  AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
    WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
      AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=NEW.id)
 AND EXISTS(SELECT 1 FROM video_subtitle_compilations s JOIN video_compilations h ON h.revision_id=s.revision_id
  WHERE s.revision_id=NEW.id AND s.base_manifest_sha256=h.manifest_sha256
    AND s.track_count=(SELECT COUNT(*) FROM video_subtitle_tracks WHERE revision_id=s.revision_id)
    AND s.cue_count=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=s.revision_id)
    AND s.cue_count=(SELECT COALESCE(SUM(cue_count),0) FROM video_subtitle_tracks WHERE revision_id=s.revision_id)
    AND s.projection_count=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=s.revision_id AND index_ordinal IS NOT NULL)
    AND h.projection_count+s.projection_count+COALESCE((SELECT projection_count FROM video_ocr_compilations WHERE revision_id=s.revision_id),0)<=4096
    AND NOT EXISTS(SELECT 1 FROM video_subtitle_tracks t WHERE t.revision_id=s.revision_id AND (
      t.cue_count!=(SELECT COUNT(*) FROM video_subtitle_cues WHERE track_id=t.id)
      OR length(t.text)!=(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE track_id=t.id)+max(t.cue_count-1,0)
      OR (t.cue_count>0 AND (t.cue_count-1!=(SELECT MAX(ordinal) FROM video_subtitle_cues WHERE track_id=t.id)
        OR length(t.text)!=(SELECT MAX(end_offset) FROM video_subtitle_cues WHERE track_id=t.id)))))
    AND NOT EXISTS(SELECT 1 FROM video_subtitle_cues c WHERE c.revision_id=s.revision_id AND c.index_ordinal IS NOT NULL
      AND c.index_ordinal!=(SELECT COUNT(*) FROM video_subtitle_cues previous WHERE previous.revision_id=c.revision_id AND previous.index_ordinal IS NOT NULL
        AND (previous.stream_index<c.stream_index OR (previous.stream_index=c.stream_index AND previous.ordinal<c.ordinal))))
    AND (SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=s.revision_id)<=500000
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=s.revision_id AND index_ordinal IS NOT NULL)<=1500000
    AND ((s.ocr_expected=0 AND NOT EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=s.revision_id))
      OR (s.ocr_expected=1 AND EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=s.revision_id AND manifest_sha256=s.ocr_manifest_sha256) AND EXISTS(SELECT 1 FROM video_ocr_compilations o JOIN video_compilations h ON h.revision_id=o.revision_id
  WHERE o.revision_id=NEW.id AND o.base_manifest_sha256=h.manifest_sha256 AND o.frame_count=h.frame_count
    AND o.frame_count=(SELECT COUNT(*) FROM video_frame_ocr WHERE revision_id=o.revision_id)
    AND o.projection_count=(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=o.revision_id)
    AND NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=o.revision_id
      AND (f.segment_count!=(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.frame_id=f.frame_id)
        OR f.region_count!=(SELECT COUNT(*) FROM video_ocr_regions b WHERE b.frame_id=f.frame_id)))
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=o.revision_id)<=1500000)
)))
) BEGIN SELECT RAISE(ABORT,'incomplete video subtitle preparation'); END
;

CREATE TRIGGER corpus_segments_no_video_identity BEFORE INSERT ON corpus_segments WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parser_revision GLOB 'java-video-compiler-v[1234]:*') BEGIN SELECT RAISE(ABORT,'video evidence cannot use another source type'); END
;

CREATE TRIGGER image_evidence_no_video_identity BEFORE INSERT ON image_evidence WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parser_revision GLOB 'java-video-compiler-v[1234]:*') BEGIN SELECT RAISE(ABORT,'video evidence cannot use another source type'); END
;

CREATE TRIGGER index_publication_identity BEFORE INSERT ON index_publications
WHEN NOT EXISTS(SELECT 1 FROM indexing_jobs j JOIN corpus_revisions r ON r.id=j.revision_id AND r.document_id=j.document_id
  WHERE j.id=NEW.job_id AND j.state='processing' AND j.document_id=NEW.document_id AND j.revision_id=NEW.revision_id
    AND j.attempt=NEW.attempt AND j.projection_generation_id=NEW.projection_generation_id
    AND j.source_sha256=NEW.source_sha256 AND j.parser_revision=NEW.parser_revision
    AND j.embedding_identity=NEW.embedding_identity AND j.projection_identity=NEW.projection_identity
    AND j.model_revision=NEW.model_revision AND j.dimensions=NEW.dimensions
    AND r.segment_count+(SELECT COUNT(*) FROM image_evidence WHERE revision_id=r.id)+(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL)+(SELECT COUNT(*) FROM video_frames WHERE revision_id=r.id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL)+(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=r.id)+(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=r.id AND index_ordinal IS NOT NULL)=NEW.segment_count AND (r.parser_revision NOT LIKE 'java-audio-compiler-v1:%' OR (r.parsed_at IS NOT NULL
  AND r.page_count=0 AND r.segment_count=0
  AND EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=r.id
    AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
    AND h.span_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id)
    AND h.projection_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL))
  AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)))
 AND (r.parser_revision NOT GLOB 'java-video-compiler-v[1234]:*' OR (r.parsed_at IS NOT NULL AND r.page_count=0 AND r.segment_count=0
AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=r.id
  AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
  AND h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
  AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
    AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
    AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
  AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
    +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
  AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
    WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
      AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=r.id)
)))
BEGIN SELECT RAISE(ABORT,'invalid index publication'); END
;

CREATE TRIGGER video_compilations_identity BEFORE INSERT ON video_compilations
WHEN NOT EXISTS(SELECT 1 FROM corpus_revisions r JOIN documents d ON d.id=r.document_id
  JOIN corpus_documents c ON c.document_id=d.id AND c.initial_revision_id=r.id
  JOIN ingestion_jobs j ON j.document_id=d.id AND j.revision_id=r.id AND j.state='processing'
  WHERE r.id=NEW.revision_id AND d.document_type='video'
    AND d.mime_type IN ('video/mp4','video/quicktime','video/webm','video/x-matroska')
    AND r.source_sha256=d.source_sha256 AND r.source_sha256=NEW.source_sha256
    AND r.parser_revision=NEW.compiler_revision AND r.parser_revision GLOB 'java-video-compiler-v[1234]:*'
    AND r.page_count=0 AND r.segment_count=0)
  OR EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.revision_id)
  OR EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.revision_id)
  OR EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=NEW.revision_id)
  OR EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=NEW.revision_id)
BEGIN SELECT RAISE(ABORT,'invalid video preparation identity'); END
;

CREATE TRIGGER video_ocr_compilations_identity BEFORE INSERT ON video_ocr_compilations
WHEN NOT EXISTS(SELECT 1 FROM video_compilations h JOIN corpus_revisions r ON r.id=h.revision_id
  WHERE h.revision_id=NEW.revision_id AND h.compiler_revision GLOB 'java-video-compiler-v[234]:*'
    AND h.frame_count=NEW.frame_count AND h.manifest_sha256=NEW.base_manifest_sha256
    AND h.projection_count+NEW.projection_count<=4096 AND r.parsed_at IS NULL)
BEGIN SELECT RAISE(ABORT,'invalid video OCR preparation'); END
;

CREATE TRIGGER video_subtitle_compilations_identity BEFORE INSERT ON video_subtitle_compilations
WHEN NOT EXISTS(SELECT 1 FROM video_compilations h JOIN corpus_revisions r ON r.id=h.revision_id
  WHERE h.revision_id=NEW.revision_id AND h.compiler_revision GLOB 'java-video-compiler-v[34]:*'
    AND h.manifest_sha256=NEW.base_manifest_sha256 AND r.parsed_at IS NULL
    AND h.projection_count+NEW.projection_count<=4096)
BEGIN SELECT RAISE(ABORT,'invalid video subtitle preparation'); END
;

CREATE TRIGGER video_transcript_spans_identity BEFORE INSERT ON video_transcript_spans
WHEN NOT EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.revision_id AND h.audio_sample_count IS NOT NULL
  AND NEW.ordinal<h.span_count AND NEW.end_ms<=(h.audio_sample_count+15)/16
  AND (NEW.index_ordinal IS NULL OR NEW.index_ordinal<h.projection_count-(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)))
  OR NEW.ordinal!=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=NEW.revision_id)
  OR NEW.start_ms!=COALESCE((SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=NEW.revision_id),0)
  OR (NEW.index_ordinal IS NOT NULL AND NEW.index_ordinal!=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=NEW.revision_id AND index_ordinal IS NOT NULL))
BEGIN SELECT RAISE(ABORT,'invalid video transcript identity'); END
;

CREATE TRIGGER video_frames_recall_mode BEFORE INSERT ON video_frames
WHEN NOT EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.revision_id
  AND ((h.compiler_revision LIKE 'java-video-compiler-v4:%' AND NEW.recall_text IS NULL)
    OR (h.compiler_revision NOT LIKE 'java-video-compiler-v4:%' AND NEW.recall_text IS NOT NULL)))
BEGIN SELECT RAISE(ABORT,'invalid video frame recall mode'); END
;

CREATE TRIGGER video_frame_publication_has_recall BEFORE INSERT ON video_frame_publication_entries
WHEN NOT EXISTS(SELECT 1 FROM video_frames WHERE id=NEW.video_frame_id AND recall_text IS NOT NULL)
BEGIN SELECT RAISE(ABORT,'video frame has no recall projection'); END
;

CREATE TRIGGER corpus_revision_video_text_complete BEFORE UPDATE OF parsed_at ON corpus_revisions WHEN NEW.parser_revision LIKE 'java-video-compiler-v4:%' AND NEW.parsed_at IS NOT NULL AND NOT (NEW.page_count=0 AND NEW.segment_count=0
AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.id
  AND h.source_sha256=NEW.source_sha256 AND h.compiler_revision=NEW.parser_revision
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
  AND h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
  AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
    AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
    AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
  AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
    +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
  AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
    WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
      AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=NEW.id)
AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=NEW.id)
 AND (NOT EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=NEW.id) OR (EXISTS(SELECT 1 FROM video_ocr_compilations o JOIN video_compilations h ON h.revision_id=o.revision_id
  WHERE o.revision_id=NEW.id AND o.base_manifest_sha256=h.manifest_sha256 AND o.frame_count=h.frame_count
    AND o.frame_count=(SELECT COUNT(*) FROM video_frame_ocr WHERE revision_id=o.revision_id)
    AND o.projection_count=(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=o.revision_id)
    AND NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=o.revision_id
      AND (f.segment_count!=(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.frame_id=f.frame_id)
        OR f.region_count!=(SELECT COUNT(*) FROM video_ocr_regions b WHERE b.frame_id=f.frame_id)))
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=o.revision_id)<=1500000)
)) AND (NOT EXISTS(SELECT 1 FROM video_subtitle_compilations WHERE revision_id=NEW.id) OR (EXISTS(SELECT 1 FROM video_subtitle_compilations s JOIN video_compilations h ON h.revision_id=s.revision_id
  WHERE s.revision_id=NEW.id AND s.base_manifest_sha256=h.manifest_sha256
    AND s.track_count=(SELECT COUNT(*) FROM video_subtitle_tracks WHERE revision_id=s.revision_id)
    AND s.cue_count=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=s.revision_id)
    AND s.cue_count=(SELECT COALESCE(SUM(cue_count),0) FROM video_subtitle_tracks WHERE revision_id=s.revision_id)
    AND s.projection_count=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=s.revision_id AND index_ordinal IS NOT NULL)
    AND h.projection_count+s.projection_count+COALESCE((SELECT projection_count FROM video_ocr_compilations WHERE revision_id=s.revision_id),0)<=4096
    AND NOT EXISTS(SELECT 1 FROM video_subtitle_tracks t WHERE t.revision_id=s.revision_id AND (
      t.cue_count!=(SELECT COUNT(*) FROM video_subtitle_cues WHERE track_id=t.id)
      OR length(t.text)!=(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE track_id=t.id)+max(t.cue_count-1,0)
      OR (t.cue_count>0 AND (t.cue_count-1!=(SELECT MAX(ordinal) FROM video_subtitle_cues WHERE track_id=t.id)
        OR length(t.text)!=(SELECT MAX(end_offset) FROM video_subtitle_cues WHERE track_id=t.id)))))
    AND NOT EXISTS(SELECT 1 FROM video_subtitle_cues c WHERE c.revision_id=s.revision_id AND c.index_ordinal IS NOT NULL
      AND c.index_ordinal!=(SELECT COUNT(*) FROM video_subtitle_cues previous WHERE previous.revision_id=c.revision_id AND previous.index_ordinal IS NOT NULL
        AND (previous.stream_index<c.stream_index OR (previous.stream_index=c.stream_index AND previous.ordinal<c.ordinal))))
    AND (SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=s.revision_id)<=500000
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=s.revision_id AND index_ordinal IS NOT NULL)<=1500000
    AND ((s.ocr_expected=0 AND NOT EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=s.revision_id))
      OR (s.ocr_expected=1 AND EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=s.revision_id AND manifest_sha256=s.ocr_manifest_sha256) AND EXISTS(SELECT 1 FROM video_ocr_compilations o JOIN video_compilations h ON h.revision_id=o.revision_id
  WHERE o.revision_id=NEW.id AND o.base_manifest_sha256=h.manifest_sha256 AND o.frame_count=h.frame_count
    AND o.frame_count=(SELECT COUNT(*) FROM video_frame_ocr WHERE revision_id=o.revision_id)
    AND o.projection_count=(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=o.revision_id)
    AND NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=o.revision_id
      AND (f.segment_count!=(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.frame_id=f.frame_id)
        OR f.region_count!=(SELECT COUNT(*) FROM video_ocr_regions b WHERE b.frame_id=f.frame_id)))
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=o.revision_id)<=1500000)
)))
)) AND NOT EXISTS(SELECT 1 FROM video_frames WHERE revision_id=NEW.id AND (recall_text IS NOT NULL OR recall_sha256 IS NOT NULL OR description_revision IS NOT NULL))) BEGIN SELECT RAISE(ABORT,'incomplete video text preparation'); END
;

CREATE TRIGGER index_publication_video_text_complete BEFORE INSERT ON index_publications WHEN NEW.parser_revision LIKE 'java-video-compiler-v4:%' AND NOT EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=NEW.revision_id AND r.parsed_at IS NOT NULL AND r.page_count=0 AND r.segment_count=0
AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=r.id
  AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
  AND h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
  AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
    AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
    AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
  AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
    +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
  AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
    WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
      AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=r.id)
 AND (NOT EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=r.id) OR (EXISTS(SELECT 1 FROM video_ocr_compilations o JOIN video_compilations h ON h.revision_id=o.revision_id
  WHERE o.revision_id=r.id AND o.base_manifest_sha256=h.manifest_sha256 AND o.frame_count=h.frame_count
    AND o.frame_count=(SELECT COUNT(*) FROM video_frame_ocr WHERE revision_id=o.revision_id)
    AND o.projection_count=(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=o.revision_id)
    AND NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=o.revision_id
      AND (f.segment_count!=(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.frame_id=f.frame_id)
        OR f.region_count!=(SELECT COUNT(*) FROM video_ocr_regions b WHERE b.frame_id=f.frame_id)))
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=o.revision_id)<=1500000)
)) AND (NOT EXISTS(SELECT 1 FROM video_subtitle_compilations WHERE revision_id=r.id) OR (EXISTS(SELECT 1 FROM video_subtitle_compilations s JOIN video_compilations h ON h.revision_id=s.revision_id
  WHERE s.revision_id=r.id AND s.base_manifest_sha256=h.manifest_sha256
    AND s.track_count=(SELECT COUNT(*) FROM video_subtitle_tracks WHERE revision_id=s.revision_id)
    AND s.cue_count=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=s.revision_id)
    AND s.cue_count=(SELECT COALESCE(SUM(cue_count),0) FROM video_subtitle_tracks WHERE revision_id=s.revision_id)
    AND s.projection_count=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=s.revision_id AND index_ordinal IS NOT NULL)
    AND h.projection_count+s.projection_count+COALESCE((SELECT projection_count FROM video_ocr_compilations WHERE revision_id=s.revision_id),0)<=4096
    AND NOT EXISTS(SELECT 1 FROM video_subtitle_tracks t WHERE t.revision_id=s.revision_id AND (
      t.cue_count!=(SELECT COUNT(*) FROM video_subtitle_cues WHERE track_id=t.id)
      OR length(t.text)!=(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE track_id=t.id)+max(t.cue_count-1,0)
      OR (t.cue_count>0 AND (t.cue_count-1!=(SELECT MAX(ordinal) FROM video_subtitle_cues WHERE track_id=t.id)
        OR length(t.text)!=(SELECT MAX(end_offset) FROM video_subtitle_cues WHERE track_id=t.id)))))
    AND NOT EXISTS(SELECT 1 FROM video_subtitle_cues c WHERE c.revision_id=s.revision_id AND c.index_ordinal IS NOT NULL
      AND c.index_ordinal!=(SELECT COUNT(*) FROM video_subtitle_cues previous WHERE previous.revision_id=c.revision_id AND previous.index_ordinal IS NOT NULL
        AND (previous.stream_index<c.stream_index OR (previous.stream_index=c.stream_index AND previous.ordinal<c.ordinal))))
    AND (SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=s.revision_id)<=500000
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=s.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=s.revision_id AND index_ordinal IS NOT NULL)<=1500000
    AND ((s.ocr_expected=0 AND NOT EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=s.revision_id))
      OR (s.ocr_expected=1 AND EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=s.revision_id AND manifest_sha256=s.ocr_manifest_sha256) AND EXISTS(SELECT 1 FROM video_ocr_compilations o JOIN video_compilations h ON h.revision_id=o.revision_id
  WHERE o.revision_id=r.id AND o.base_manifest_sha256=h.manifest_sha256 AND o.frame_count=h.frame_count
    AND o.frame_count=(SELECT COUNT(*) FROM video_frame_ocr WHERE revision_id=o.revision_id)
    AND o.projection_count=(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=o.revision_id)
    AND NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=o.revision_id
      AND (f.segment_count!=(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.frame_id=f.frame_id)
        OR f.region_count!=(SELECT COUNT(*) FROM video_ocr_regions b WHERE b.frame_id=f.frame_id)))
    AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=o.revision_id)
      +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=o.revision_id)<=1500000)
)))
)) AND NOT EXISTS(SELECT 1 FROM video_frames WHERE revision_id=r.id AND (recall_text IS NOT NULL OR recall_sha256 IS NOT NULL OR description_revision IS NOT NULL))) BEGIN SELECT RAISE(ABORT,'incomplete video text publication'); END
;

CREATE TRIGGER knowledge_answer_traces_complete BEFORE INSERT ON knowledge_answer_traces
WHEN NEW.scope_count!=(SELECT COUNT(*) FROM knowledge_answer_documents WHERE trace_id=NEW.id)
  OR (NEW.scope_count>0 AND (SELECT MIN(ordinal)!=0 OR MAX(ordinal)!=NEW.scope_count-1 FROM knowledge_answer_documents WHERE trace_id=NEW.id))
  OR NEW.citation_count!=(SELECT COUNT(*) FROM knowledge_answer_citations WHERE trace_id=NEW.id)
  OR (NEW.citation_count>0 AND (SELECT MIN(ordinal)!=1 OR MAX(ordinal)!=NEW.citation_count FROM knowledge_answer_citations WHERE trace_id=NEW.id))
  OR EXISTS(SELECT 1 FROM knowledge_answer_documents s LEFT JOIN index_publications p ON p.id=s.publication_id
    LEFT JOIN documents d ON d.id=p.document_id WHERE s.trace_id=NEW.id AND (p.id IS NULL OR d.workspace_id IS NOT NEW.workspace_id))
BEGIN SELECT RAISE(ABORT,'incomplete knowledge answer'); END
;

CREATE TRIGGER knowledge_answer_traces_no_delete BEFORE DELETE ON knowledge_answer_traces BEGIN SELECT RAISE(ABORT,'immutable knowledge answer'); END
;

CREATE TRIGGER knowledge_answer_traces_no_replace BEFORE INSERT ON knowledge_answer_traces WHEN EXISTS(SELECT 1 FROM knowledge_answer_traces WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'immutable knowledge answer'); END
;

CREATE TRIGGER knowledge_answer_traces_no_update BEFORE UPDATE ON knowledge_answer_traces BEGIN SELECT RAISE(ABORT,'immutable knowledge answer'); END
;

CREATE TRIGGER query_traces_complete BEFORE INSERT ON query_traces WHEN
  NEW.scope_count!=(SELECT COUNT(*) FROM query_trace_documents WHERE trace_id=NEW.id)
  OR NEW.citation_count!=(SELECT COUNT(*) FROM query_trace_evidence WHERE trace_id=NEW.id)
    +(SELECT COUNT(*) FROM image_trace_evidence WHERE trace_id=NEW.id)
    +(SELECT COUNT(*) FROM audio_trace_evidence WHERE trace_id=NEW.id)
    +(SELECT COUNT(*) FROM video_trace_evidence WHERE trace_id=NEW.id)+(SELECT COUNT(*) FROM video_ocr_trace_evidence WHERE trace_id=NEW.id)+(SELECT COUNT(*) FROM video_subtitle_trace_evidence WHERE trace_id=NEW.id)
  OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=0
    OR (SELECT MAX(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
  OR (NEW.citation_count>0 AND (SELECT COUNT(DISTINCT citation_ordinal)!=NEW.citation_count
    OR MIN(citation_ordinal)!=1 OR MAX(citation_ordinal)!=NEW.citation_count
    FROM (SELECT citation_ordinal FROM query_trace_evidence WHERE trace_id=NEW.id
      UNION ALL SELECT citation_ordinal FROM image_trace_evidence WHERE trace_id=NEW.id
      UNION ALL SELECT citation_ordinal FROM audio_trace_evidence WHERE trace_id=NEW.id
      UNION ALL SELECT citation_ordinal FROM video_trace_evidence WHERE trace_id=NEW.id UNION ALL SELECT citation_ordinal FROM video_ocr_trace_evidence WHERE trace_id=NEW.id UNION ALL SELECT citation_ordinal FROM video_subtitle_trace_evidence WHERE trace_id=NEW.id)))
  OR EXISTS(SELECT 1 FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id
    JOIN documents d ON d.id=p.document_id WHERE q.trace_id=NEW.id AND d.workspace_id!=NEW.workspace_id)
  OR ((SELECT COUNT(*) FROM video_trace_proofs WHERE trace_id=NEW.id)>0)!=(EXISTS(SELECT 1 FROM video_trace_evidence WHERE trace_id=NEW.id))
  OR EXISTS(SELECT 1 FROM video_trace_proofs p WHERE p.trace_id=NEW.id AND
    (NEW.outcome!='answered' OR p.fact_count!=(SELECT COUNT(*) FROM video_trace_facts WHERE trace_id=NEW.id)
      OR (SELECT MIN(ordinal) FROM video_trace_facts WHERE trace_id=NEW.id)!=0
      OR (SELECT MAX(ordinal) FROM video_trace_facts WHERE trace_id=NEW.id)!=p.fact_count-1
      OR (p.mode='JOINT' AND ((SELECT SUM(visual_support) FROM video_trace_facts WHERE trace_id=NEW.id)=0 OR (SELECT SUM(transcript_support) FROM video_trace_facts WHERE trace_id=NEW.id)=0))
      OR (p.mode='VISUAL' AND EXISTS(SELECT 1 FROM video_trace_facts WHERE trace_id=NEW.id AND (visual_support!=1 OR transcript_support!=0)))
      OR (p.mode='TRANSCRIPT' AND EXISTS(SELECT 1 FROM video_trace_facts WHERE trace_id=NEW.id AND (visual_support!=0 OR transcript_support!=1)))))
  OR EXISTS(SELECT 1 FROM video_trace_facts f WHERE f.trace_id=NEW.id AND
    (f.visual_support!=(EXISTS(SELECT 1 FROM video_trace_evidence e WHERE e.trace_id=f.trace_id AND e.fact_sha256=f.fact_sha256 AND e.kind='VISUAL'))
      OR f.transcript_support!=(EXISTS(SELECT 1 FROM video_trace_evidence e WHERE e.trace_id=f.trace_id AND e.fact_sha256=f.fact_sha256 AND e.kind='TRANSCRIPT'))))
BEGIN SELECT RAISE(ABORT,'incomplete query trace'); END
;

CREATE TRIGGER query_traces_no_delete BEFORE DELETE ON query_traces BEGIN SELECT RAISE(ABORT,'immutable query trace'); END
;

CREATE TRIGGER query_traces_no_replace BEFORE INSERT ON query_traces
WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.id)
BEGIN SELECT RAISE(ABORT,'immutable query trace'); END
;

CREATE TRIGGER query_traces_no_update BEFORE UPDATE ON query_traces BEGIN SELECT RAISE(ABORT,'immutable query trace'); END
;

CREATE TRIGGER query_traces_query_preparation_complete BEFORE INSERT ON query_traces
WHEN EXISTS(SELECT 1 FROM query_trace_preparations p WHERE p.trace_id=NEW.id AND (
  p.question_sha256!=NEW.question_sha256 OR (p.status='failed' AND NEW.outcome!='abstained')
  OR p.attachment_count!=(SELECT COUNT(*) FROM query_trace_attachments a WHERE a.trace_id=NEW.id)
  OR (SELECT MIN(ordinal) FROM query_trace_attachments WHERE trace_id=NEW.id)!=0
  OR (SELECT MAX(ordinal) FROM query_trace_attachments WHERE trace_id=NEW.id)!=p.attachment_count-1
  OR EXISTS(SELECT 1 FROM query_trace_attachments a WHERE a.trace_id=NEW.id AND (
    (p.status='prepared' AND a.compiler_revision IS NULL) OR (p.status='failed' AND a.compiler_revision IS NOT NULL)
    OR a.selected_image_count!=(SELECT COUNT(*) FROM query_trace_attachment_images i WHERE i.trace_id=a.trace_id AND i.attachment_ordinal=a.ordinal)
    OR (a.selected_image_count>0 AND ((SELECT MIN(ordinal) FROM query_trace_attachment_images i WHERE i.trace_id=a.trace_id AND i.attachment_ordinal=a.ordinal)!=0
      OR (SELECT MAX(ordinal) FROM query_trace_attachment_images i WHERE i.trace_id=a.trace_id AND i.attachment_ordinal=a.ordinal)!=a.selected_image_count-1))))
  OR (SELECT COUNT(DISTINCT image_sha256) FROM query_trace_attachment_images WHERE trace_id=NEW.id)>3))
BEGIN SELECT RAISE(ABORT,'incomplete query preparation trace'); END
;

CREATE TRIGGER sound_traces_no_delete BEFORE DELETE ON sound_traces BEGIN SELECT RAISE(ABORT,'immutable sound authority'); END
;

CREATE TRIGGER sound_traces_no_replace BEFORE INSERT ON sound_traces WHEN EXISTS(SELECT 1 FROM sound_traces WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'immutable sound trace'); END
;

CREATE TRIGGER sound_traces_no_update BEFORE UPDATE ON sound_traces BEGIN SELECT RAISE(ABORT,'immutable sound authority'); END
;

CREATE TRIGGER video_av_query_parent_complete BEFORE INSERT ON video_av_traces
WHEN (EXISTS(SELECT 1 FROM video_av_query_attachments WHERE trace_id=NEW.id)
  AND NOT EXISTS(SELECT 1 FROM video_av_query_preparations WHERE trace_id=NEW.id))
  OR EXISTS(SELECT 1 FROM video_av_query_preparations q WHERE q.trace_id=NEW.id
    AND (q.mode!=NEW.mode OR q.question_sha256!=NEW.question_sha256
      OR (NEW.status='answered' AND EXISTS(SELECT 1 FROM video_av_query_attachments a
        WHERE a.trace_id=NEW.id AND a.status!='prepared'))
      OR EXISTS(SELECT 1 FROM video_av_trace_documents d JOIN video_av_publications p ON p.id=d.publication_id
        WHERE d.trace_id=NEW.id AND (p.profile_fingerprint!=q.profile_fingerprint
          OR p.visual_embedding_identity!=q.embedding_revision OR p.audio_embedding_identity!=q.embedding_revision))))
BEGIN SELECT RAISE(ABORT,'inconsistent video query trace'); END
;

CREATE TRIGGER video_av_traces_no_delete BEFORE DELETE ON video_av_traces BEGIN SELECT RAISE(ABORT,'immutable video audiovisual authority'); END
;

CREATE TRIGGER video_av_traces_no_replace BEFORE INSERT ON video_av_traces WHEN EXISTS(SELECT 1 FROM video_av_traces WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'immutable video audiovisual trace'); END
;

CREATE TRIGGER video_av_traces_no_update BEFORE UPDATE ON video_av_traces BEGIN SELECT RAISE(ABORT,'immutable video audiovisual authority'); END
;

CREATE TRIGGER knowledge_answer_documents_no_delete BEFORE DELETE ON knowledge_answer_documents BEGIN SELECT RAISE(ABORT,'immutable knowledge answer'); END
;

CREATE TRIGGER knowledge_answer_documents_no_replace BEFORE INSERT ON knowledge_answer_documents WHEN EXISTS(SELECT 1 FROM knowledge_answer_documents WHERE trace_id=NEW.trace_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable knowledge answer'); END
;

CREATE TRIGGER knowledge_answer_documents_no_update BEFORE UPDATE ON knowledge_answer_documents BEGIN SELECT RAISE(ABORT,'immutable knowledge answer'); END
;

CREATE TRIGGER knowledge_answer_documents_sealed BEFORE INSERT ON knowledge_answer_documents WHEN EXISTS(SELECT 1 FROM knowledge_answer_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed knowledge answer'); END
;

CREATE TRIGGER query_trace_documents_no_delete BEFORE DELETE ON query_trace_documents BEGIN SELECT RAISE(ABORT,'immutable query trace'); END
;

CREATE TRIGGER query_trace_documents_no_update BEFORE UPDATE ON query_trace_documents BEGIN SELECT RAISE(ABORT,'immutable query trace'); END
;

CREATE TRIGGER query_trace_documents_sealed BEFORE INSERT ON query_trace_documents WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed query trace'); END
;

CREATE TRIGGER sound_trace_documents_no_delete BEFORE DELETE ON sound_trace_documents BEGIN SELECT RAISE(ABORT,'immutable sound authority'); END
;

CREATE TRIGGER sound_trace_documents_no_replace BEFORE INSERT ON sound_trace_documents WHEN EXISTS(SELECT 1 FROM sound_trace_documents WHERE trace_id=NEW.trace_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable sound trace child'); END
;

CREATE TRIGGER sound_trace_documents_no_update BEFORE UPDATE ON sound_trace_documents BEGIN SELECT RAISE(ABORT,'immutable sound authority'); END
;

CREATE TRIGGER sound_trace_documents_sealed BEFORE INSERT ON sound_trace_documents WHEN EXISTS(SELECT 1 FROM sound_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed sound trace'); END
;

CREATE TRIGGER video_av_trace_documents_no_delete BEFORE DELETE ON video_av_trace_documents BEGIN SELECT RAISE(ABORT,'immutable video audiovisual authority'); END
;

CREATE TRIGGER video_av_trace_documents_no_replace BEFORE INSERT ON video_av_trace_documents WHEN EXISTS(SELECT 1 FROM video_av_trace_documents WHERE trace_id=NEW.trace_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable video audiovisual trace child'); END
;

CREATE TRIGGER video_av_trace_documents_no_update BEFORE UPDATE ON video_av_trace_documents BEGIN SELECT RAISE(ABORT,'immutable video audiovisual authority'); END
;

CREATE TRIGGER video_av_trace_documents_sealed BEFORE INSERT ON video_av_trace_documents WHEN EXISTS(SELECT 1 FROM video_av_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed video audiovisual trace'); END
;

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
;

CREATE TRIGGER knowledge_answer_citations_no_delete BEFORE DELETE ON knowledge_answer_citations BEGIN SELECT RAISE(ABORT,'immutable knowledge answer'); END
;

CREATE TRIGGER knowledge_answer_citations_no_replace BEFORE INSERT ON knowledge_answer_citations WHEN EXISTS(SELECT 1 FROM knowledge_answer_citations WHERE trace_id=NEW.trace_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable knowledge answer'); END
;

CREATE TRIGGER knowledge_answer_citations_no_update BEFORE UPDATE ON knowledge_answer_citations BEGIN SELECT RAISE(ABORT,'immutable knowledge answer'); END
;

CREATE TRIGGER knowledge_answer_citations_sealed BEFORE INSERT ON knowledge_answer_citations WHEN EXISTS(SELECT 1 FROM knowledge_answer_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed knowledge answer'); END
;

CREATE TRIGGER sound_traces_complete BEFORE INSERT ON sound_traces
WHEN NEW.scope_count!=(SELECT COUNT(*) FROM sound_trace_documents WHERE trace_id=NEW.id)
  OR (NEW.selection_all=1 AND NEW.scope_count!=(SELECT COUNT(*) FROM documents d
     WHERE d.workspace_id=NEW.workspace_id
       AND d.document_type='audio'
      AND (EXISTS(SELECT 1 FROM sound_originals s WHERE s.document_id=d.id)
        OR EXISTS(SELECT 1 FROM corpus_documents c WHERE c.document_id=d.id))
      AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)))
  OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM sound_trace_documents WHERE trace_id=NEW.id)!=0
    OR (SELECT MAX(ordinal) FROM sound_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
  OR NEW.citation_count!=(SELECT COUNT(*) FROM sound_trace_evidence WHERE trace_id=NEW.id)
  OR (NEW.citation_count>0 AND ((SELECT MIN(ordinal) FROM sound_trace_evidence WHERE trace_id=NEW.id)!=1
    OR (SELECT MAX(ordinal) FROM sound_trace_evidence WHERE trace_id=NEW.id)!=NEW.citation_count))
  OR EXISTS(SELECT 1 FROM sound_trace_documents e JOIN sound_publications p ON p.id=e.publication_id
    WHERE e.trace_id=NEW.id AND (p.workspace_id!=NEW.workspace_id OR p.sound_model_revision!=NEW.model_revision
      OR NOT EXISTS(SELECT 1 FROM documents d 
        WHERE d.id=p.document_id AND d.workspace_id=NEW.workspace_id 
          AND d.active_revision_id=p.source_revision_id AND d.source_sha256=p.source_sha256
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id))))
  OR EXISTS(SELECT 1 FROM sound_trace_evidence e WHERE e.trace_id=NEW.id AND NOT EXISTS(
    SELECT 1 FROM sound_trace_documents d WHERE d.trace_id=NEW.id AND d.publication_id=e.publication_id))
BEGIN SELECT RAISE(ABORT,'incomplete sound trace'); END
;

CREATE TRIGGER video_av_traces_complete BEFORE INSERT ON video_av_traces
WHEN NEW.scope_count!=(SELECT COUNT(*) FROM video_av_trace_documents WHERE trace_id=NEW.id)
  OR (NEW.selection_all=1 AND NEW.scope_count!=(SELECT COUNT(*) FROM documents d
     WHERE d.workspace_id=NEW.workspace_id
       AND d.document_type='video'
      AND (EXISTS(SELECT 1 FROM video_av_originals s WHERE s.document_id=d.id)
        OR EXISTS(SELECT 1 FROM corpus_documents c WHERE c.document_id=d.id))
      AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)))
  OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM video_av_trace_documents WHERE trace_id=NEW.id)!=0
    OR (SELECT MAX(ordinal) FROM video_av_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
  OR NEW.citation_count!=(SELECT COUNT(*) FROM video_av_trace_evidence WHERE trace_id=NEW.id)
  OR (NEW.citation_count>0 AND ((SELECT MIN(ordinal) FROM video_av_trace_evidence WHERE trace_id=NEW.id)!=1
    OR (SELECT MAX(ordinal) FROM video_av_trace_evidence WHERE trace_id=NEW.id)!=NEW.citation_count))
  OR EXISTS(SELECT 1 FROM video_av_trace_documents e JOIN video_av_publications p ON p.id=e.publication_id
    WHERE e.trace_id=NEW.id AND (p.workspace_id!=NEW.workspace_id OR p.analysis_model_revision!=NEW.model_revision
      OR NOT EXISTS(SELECT 1 FROM documents d 
        WHERE d.id=p.document_id AND d.workspace_id=NEW.workspace_id 
          AND d.active_revision_id=p.source_revision_id AND d.source_sha256=p.source_sha256
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id))))
  OR EXISTS(SELECT 1 FROM video_av_trace_evidence e WHERE e.trace_id=NEW.id AND NOT EXISTS(
    SELECT 1 FROM video_av_trace_documents d WHERE d.trace_id=NEW.id AND d.publication_id=e.publication_id))
BEGIN SELECT RAISE(ABORT,'incomplete video audiovisual trace'); END
;

CREATE TRIGGER wiki_page_revisions_contiguous BEFORE INSERT ON wiki_page_revisions
WHEN NEW.version!=COALESCE((SELECT MAX(version)+1 FROM wiki_page_revisions WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id),1)
BEGIN SELECT RAISE(ABORT,'invalid wiki revision'); END
;

CREATE TRIGGER wiki_proposals_initial BEFORE INSERT ON wiki_proposals
WHEN NEW.status!='pending' OR EXISTS(SELECT 1 FROM wiki_proposals WHERE workspace_id=NEW.workspace_id AND id=NEW.id)
BEGIN SELECT RAISE(ABORT,'invalid wiki proposal'); END
;

CREATE TRIGGER wiki_proposals_review BEFORE UPDATE ON wiki_proposals
WHEN OLD.status!='pending' OR NEW.status NOT IN ('accepted','dismissed')
  OR NEW.workspace_id IS NOT OLD.workspace_id OR NEW.id IS NOT OLD.id
  OR NEW.page_id IS NOT OLD.page_id OR NEW.base_version IS NOT OLD.base_version
  OR NEW.before_json IS NOT OLD.before_json OR NEW.after_json IS NOT OLD.after_json
  OR NEW.generation_method IS NOT OLD.generation_method OR NEW.model_revision IS NOT OLD.model_revision
  OR NEW.policy_revision IS NOT OLD.policy_revision OR NEW.created_at IS NOT OLD.created_at
BEGIN SELECT RAISE(ABORT,'invalid wiki proposal review'); END
;

CREATE TRIGGER wiki_page_revisions_no_update BEFORE UPDATE ON wiki_page_revisions BEGIN SELECT RAISE(ABORT,'immutable wiki revision'); END
;

CREATE TRIGGER wiki_drafts_version BEFORE UPDATE ON wiki_drafts
WHEN NEW.workspace_id IS NOT OLD.workspace_id OR NEW.id IS NOT OLD.id
  OR NEW.created_at IS NOT OLD.created_at OR NEW.version!=OLD.version+1
  OR NEW.updated_at<OLD.updated_at
BEGIN SELECT RAISE(ABORT,'invalid wiki draft version'); END
;

CREATE TRIGGER wiki_page_lifecycle_contiguous BEFORE INSERT ON wiki_page_lifecycle
WHEN NEW.lifecycle_version!=COALESCE((SELECT MAX(lifecycle_version)+1 FROM wiki_page_lifecycle WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id),1)
  OR NEW.state=COALESCE((SELECT state FROM wiki_page_lifecycle WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id ORDER BY lifecycle_version DESC LIMIT 1),'active')
  OR NEW.content_version!=COALESCE((SELECT MAX(version) FROM wiki_page_revisions WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id),0)
BEGIN SELECT RAISE(ABORT,'invalid wiki lifecycle'); END
;

CREATE TRIGGER wiki_page_lifecycle_no_update BEFORE UPDATE ON wiki_page_lifecycle BEGIN SELECT RAISE(ABORT,'immutable wiki lifecycle'); END
;

CREATE TRIGGER wiki_page_purges_authorized BEFORE INSERT ON wiki_page_purges
WHEN java_wiki_page_purge_authorized(NEW.workspace_id,NEW.page_id)!=1
  OR NEW.content_version IS NOT (SELECT MAX(version) FROM wiki_page_revisions WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id)
  OR NEW.lifecycle_version IS NOT (SELECT MAX(lifecycle_version) FROM wiki_page_lifecycle WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id)
  OR 'deleted' IS NOT (SELECT state FROM wiki_page_lifecycle WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id ORDER BY lifecycle_version DESC LIMIT 1)
BEGIN SELECT RAISE(ABORT,'invalid wiki purge'); END
;

CREATE TRIGGER wiki_page_purges_no_update BEFORE UPDATE ON wiki_page_purges BEGIN SELECT RAISE(ABORT,'immutable wiki purge'); END
;

CREATE TRIGGER wiki_page_purges_no_delete BEFORE DELETE ON wiki_page_purges BEGIN SELECT RAISE(ABORT,'immutable wiki purge'); END
;

CREATE TRIGGER wiki_page_revisions_no_delete BEFORE DELETE ON wiki_page_revisions WHEN java_wiki_page_purge_authorized(OLD.workspace_id,OLD.page_id)!=1 OR NOT EXISTS(SELECT 1 FROM wiki_page_purges p WHERE p.workspace_id=OLD.workspace_id AND p.page_id=OLD.page_id) BEGIN SELECT RAISE(ABORT,'immutable wiki content'); END
;

CREATE TRIGGER wiki_proposals_no_delete BEFORE DELETE ON wiki_proposals WHEN java_wiki_page_purge_authorized(OLD.workspace_id,OLD.page_id)!=1 OR NOT EXISTS(SELECT 1 FROM wiki_page_purges p WHERE p.workspace_id=OLD.workspace_id AND p.page_id=OLD.page_id) BEGIN SELECT RAISE(ABORT,'immutable wiki content'); END
;

CREATE TRIGGER wiki_page_lifecycle_no_delete BEFORE DELETE ON wiki_page_lifecycle WHEN java_wiki_page_purge_authorized(OLD.workspace_id,OLD.page_id)!=1 OR NOT EXISTS(SELECT 1 FROM wiki_page_purges p WHERE p.workspace_id=OLD.workspace_id AND p.page_id=OLD.page_id) BEGIN SELECT RAISE(ABORT,'immutable wiki content'); END
;

CREATE TRIGGER wiki_page_revisions_purged BEFORE INSERT ON wiki_page_revisions WHEN EXISTS(SELECT 1 FROM wiki_page_purges p WHERE p.workspace_id=NEW.workspace_id AND p.page_id=NEW.page_id) BEGIN SELECT RAISE(ABORT,'purged wiki page'); END
;

CREATE TRIGGER wiki_proposals_purged BEFORE INSERT ON wiki_proposals WHEN EXISTS(SELECT 1 FROM wiki_page_purges p WHERE p.workspace_id=NEW.workspace_id AND p.page_id=NEW.page_id) BEGIN SELECT RAISE(ABORT,'purged wiki page'); END
;

CREATE TRIGGER indexing_generation_identity BEFORE UPDATE OF projection_generation_id ON indexing_jobs WHEN NOT ((OLD.state='queued' AND NEW.state='processing' AND OLD.projection_generation_id IS NULL AND EXISTS(SELECT 1 FROM indexing_attempts a WHERE a.job_id=NEW.id AND a.attempt=NEW.attempt AND a.projection_generation_id=NEW.projection_generation_id)) OR (OLD.state IN ('failed','cancelled') AND NEW.state='queued' AND NEW.attempt=OLD.attempt+1 AND NEW.projection_generation_id IS NULL)) BEGIN SELECT RAISE(ABORT,'immutable indexing generation'); END
;

CREATE TRIGGER indexing_jobs_no_delete BEFORE DELETE ON indexing_jobs BEGIN SELECT RAISE(ABORT,'immutable index history'); END
;

CREATE TRIGGER indexing_model_rebuild_identity BEFORE INSERT ON indexing_jobs
WHEN NEW.model_rebuild_id IS NOT NULL AND (NEW.replacement_id IS NOT NULL OR NEW.state!='queued'
  OR NEW.attempt!=1 OR NEW.rebuild_sequence!=(SELECT COALESCE(MAX(rebuild_sequence),0)+1 FROM indexing_jobs WHERE document_id=NEW.document_id)
  OR NOT EXISTS(SELECT 1 FROM model_rebuild_items i JOIN model_rebuilds b ON b.id=i.batch_id
    JOIN documents d ON d.id=i.document_id AND d.workspace_id=b.workspace_id
    JOIN corpus_documents c ON c.document_id=d.id AND c.parsed_revision_id=i.revision_id
    JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=c.document_id AND r.parsed_at IS NOT NULL
    JOIN ingestion_jobs parsed ON parsed.document_id=d.id AND parsed.revision_id=r.id AND parsed.state='parsed'
    WHERE b.id=NEW.model_rebuild_id AND b.state='queued' AND i.state='queued' AND i.job_id=NEW.id
      AND i.document_id=NEW.document_id AND i.revision_id=NEW.revision_id
      AND r.id=d.active_revision_id AND r.source_sha256=d.source_sha256
      AND i.source_sha256=NEW.source_sha256 AND r.source_sha256=i.source_sha256
      AND i.parser_revision=NEW.parser_revision AND r.parser_revision=i.parser_revision
      AND i.base_publication_id IS NEW.base_publication_id
      AND ((i.base_publication_id IS NULL AND NEW.base_vector_set_sha256 IS NULL AND NOT EXISTS(SELECT 1 FROM active_corpus_publications WHERE document_id=d.id))
        OR (i.base_vector_set_sha256=NEW.base_vector_set_sha256 AND EXISTS(SELECT 1 FROM active_corpus_publications a WHERE a.document_id=d.id AND a.publication_id=i.base_publication_id AND a.revision_id=r.id)))
      AND b.embedding_identity=NEW.embedding_identity AND b.projection_identity=NEW.projection_identity
      AND b.model_revision=NEW.model_revision AND b.dimensions=NEW.dimensions
      AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id)))
BEGIN SELECT RAISE(ABORT,'invalid model rebuild task'); END
;

CREATE TRIGGER indexing_model_rebuild_prepared BEFORE UPDATE OF state ON indexing_jobs
WHEN NEW.state='prepared' AND NOT EXISTS(SELECT 1 FROM model_rebuild_items i JOIN index_publications p ON p.id=i.publication_id
  WHERE i.job_id=NEW.id AND i.batch_id=NEW.model_rebuild_id AND i.state='prepared'
    AND p.job_id=NEW.id AND p.attempt=NEW.attempt AND p.projection_generation_id=NEW.projection_generation_id)
BEGIN SELECT RAISE(ABORT,'unsealed model rebuild publication'); END
;

CREATE TRIGGER indexing_model_rebuild_transition BEFORE UPDATE ON indexing_jobs
WHEN OLD.model_rebuild_id IS NOT NULL AND NOT (NEW.attempt=OLD.attempt AND (
  (OLD.state='queued' AND NEW.state IN ('processing','failed','cancelled'))
  OR (OLD.state='processing' AND NEW.state IN ('prepared','failed','cancelled'))
  OR (OLD.state='prepared' AND NEW.state='failed')
  OR (OLD.state='prepared' AND NEW.state='indexed' AND java_model_rebuild_authorized(OLD.document_id)=1)))
BEGIN SELECT RAISE(ABORT,'invalid model rebuild transition'); END
;

CREATE TRIGGER indexing_published_state BEFORE UPDATE OF state ON indexing_jobs
WHEN NEW.state='indexed' AND NOT EXISTS(SELECT 1 FROM index_publications p
  JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
  JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=p.document_id
  WHERE p.job_id=NEW.id AND p.attempt=NEW.attempt AND p.projection_generation_id=NEW.projection_generation_id
    AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_frame_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_transcript_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_ocr_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_subtitle_publication_entries WHERE publication_id=p.id) AND (r.parser_revision NOT LIKE 'java-audio-compiler-v1:%' OR (r.parsed_at IS NOT NULL
  AND r.page_count=0 AND r.segment_count=0
  AND EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=r.id
    AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
    AND h.span_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id)
    AND h.projection_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL))
  AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
  AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)))
 AND (r.parser_revision NOT GLOB 'java-video-compiler-v[1234]:*' OR (r.parsed_at IS NOT NULL AND r.page_count=0 AND r.segment_count=0
AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=r.id
  AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
  AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
  AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
  AND h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
  AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
  AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
    AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
    AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
  AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
    +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
    AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
  AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
    WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
      AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)
AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=r.id)
)))
BEGIN SELECT RAISE(ABORT,'missing index publication'); END
;

CREATE TRIGGER indexing_replacement_identity BEFORE INSERT ON indexing_jobs WHEN NEW.replacement_id IS NOT NULL AND (NEW.base_vector_set_sha256 IS NOT NULL OR NEW.state!='queued' OR NEW.attempt!=1 OR NEW.rebuild_sequence!=(SELECT COALESCE(MAX(rebuild_sequence),0)+1 FROM indexing_jobs WHERE document_id=NEW.document_id) OR NOT EXISTS(SELECT 1 FROM document_replacements u JOIN document_original_revisions o ON o.revision_id=u.candidate_revision_id AND o.document_id=u.document_id JOIN corpus_revisions r ON r.id=o.revision_id AND r.document_id=o.document_id JOIN ingestion_jobs parsed ON parsed.replacement_id=u.id AND parsed.revision_id=r.id AND parsed.state='parsed' JOIN documents d ON d.id=u.document_id WHERE u.id=NEW.replacement_id AND u.pipeline='corpus' AND u.state IN ('parsed','failed','cancelled') AND d.active_revision_id=u.base_revision_id AND u.document_id=NEW.document_id AND u.candidate_revision_id=NEW.revision_id AND u.base_publication_id IS NEW.base_publication_id AND o.source_sha256=NEW.source_sha256 AND r.source_sha256=o.source_sha256 AND r.parser_revision=NEW.parser_revision AND r.parsed_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id) AND ((u.base_publication_id IS NULL AND NOT EXISTS(SELECT 1 FROM active_corpus_publications WHERE document_id=d.id)) OR EXISTS(SELECT 1 FROM active_corpus_publications a JOIN index_publications p ON p.id=a.publication_id WHERE a.document_id=d.id AND p.id=u.base_publication_id AND p.embedding_identity=NEW.embedding_identity AND p.projection_identity=NEW.projection_identity AND p.model_revision=NEW.model_revision AND p.dimensions=NEW.dimensions)))) BEGIN SELECT RAISE(ABORT,'invalid replacement index identity'); END
;

CREATE TRIGGER indexing_transition BEFORE UPDATE ON indexing_jobs  WHEN (OLD.model_rebuild_id IS NULL) AND (NOT ((OLD.state='queued' AND NEW.state IN ('processing','failed','cancelled') AND NEW.attempt=OLD.attempt) OR (OLD.state='processing' AND NEW.state IN ('indexed','failed','cancelled') AND NEW.attempt=OLD.attempt) OR (OLD.state IN ('failed','cancelled') AND NEW.state='queued' AND NEW.attempt=OLD.attempt+1))) BEGIN SELECT RAISE(ABORT,'invalid indexing transition'); END
;

CREATE TRIGGER indexing_vector_snapshot_initial BEFORE INSERT ON indexing_jobs WHEN NEW.rebuild_sequence=0 AND NEW.base_vector_set_sha256 IS NOT NULL BEGIN SELECT RAISE(ABORT,'invalid initial vector snapshot'); END
;

CREATE TRIGGER model_rebuild_block_index BEFORE INSERT ON indexing_jobs WHEN NEW.model_rebuild_id IS NULL AND EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER model_rebuild_block_index_retry BEFORE UPDATE OF state ON indexing_jobs WHEN OLD.model_rebuild_id IS NULL AND NEW.state='queued' AND EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END
;

CREATE TRIGGER indexing_identity BEFORE UPDATE OF id,document_id,revision_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,created_by,created_at,rebuild_sequence,base_publication_id,base_vector_set_sha256,replacement_id,model_rebuild_id,restart_of ON indexing_jobs BEGIN SELECT RAISE(ABORT,'immutable indexing identity'); END
;

CREATE TRIGGER indexing_rebuild_identity BEFORE INSERT ON indexing_jobs
   WHEN (NEW.restart_of IS NULL) AND ((NEW.model_rebuild_id IS NULL) AND ((NEW.replacement_id IS NULL) AND (NEW.rebuild_sequence>0 AND (NEW.state!='queued' OR NEW.attempt!=1 OR (
    NEW.rebuild_sequence!=(SELECT COALESCE(MAX(rebuild_sequence),0)+1 FROM indexing_jobs WHERE document_id=NEW.document_id)
    OR NOT EXISTS(SELECT 1 FROM active_corpus_publications a
      JOIN index_publications p ON p.id=a.publication_id AND p.document_id=a.document_id AND p.revision_id=a.revision_id
      JOIN indexing_jobs prior ON prior.id=p.job_id AND prior.state='indexed'
      JOIN corpus_documents c ON c.document_id=a.document_id AND c.parsed_revision_id=a.revision_id
      JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=c.document_id AND r.parsed_at IS NOT NULL
      JOIN documents d ON d.id=c.document_id AND d.source_sha256=r.source_sha256
      JOIN ingestion_jobs parsed ON parsed.document_id=c.document_id AND parsed.revision_id=r.id AND parsed.state='parsed'
      WHERE a.document_id=NEW.document_id AND a.publication_id=NEW.base_publication_id
        AND p.revision_id=NEW.revision_id AND p.source_sha256=NEW.source_sha256 AND p.parser_revision=NEW.parser_revision
        AND p.embedding_identity=NEW.embedding_identity AND p.projection_identity=NEW.projection_identity
        AND p.model_revision=NEW.model_revision AND p.dimensions=NEW.dimensions
        AND r.source_sha256=NEW.source_sha256 AND r.parser_revision=NEW.parser_revision)
    OR (NEW.base_vector_set_sha256 IS NULL AND (EXISTS(SELECT 1 FROM image_vector_publications WHERE publication_id=NEW.base_publication_id) OR EXISTS(SELECT 1 FROM audio_vector_publications WHERE publication_id=NEW.base_publication_id) OR EXISTS(SELECT 1 FROM image_vector_bindings WHERE publication_id=NEW.base_publication_id) OR EXISTS(SELECT 1 FROM audio_vector_bindings WHERE publication_id=NEW.base_publication_id)))
    OR EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id)))))) BEGIN SELECT RAISE(ABORT,'invalid rebuild identity'); END
;

CREATE TRIGGER indexing_restart_identity BEFORE INSERT ON indexing_jobs
WHEN NEW.restart_of IS NOT NULL AND (NEW.state!='queued' OR NEW.attempt!=1
  OR NEW.rebuild_sequence!=(SELECT COALESCE(MAX(rebuild_sequence),0)+1 FROM indexing_jobs WHERE document_id=NEW.document_id)
  OR NOT EXISTS(SELECT 1 FROM indexing_jobs prior
    JOIN corpus_documents c ON c.document_id=prior.document_id
    JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=c.document_id AND r.parsed_at IS NOT NULL
    JOIN documents d ON d.id=c.document_id AND d.active_revision_id=r.id AND d.source_sha256=r.source_sha256
    JOIN ingestion_jobs parsed ON parsed.document_id=c.document_id AND parsed.revision_id=r.id AND parsed.state='parsed'
    WHERE prior.id=NEW.restart_of AND prior.document_id=NEW.document_id
      AND prior.rebuild_sequence=NEW.rebuild_sequence-1 AND prior.state IN ('failed','cancelled')
      AND prior.base_publication_id IS NULL AND prior.replacement_id IS NULL AND prior.model_rebuild_id IS NULL
      AND r.id=NEW.revision_id AND r.source_sha256=NEW.source_sha256 AND r.parser_revision=NEW.parser_revision)
  OR EXISTS(SELECT 1 FROM active_corpus_publications WHERE document_id=NEW.document_id)
  OR EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id))
BEGIN SELECT RAISE(ABORT,'invalid index restart'); END
;

CREATE TRIGGER active_corpus_publication_initial BEFORE INSERT ON active_corpus_publications
  WHEN (java_model_rebuild_authorized(NEW.document_id)=0) AND ((java_replacement_authorized(NEW.document_id)=0) AND (EXISTS(SELECT 1 FROM active_corpus_publications WHERE document_id=NEW.document_id)
  OR NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id
    WHERE p.id=NEW.publication_id AND (j.rebuild_sequence=0 OR j.restart_of IS NOT NULL) AND j.base_publication_id IS NULL))) BEGIN SELECT RAISE(ABORT,'invalid initial active publication'); END
;

CREATE TRIGGER cleanup_schema_objects_no_update BEFORE UPDATE ON cleanup_schema_objects BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END
;

CREATE TRIGGER batch_file_state_insert AFTER INSERT ON document_import_files
BEGIN INSERT INTO document_import_file_states(batch_id,ordinal,state) VALUES(NEW.batch_id,NEW.ordinal,'awaiting_upload'); END
;

CREATE TRIGGER batch_file_state_received AFTER UPDATE OF document_id,revision_id,ingestion_task_id ON document_import_files BEGIN UPDATE document_import_file_states SET state=(SELECT CASE WHEN EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=f.document_id) THEN 'deleted'
  WHEN f.document_id IS NULL THEN 'awaiting_upload'
  WHEN g.state IN ('failed','cancelled') THEN g.state
  WHEN a.state='failed' THEN 'failed'
  WHEN j.state IN ('failed','cancelled') THEN j.state
  WHEN j.state='indexed' THEN 'indexed'
  WHEN j.state IN ('queued','processing','prepared') THEN 'indexing'
  WHEN g.state='parsed' THEN 'parsed'
  WHEN g.state='processing' THEN 'parsing'
  ELSE 'queued' END
FROM document_import_files f
LEFT JOIN ingestion_jobs g ON g.id=f.ingestion_task_id
LEFT JOIN import_index_requests a ON a.revision_id=f.revision_id
LEFT JOIN indexing_jobs j ON j.id=a.task_id
 WHERE f.batch_id=document_import_file_states.batch_id AND f.ordinal=document_import_file_states.ordinal) WHERE state<>'duplicate' AND (batch_id,ordinal) IN (SELECT f0.batch_id,f0.ordinal FROM document_import_files f0 WHERE f0.batch_id=NEW.batch_id AND f0.ordinal=NEW.ordinal); END
;

CREATE TRIGGER batch_file_state_parse AFTER UPDATE OF state ON ingestion_jobs WHEN EXISTS(SELECT 1 FROM document_import_files x WHERE x.ingestion_task_id=NEW.id) BEGIN UPDATE document_import_file_states SET state=(SELECT CASE WHEN EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=f.document_id) THEN 'deleted'
  WHEN f.document_id IS NULL THEN 'awaiting_upload'
  WHEN g.state IN ('failed','cancelled') THEN g.state
  WHEN a.state='failed' THEN 'failed'
  WHEN j.state IN ('failed','cancelled') THEN j.state
  WHEN j.state='indexed' THEN 'indexed'
  WHEN j.state IN ('queued','processing','prepared') THEN 'indexing'
  WHEN g.state='parsed' THEN 'parsed'
  WHEN g.state='processing' THEN 'parsing'
  ELSE 'queued' END
FROM document_import_files f
LEFT JOIN ingestion_jobs g ON g.id=f.ingestion_task_id
LEFT JOIN import_index_requests a ON a.revision_id=f.revision_id
LEFT JOIN indexing_jobs j ON j.id=a.task_id
 WHERE f.batch_id=document_import_file_states.batch_id AND f.ordinal=document_import_file_states.ordinal) WHERE state<>'duplicate' AND (batch_id,ordinal) IN (SELECT f0.batch_id,f0.ordinal FROM document_import_files f0 WHERE f0.ingestion_task_id=NEW.id); END
;

CREATE TRIGGER batch_file_state_request_insert AFTER INSERT ON import_index_requests WHEN EXISTS(SELECT 1 FROM document_import_files x WHERE x.revision_id=NEW.revision_id) BEGIN UPDATE document_import_file_states SET state=(SELECT CASE WHEN EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=f.document_id) THEN 'deleted'
  WHEN f.document_id IS NULL THEN 'awaiting_upload'
  WHEN g.state IN ('failed','cancelled') THEN g.state
  WHEN a.state='failed' THEN 'failed'
  WHEN j.state IN ('failed','cancelled') THEN j.state
  WHEN j.state='indexed' THEN 'indexed'
  WHEN j.state IN ('queued','processing','prepared') THEN 'indexing'
  WHEN g.state='parsed' THEN 'parsed'
  WHEN g.state='processing' THEN 'parsing'
  ELSE 'queued' END
FROM document_import_files f
LEFT JOIN ingestion_jobs g ON g.id=f.ingestion_task_id
LEFT JOIN import_index_requests a ON a.revision_id=f.revision_id
LEFT JOIN indexing_jobs j ON j.id=a.task_id
 WHERE f.batch_id=document_import_file_states.batch_id AND f.ordinal=document_import_file_states.ordinal) WHERE state<>'duplicate' AND (batch_id,ordinal) IN (SELECT f0.batch_id,f0.ordinal FROM document_import_files f0 WHERE f0.revision_id=NEW.revision_id); END
;

CREATE TRIGGER batch_file_state_request_update AFTER UPDATE OF state,task_id ON import_index_requests WHEN EXISTS(SELECT 1 FROM document_import_files x WHERE x.revision_id=NEW.revision_id) BEGIN UPDATE document_import_file_states SET state=(SELECT CASE WHEN EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=f.document_id) THEN 'deleted'
  WHEN f.document_id IS NULL THEN 'awaiting_upload'
  WHEN g.state IN ('failed','cancelled') THEN g.state
  WHEN a.state='failed' THEN 'failed'
  WHEN j.state IN ('failed','cancelled') THEN j.state
  WHEN j.state='indexed' THEN 'indexed'
  WHEN j.state IN ('queued','processing','prepared') THEN 'indexing'
  WHEN g.state='parsed' THEN 'parsed'
  WHEN g.state='processing' THEN 'parsing'
  ELSE 'queued' END
FROM document_import_files f
LEFT JOIN ingestion_jobs g ON g.id=f.ingestion_task_id
LEFT JOIN import_index_requests a ON a.revision_id=f.revision_id
LEFT JOIN indexing_jobs j ON j.id=a.task_id
 WHERE f.batch_id=document_import_file_states.batch_id AND f.ordinal=document_import_file_states.ordinal) WHERE state<>'duplicate' AND (batch_id,ordinal) IN (SELECT f0.batch_id,f0.ordinal FROM document_import_files f0 WHERE f0.revision_id=NEW.revision_id); END
;

CREATE TRIGGER batch_file_state_index AFTER UPDATE OF state ON indexing_jobs WHEN EXISTS(SELECT 1 FROM import_index_requests x JOIN document_import_files y ON y.revision_id=x.revision_id WHERE x.task_id=NEW.id) BEGIN UPDATE document_import_file_states SET state=(SELECT CASE WHEN EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=f.document_id) THEN 'deleted'
  WHEN f.document_id IS NULL THEN 'awaiting_upload'
  WHEN g.state IN ('failed','cancelled') THEN g.state
  WHEN a.state='failed' THEN 'failed'
  WHEN j.state IN ('failed','cancelled') THEN j.state
  WHEN j.state='indexed' THEN 'indexed'
  WHEN j.state IN ('queued','processing','prepared') THEN 'indexing'
  WHEN g.state='parsed' THEN 'parsed'
  WHEN g.state='processing' THEN 'parsing'
  ELSE 'queued' END
FROM document_import_files f
LEFT JOIN ingestion_jobs g ON g.id=f.ingestion_task_id
LEFT JOIN import_index_requests a ON a.revision_id=f.revision_id
LEFT JOIN indexing_jobs j ON j.id=a.task_id
 WHERE f.batch_id=document_import_file_states.batch_id AND f.ordinal=document_import_file_states.ordinal) WHERE state<>'duplicate' AND (batch_id,ordinal) IN (SELECT f0.batch_id,f0.ordinal FROM document_import_files f0 WHERE f0.revision_id IN (SELECT x.revision_id FROM import_index_requests x WHERE x.task_id=NEW.id)); END
;

CREATE TRIGGER batch_file_state_tombstone AFTER INSERT ON document_tombstones WHEN EXISTS(SELECT 1 FROM document_import_files x WHERE x.document_id=NEW.document_id) BEGIN UPDATE document_import_file_states SET state=(SELECT CASE WHEN EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=f.document_id) THEN 'deleted'
  WHEN f.document_id IS NULL THEN 'awaiting_upload'
  WHEN g.state IN ('failed','cancelled') THEN g.state
  WHEN a.state='failed' THEN 'failed'
  WHEN j.state IN ('failed','cancelled') THEN j.state
  WHEN j.state='indexed' THEN 'indexed'
  WHEN j.state IN ('queued','processing','prepared') THEN 'indexing'
  WHEN g.state='parsed' THEN 'parsed'
  WHEN g.state='processing' THEN 'parsing'
  ELSE 'queued' END
FROM document_import_files f
LEFT JOIN ingestion_jobs g ON g.id=f.ingestion_task_id
LEFT JOIN import_index_requests a ON a.revision_id=f.revision_id
LEFT JOIN indexing_jobs j ON j.id=a.task_id
 WHERE f.batch_id=document_import_file_states.batch_id AND f.ordinal=document_import_file_states.ordinal) WHERE state<>'duplicate' AND (batch_id,ordinal) IN (SELECT f0.batch_id,f0.ordinal FROM document_import_files f0 WHERE f0.document_id=NEW.document_id); END
;

CREATE TRIGGER batch_times_insert AFTER INSERT ON document_import_batches
BEGIN INSERT INTO document_import_batch_times(batch_id,updated_at) VALUES(NEW.id,NEW.created_at); END
;

CREATE TRIGGER batch_times_update AFTER UPDATE OF halted_reason,request_limit ON document_import_batches WHEN OLD.halted_reason IS NOT NEW.halted_reason OR OLD.request_limit<>NEW.request_limit BEGIN UPDATE document_import_batch_times SET updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE batch_id=NEW.id; END
;

CREATE TRIGGER batch_file_times_insert AFTER INSERT ON document_import_file_states BEGIN INSERT INTO document_import_file_times(batch_id,ordinal,changed_at) VALUES(NEW.batch_id,NEW.ordinal,strftime('%Y-%m-%dT%H:%M:%fZ','now')); END
;

CREATE TRIGGER batch_file_times_update AFTER UPDATE OF state ON document_import_file_states WHEN OLD.state IS NOT NEW.state BEGIN UPDATE document_import_file_times SET changed_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),finished_at=CASE WHEN NEW.state IN ('indexed','failed','cancelled') THEN strftime('%Y-%m-%dT%H:%M:%fZ','now') END WHERE batch_id=NEW.batch_id AND ordinal=NEW.ordinal; UPDATE document_import_batch_times SET updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE batch_id=NEW.batch_id; END
;

CREATE TRIGGER cleanup_library_no_update BEFORE UPDATE ON cleanup_library BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END
;

CREATE TRIGGER cleanup_library_no_delete BEFORE DELETE ON cleanup_library BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END
;

CREATE TRIGGER cleanup_library_no_replace BEFORE INSERT ON cleanup_library WHEN EXISTS(SELECT 1 FROM cleanup_library WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'immutable cleanup identity'); END
;
