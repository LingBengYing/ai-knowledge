package com.evidence.rag.controller;

import com.evidence.rag.model.dto.WikiCatalogResult;
import com.evidence.rag.model.dto.WikiDraftCommand;
import com.evidence.rag.model.dto.WikiDraftListResult;
import com.evidence.rag.model.dto.WikiDraftResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.WikiCatalogService;
import com.evidence.rag.service.WikiDraftService;
import com.evidence.rag.web.converter.WikiDraftRequestMapper;
import com.evidence.rag.web.converter.WikiRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** File discovery and editable notes are separate from reviewed, versioned knowledge pages. */
@RestController
public final class WikiLibraryController {
  private final WikiCatalogService catalog;
  private final WikiDraftService drafts;

  public WikiLibraryController(WikiCatalogService catalog, WikiDraftService drafts) {
    this.catalog = catalog;
    this.drafts = drafts;
  }

  @GetMapping("/v1/wiki/catalog")
  public ResponseEntity<WikiCatalogResult> catalog(HttpServletRequest request) {
    noBody(request);
    WikiRequestMapper.query(request.getParameterMap(), Set.of("offset", "limit", "q", "kind"));
    return ok(
        catalog.list(
            AuthenticatedActor.require(request),
            WikiRequestMapper.number(request.getParameter("offset"), 0),
            WikiRequestMapper.number(request.getParameter("limit"), 20),
            request.getParameter("q"),
            request.getParameter("kind")));
  }

  @GetMapping("/v1/wiki/drafts")
  public ResponseEntity<WikiDraftListResult> drafts(HttpServletRequest request) {
    noBody(request);
    WikiRequestMapper.query(request.getParameterMap(), Set.of("offset", "limit"));
    return ok(
        drafts.list(
            AuthenticatedActor.require(request),
            WikiRequestMapper.number(request.getParameter("offset"), 0),
            WikiRequestMapper.number(request.getParameter("limit"), 20)));
  }

  @GetMapping("/v1/wiki/drafts/{id}")
  public ResponseEntity<WikiDraftResult> draft(
      HttpServletRequest request, @PathVariable String id) {
    noBody(request);
    WikiRequestMapper.query(request.getParameterMap(), Set.of());
    return ok(drafts.get(AuthenticatedActor.require(request), id));
  }

  @PostMapping(value = "/v1/wiki/drafts", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WikiDraftResult> create(HttpServletRequest request) {
    var actor = AuthenticatedActor.require(request);
    return ResponseEntity.status(201)
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(drafts.create(actor, WikiDraftRequestMapper.create(body(request))));
  }

  @PutMapping(value = "/v1/wiki/drafts/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WikiDraftResult> update(
      HttpServletRequest request, @PathVariable String id) {
    var actor = AuthenticatedActor.require(request);
    var command = WikiDraftRequestMapper.update(body(request));
    return ok(
        drafts.update(
            actor, id, command.version(), new WikiDraftCommand(command.title(), command.body())));
  }

  @DeleteMapping("/v1/wiki/drafts/{id}")
  public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String id) {
    noBody(request);
    WikiRequestMapper.query(request.getParameterMap(), Set.of("version"));
    drafts.delete(
        AuthenticatedActor.require(request),
        id,
        WikiDraftRequestMapper.version(request.getParameter("version")));
    return ResponseEntity.noContent()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .build();
  }

  private static <T> ResponseEntity<T> ok(T value) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(value);
  }

  private static byte[] body(HttpServletRequest request) {
    if (request.getQueryString() != null) {
      throw WikiRequestMapper.invalid();
    }
    try {
      return request.getInputStream().readNBytes(WikiDraftRequestMapper.MAX_BYTES + 1);
    } catch (IOException failure) {
      throw WikiRequestMapper.invalid();
    }
  }

  private static void noBody(HttpServletRequest request) {
    if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
      throw WikiRequestMapper.invalid();
    }
  }
}
