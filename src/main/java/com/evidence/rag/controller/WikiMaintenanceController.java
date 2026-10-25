package com.evidence.rag.controller;

import com.evidence.rag.model.dto.WikiMaintenanceResult;
import com.evidence.rag.model.dto.WikiProposalResult;
import com.evidence.rag.model.dto.WikiSourceChangesResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.WikiMaintenanceService;
import com.evidence.rag.service.WikiWorkspaceService;
import com.evidence.rag.web.converter.WikiRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class WikiMaintenanceController {
  private final WikiMaintenanceService maintenance;
  private final WikiWorkspaceService wiki;

  public WikiMaintenanceController(WikiMaintenanceService maintenance, WikiWorkspaceService wiki) {
    this.maintenance = maintenance;
    this.wiki = wiki;
  }

  @GetMapping("/v1/wiki/maintenance")
  public ResponseEntity<WikiMaintenanceResult> list(HttpServletRequest request) {
    noBody(request);
    WikiRequestMapper.query(request.getParameterMap(), Set.of("offset", "limit", "kind"));
    return ok(
        maintenance.list(
            AuthenticatedActor.require(request),
            WikiRequestMapper.number(request.getParameter("offset"), 0),
            WikiRequestMapper.number(request.getParameter("limit"), 20),
            request.getParameter("kind")));
  }

  @GetMapping("/v1/wiki/pages/{id}/source-changes")
  public ResponseEntity<WikiSourceChangesResult> sources(
      HttpServletRequest request, @PathVariable String id) {
    noBody(request);
    WikiRequestMapper.query(request.getParameterMap(), Set.of());
    return ok(maintenance.sourceChanges(AuthenticatedActor.require(request), id));
  }

  @PostMapping(
      value = "/v1/wiki/pages/{id}/refresh-proposal",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WikiProposalResult> refresh(
      HttpServletRequest request, @PathVariable String id) {
    if (request.getQueryString() != null) {
      throw WikiRequestMapper.invalid();
    }
    try {
      var command =
          WikiRequestMapper.refresh(
              request.getInputStream().readNBytes(WikiRequestMapper.MAX_BYTES + 1));
      return ResponseEntity.status(201)
          .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
          .body(wiki.refreshProposal(AuthenticatedActor.require(request), id, command));
    } catch (IOException failure) {
      throw WikiRequestMapper.invalid();
    }
  }

  private static void noBody(HttpServletRequest request) {
    if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
      throw WikiRequestMapper.invalid();
    }
  }

  private static <T> ResponseEntity<T> ok(T value) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(value);
  }
}
