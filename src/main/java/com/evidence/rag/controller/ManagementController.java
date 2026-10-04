package com.evidence.rag.controller;

import com.evidence.rag.model.dto.FolderListResult;
import com.evidence.rag.model.dto.FolderRemovalResult;
import com.evidence.rag.model.dto.FolderResult;
import com.evidence.rag.model.vo.DocumentActionsResponse;
import com.evidence.rag.model.vo.DocumentPageResponse;
import com.evidence.rag.model.vo.DocumentResponse;
import com.evidence.rag.model.vo.TagListResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.ManagementService;
import com.evidence.rag.web.converter.ManagementRequestMapper;
import com.evidence.rag.web.converter.ManagementResponseMapper;
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
  private final ManagementService management;

  public ManagementController(ManagementService management) {
    this.management = management;
  }

  @GetMapping("/documents")
  public DocumentPageResponse documents(
      HttpServletRequest request, @RequestParam Map<String, String> query) {
    return ManagementResponseMapper.page(
        management.listDocuments(
            AuthenticatedActor.require(request), ManagementRequestMapper.query(query)));
  }

  @PatchMapping("/documents/{documentId}")
  public DocumentResponse updateDocument(
      HttpServletRequest request,
      @PathVariable String documentId,
      @RequestBody Map<String, Object> body) {
    return ManagementResponseMapper.document(
        management.updateDocument(
            AuthenticatedActor.require(request), documentId, ManagementRequestMapper.patch(body)));
  }

  @GetMapping("/folders")
  public FolderListResult folders(HttpServletRequest request) {
    return ManagementResponseMapper.folders(
        management.listFolders(AuthenticatedActor.require(request)));
  }

  @PostMapping("/folders")
  @ResponseStatus(HttpStatus.CREATED)
  public FolderResult createFolder(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    return management.createFolder(
        AuthenticatedActor.require(request), ManagementRequestMapper.folderName(body));
  }

  @PatchMapping("/folders/{folderId}")
  public FolderResult renameFolder(
      HttpServletRequest request,
      @PathVariable String folderId,
      @RequestBody Map<String, Object> body) {
    return management.renameFolder(
        AuthenticatedActor.require(request), folderId, ManagementRequestMapper.folderName(body));
  }

  @DeleteMapping("/folders/{folderId}")
  public FolderRemovalResult removeFolder(
      HttpServletRequest request, @PathVariable String folderId) {
    return management.removeFolder(AuthenticatedActor.require(request), folderId);
  }

  @GetMapping("/tags")
  public TagListResponse tags(HttpServletRequest request) {
    return ManagementResponseMapper.tags(management.listTags(AuthenticatedActor.require(request)));
  }

  @PostMapping("/document-actions")
  public DocumentActionsResponse actions(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    var actor = AuthenticatedActor.require(request);
    var command = ManagementRequestMapper.action(body);
    return ManagementResponseMapper.actions(
        management.documentActions(actor, command), command.action());
  }
}
