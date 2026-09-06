package com.evidence.rag.management;

import com.evidence.rag.shared.Actor;
import com.evidence.rag.shared.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP Adapter. Identity is supplied exclusively by the authentication Module. */
@RestController
@RequestMapping("/v1/management")
public final class ManagementController {
  private final ManagementModule management;

  public ManagementController(ManagementModule management) {
    this.management = management;
  }

  @GetMapping("/documents")
  public Map<String, Object> documents(
      HttpServletRequest request, @RequestParam Map<String, String> query) {
    return management.listDocuments(actor(request), query);
  }

  @PatchMapping("/documents/{documentId}")
  public Map<String, Object> updateDocument(
      HttpServletRequest request,
      @PathVariable String documentId,
      @RequestBody Map<String, Object> body) {
    return management.updateDocument(actor(request), documentId, body);
  }

  @GetMapping("/folders")
  public Map<String, Object> folders(HttpServletRequest request) {
    return management.listFolders(actor(request));
  }

  @PostMapping("/folders")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createFolder(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    return management.createFolder(actor(request), body);
  }

  @PatchMapping("/folders/{folderId}")
  public Map<String, Object> renameFolder(
      HttpServletRequest request,
      @PathVariable String folderId,
      @RequestBody Map<String, Object> body) {
    return management.renameFolder(actor(request), folderId, body);
  }

  @DeleteMapping("/folders/{folderId}")
  public Map<String, Object> removeFolder(
      HttpServletRequest request, @PathVariable String folderId) {
    return management.removeFolder(actor(request), folderId);
  }

  @GetMapping("/tags")
  public Map<String, Object> tags(HttpServletRequest request) {
    return management.listTags(actor(request));
  }

  @PostMapping("/document-actions")
  public Map<String, Object> actions(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    return management.documentActions(actor(request), body);
  }

  private static Actor actor(HttpServletRequest request) {
    if (request.getAttribute(Actor.REQUEST_ATTRIBUTE) instanceof Actor actor) return actor;
    throw new Problem(401, "unauthenticated", "请先登录。");
  }
}
