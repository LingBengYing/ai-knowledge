package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VideoAvQueryRuntimeTest {
  @Test
  void oldVideoAvConstructionCannotAdvertiseUnregisteredQueryEndpoint() {
    var value =
        new RuntimeService(
            "development",
            "test-workspace",
            true,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            true);
    assertTrue(value.capabilities().capabilities().contains("video_av_answers"));
    assertFalse(value.capabilities().capabilities().contains("video_av_query_attachments"));
  }

  @Test
  void registeredVideoAvQueryWorksWithoutSpeechOrOldAttachmentCapabilities() {
    var value = runtime(true, true, true);
    assertTrue(value.capabilities().capabilities().contains("video_av_query_attachments"));
    assertTrue(value.capabilities().capabilities().contains("video_av_sources"));
    assertFalse(value.capabilities().capabilities().contains("query_attachments"));
    assertFalse(value.capabilities().capabilities().contains("audio_answers"));
  }

  @Test
  void endpointPresenceRequiresActualVideoAvGraphAndIngestion() {
    assertFalse(
        runtime(true, true, false)
            .capabilities()
            .capabilities()
            .contains("video_av_query_attachments"));
    assertFalse(
        runtime(true, false, true)
            .capabilities()
            .capabilities()
            .contains("video_av_query_attachments"));
    assertFalse(
        runtime(false, true, true)
            .capabilities()
            .capabilities()
            .contains("video_av_query_attachments"));
  }

  private static RuntimeService runtime(boolean ingestion, boolean videoAv, boolean query) {
    return new RuntimeService(
        "development",
        "test-workspace",
        ingestion,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        videoAv,
        query);
  }
}
