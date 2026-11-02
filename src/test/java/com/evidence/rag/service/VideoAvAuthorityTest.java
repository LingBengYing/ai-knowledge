package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.evidence.rag.model.dto.DocumentPatchCommand;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoAvAuthorityTest {
  @TempDir Path directory;

  @Test
  void realRawManagementStateTagsAndOriginalNeverInventLegacyTasks() {
    try (var fixture = new VideoAvTestFixture(directory)) {
      var original = fixture.register("library", 1, 1, 0, false);
      var management = management(fixture);
      var before =
          management
              .listDocuments(
                  VideoAvTestFixture.OWNER,
                  new DocumentQuery("", "video", "ready", null, null, "updated_desc", 1, 20))
              .items()
              .getFirst();
      assertFalse(before.syntheticFixture());
      assertFalse(before.canIndex());
      assertNull(before.latestJob());
      assertNull(before.latestIndexJob());
      assertEquals(original.revisionId(), before.registeredRevisionId());
      var changed =
          management.updateDocument(
              VideoAvTestFixture.OWNER,
              "library",
              new DocumentPatchCommand(true, "音画对照", false, null, true, List.of("视频")));
      assertEquals("音画对照", changed.displayName());
      assertEquals(List.of("视频"), changed.tags());
      fixture.publish(original, fixture.compilations.get(original.sourceSha256()));
      var after =
          management
              .listDocuments(
                  VideoAvTestFixture.OWNER,
                  new DocumentQuery("", "video", "parsed", null, "视频", "updated_desc", 1, 20))
              .items()
              .getFirst();
      assertEquals("indexed", after.indexStatus());
      assertEquals(original.revisionId(), after.activeRevisionId());
      assertEquals(fixture.publications.getFirst().id(), after.indexPublicationId());
      assertEquals(1, after.segmentCount());
      assertArrayEquals(
          original.content(),
          management
              .documentContent(VideoAvTestFixture.OWNER, "library", original.revisionId())
              .content());
      assertEquals(0, fixture.count("SELECT count(*) FROM ingestion_jobs"));
      assertEquals(0, fixture.count("SELECT count(*) FROM indexing_jobs"));
    }
  }

  private static ManagementService management(VideoAvTestFixture fixture) {
    return new ManagementService(
        fixture.store,
        fixture.management,
        new IngestionRepository(fixture.store),
        new IndexingRepository(fixture.store),
        new DocumentPermissionPolicy());
  }
}
