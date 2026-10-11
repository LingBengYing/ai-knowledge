package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.controller.ConversationController;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.repository.ConversationRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.ConversationService;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class ConversationControllerTest {
  @TempDir Path directory;

  @Test
  void actorCrudHasStablePublicShapeAndRejectsUnrelatedFields() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var controller =
          new ConversationController(
              new ConversationService(
                  store, new ConversationRepository(store), "org-main", 24000, 4));
      var request = new MockHttpServletRequest();
      assertThrows(ApplicationException.class, () -> controller.list(request, 0, 30));
      request.setAttribute(AuthenticatedActor.class.getName(), new Actor("org-main", "owner"));
      var created = controller.create(request, Map.of("title", "合成对话"));
      assertEquals(201, created.getStatusCode().value());
      assertEquals("private, no-store", created.getHeaders().getCacheControl());
      String id = created.getBody().id();
      assertEquals(1, controller.list(request, 0, 30).getBody().items().size());
      var detail = controller.get(request, id, 0, 20).getBody();
      assertTrue(detail.turns().isEmpty());
      var json = JsonMapper.builder().build().valueToTree(detail);
      assertTrue(json.path("conversation").has("turn_count"));
      assertTrue(json.path("conversation").path("compression").has("covered_turn_count"));
      assertEquals("新标题", controller.rename(request, id, Map.of("title", "新标题")).getBody().title());
      assertThrows(
          ApplicationException.class,
          () -> controller.create(request, Map.of("title", "正常", "actor_id", "foreign")));
      assertThrows(
          ApplicationException.class,
          () -> controller.rename(request, id, Map.of("summary", "不能伪造摘要")));
      request.setParameter("actor_id", "foreign");
      assertThrows(ApplicationException.class, () -> controller.list(request, 0, 30));
      request.removeParameter("actor_id");
      assertEquals(204, controller.delete(request, id).getStatusCode().value());
      assertTrue(controller.list(request, 0, 30).getBody().items().isEmpty());
    }
  }
}
