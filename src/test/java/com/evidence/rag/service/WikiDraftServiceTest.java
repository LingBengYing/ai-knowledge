package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.dto.WikiDraftCommand;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.WikiDraftRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WikiDraftServiceTest {
  @TempDir Path directory;
  private static final Actor OWNER = new Actor("org", "owner");
  private static final Actor MEMBER = new Actor("org", "member");

  @Test
  void sharedDraftCrudIsPersistentCasProtectedAndDoesNotPublishEvidence() {
    String id;
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store);
      var created =
          service.create(
              OWNER, new WikiDraftCommand("  笔记  ", "😀\n<script>not executable</script>"));
      id = created.id();
      assertEquals("笔记", created.title());
      assertEquals(created, service.get(MEMBER, id));
      assertEquals(1, service.list(MEMBER, 0, 1).total());
      assertEquals(0, service.list(MEMBER, 1, 1).items().size());
      var changed = service.update(MEMBER, id, 1, new WikiDraftCommand("第二版", "新正文"));
      assertEquals(2, changed.version());
      assertEquals(created.createdAt(), changed.createdAt());
      assertEquals(
          FailureKind.CONFLICT,
          assertThrows(
                  ApplicationException.class,
                  () -> service.update(OWNER, id, 1, new WikiDraftCommand("冲突", "不覆盖")))
              .kind());
      assertEquals(
          FailureKind.CONFLICT,
          assertThrows(ApplicationException.class, () -> service.delete(OWNER, id, 1)).kind());
      assertEquals(changed, service.get(OWNER, id));
      assertEquals(
          FailureKind.FORBIDDEN,
          assertThrows(
                  ApplicationException.class, () -> service.get(new Actor("other", "owner"), id))
              .kind());
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store);
      assertEquals("新正文", service.get(MEMBER, id).body());
      service.delete(MEMBER, id, 2);
      assertEquals(0, service.list(OWNER, 0, 20).total());
      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(ApplicationException.class, () -> service.get(OWNER, id)).kind());
    }
  }

  @Test
  void invalidBodiesAndVersionsDoNotCreateOrOverwriteDrafts() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store);
      assertThrows(
          ApplicationException.class,
          () -> service.create(OWNER, new WikiDraftCommand("", "body")));
      assertThrows(
          ApplicationException.class,
          () -> service.create(OWNER, new WikiDraftCommand("标题", "bad\u0000body")));
      assertThrows(
          ApplicationException.class,
          () -> service.create(OWNER, new WikiDraftCommand("标题", "长".repeat(100_001))));
      assertEquals(0, service.list(OWNER, 0, 20).total());
      var saved = service.create(OWNER, new WikiDraftCommand("标题", ""));
      assertThrows(
          ApplicationException.class,
          () ->
              service.update(OWNER, saved.id(), Long.MAX_VALUE, new WikiDraftCommand("标题", "正文")));
      assertEquals(saved, service.get(OWNER, saved.id()));
    }
  }

  private static WikiDraftService service(SqliteAuthorityStore store) {
    return new WikiDraftService(store, new WikiDraftRepository(store), "org");
  }
}
