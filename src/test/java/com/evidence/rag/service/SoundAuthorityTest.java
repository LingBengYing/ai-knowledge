package com.evidence.rag.service;

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

class SoundAuthorityTest {
  @TempDir Path directory;

  @Test
  void genuineSoundMetadataAndTagsNeverInventLegacyTasks() {
    try (var fixture = new SoundTestFixture(directory)) {
      var original = fixture.register("library", SoundTestFixture.pcm(32002, 4), false);
      var management = management(fixture);
      var query = new DocumentQuery("", "audio", "ready", null, null, "updated_desc", 1, 20);
      var before = management.listDocuments(SoundTestFixture.OWNER, query).items().getFirst();
      assertFalse(before.syntheticFixture());
      assertFalse(before.canIndex());
      assertNull(before.latestJob());
      assertNull(before.latestIndexJob());
      assertEquals(original.revisionId(), before.registeredRevisionId());
      var changed =
          management.updateDocument(
              SoundTestFixture.OWNER,
              "library",
              new DocumentPatchCommand(true, "合成声音", false, null, true, List.of("声音")));
      assertEquals("合成声音", changed.displayName());
      assertEquals(List.of("声音"), changed.tags());
      fixture.publish(original, SoundTestFixture.pcm(32002, 4));
      var after =
          management
              .listDocuments(
                  SoundTestFixture.OWNER,
                  new DocumentQuery("", "audio", "parsed", null, "声音", "updated_desc", 1, 20))
              .items()
              .getFirst();
      assertEquals("parsed", after.status());
      assertEquals("indexed", after.indexStatus());
      assertEquals(original.revisionId(), after.activeRevisionId());
      assertEquals(fixture.publications.getFirst().id(), after.indexPublicationId());
      assertFalse(after.canIndex());
      assertNull(after.latestJob());
      assertNull(after.latestIndexJob());
      assertEquals(0, fixture.count("SELECT count(*) FROM ingestion_jobs"));
      assertEquals(0, fixture.count("SELECT count(*) FROM indexing_jobs"));
    }
  }

  private static ManagementService management(SoundTestFixture fixture) {
    return new ManagementService(
        fixture.store,
        fixture.management,
        new IngestionRepository(fixture.store),
        new IndexingRepository(fixture.store),
        new DocumentPermissionPolicy());
  }
}
