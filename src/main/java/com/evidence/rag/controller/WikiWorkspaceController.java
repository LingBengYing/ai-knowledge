package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SynopsisSourceMaterial;
import com.evidence.rag.model.dto.WikiPageListResult;
import com.evidence.rag.model.dto.WikiPagePurgeResult;
import com.evidence.rag.model.dto.WikiPageResult;
import com.evidence.rag.model.dto.WikiProposalListResult;
import com.evidence.rag.model.dto.WikiProposalResult;
import com.evidence.rag.model.vo.WikiSourceResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.WikiWorkspaceService;
import com.evidence.rag.web.MediaContentResponse;
import com.evidence.rag.web.converter.WikiRequestMapper;
import com.evidence.rag.web.converter.WikiSourceResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP Adapter only: compilation, source binding and review transactions live in the Service. */
@RestController
public final class WikiWorkspaceController {
  private final WikiWorkspaceService service;

  public WikiWorkspaceController(WikiWorkspaceService service) {
    this.service = service;
  }

  @GetMapping("/v1/wiki/pages")
  public ResponseEntity<WikiPageListResult> pages(HttpServletRequest request) {
    noBody(request);
    WikiRequestMapper.query(request.getParameterMap(), Set.of("offset", "limit", "q", "state"));
    return ok(
        service.pages(
            AuthenticatedActor.require(request),
            WikiRequestMapper.number(request.getParameter("offset"), 0),
            WikiRequestMapper.number(request.getParameter("limit"), 20),
            request.getParameter("q"),
            WikiRequestMapper.pageState(request.getParameter("state"))));
  }

  @DeleteMapping("/v1/wiki/pages/{id}")
  public ResponseEntity<WikiPageResult> delete(
      HttpServletRequest request, @PathVariable String id) {
    noBody(request);
    WikiRequestMapper.query(request.getParameterMap(), Set.of("version", "lifecycle_version"));
    return ok(
        service.delete(
            AuthenticatedActor.require(request),
            id,
            WikiRequestMapper.lifecycle(
                request.getParameter("version"), request.getParameter("lifecycle_version"))));
  }

  @PostMapping(value = "/v1/wiki/pages/{id}/restore", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WikiPageResult> restore(
      HttpServletRequest request, @PathVariable String id) {
    return ok(
        service.restore(
            AuthenticatedActor.require(request), id, WikiRequestMapper.lifecycle(body(request))));
  }

  @DeleteMapping("/v1/wiki/pages/{id}/purge")
  public ResponseEntity<WikiPagePurgeResult> purge(
      HttpServletRequest request, @PathVariable String id) {
    noBody(request);
    WikiRequestMapper.query(request.getParameterMap(), Set.of("version", "lifecycle_version"));
    return ok(
        service.purge(
            AuthenticatedActor.require(request),
            id,
            WikiRequestMapper.lifecycle(
                request.getParameter("version"), request.getParameter("lifecycle_version"))));
  }

  @GetMapping("/v1/wiki/pages/{id}")
  public ResponseEntity<WikiPageResult> page(HttpServletRequest request, @PathVariable String id) {
    readOnly(request);
    return ok(service.page(AuthenticatedActor.require(request), id));
  }

  @GetMapping("/v1/wiki/pages/{id}/versions/{version}")
  public ResponseEntity<WikiPageResult> version(
      HttpServletRequest request, @PathVariable String id, @PathVariable long version) {
    readOnly(request);
    return ok(service.version(AuthenticatedActor.require(request), id, version));
  }

  @GetMapping("/v1/wiki/proposals")
  public ResponseEntity<WikiProposalListResult> proposals(HttpServletRequest request) {
    noBody(request);
    WikiRequestMapper.query(request.getParameterMap(), Set.of("offset", "limit", "status"));
    String status = request.getParameter("status");
    return ok(
        service.proposals(
            AuthenticatedActor.require(request),
            WikiRequestMapper.number(request.getParameter("offset"), 0),
            WikiRequestMapper.number(request.getParameter("limit"), 20),
            status == null ? "pending" : status));
  }

  @GetMapping("/v1/wiki/proposals/{id}")
  public ResponseEntity<WikiProposalResult> proposal(
      HttpServletRequest request, @PathVariable String id) {
    readOnly(request);
    return ok(service.proposal(AuthenticatedActor.require(request), id));
  }

  @PostMapping(value = "/v1/wiki/proposals", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WikiProposalResult> create(HttpServletRequest request) {
    var actor = AuthenticatedActor.require(request);
    var command = WikiRequestMapper.proposal(body(request));
    return ResponseEntity.status(201)
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(service.createProposal(actor, command));
  }

  @PostMapping(
      value = "/v1/wiki/proposals/{id}/accept",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WikiPageResult> accept(
      HttpServletRequest request, @PathVariable String id) {
    var actor = AuthenticatedActor.require(request);
    return ok(service.accept(actor, id, WikiRequestMapper.accept(body(request))));
  }

  @PostMapping(
      value = "/v1/wiki/proposals/{id}/dismiss",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WikiProposalResult> dismiss(
      HttpServletRequest request, @PathVariable String id) {
    var actor = AuthenticatedActor.require(request);
    WikiRequestMapper.dismiss(body(request));
    return ok(service.dismiss(actor, id));
  }

  @GetMapping("/v1/wiki/pages/{id}/versions/{version}/sources/{sourceId}")
  public ResponseEntity<WikiSourceResponse> source(
      HttpServletRequest request,
      @PathVariable String id,
      @PathVariable long version,
      @PathVariable String sourceId) {
    readOnly(request);
    var material = service.source(AuthenticatedActor.require(request), id, version, sourceId);
    return ok(
        WikiSourceResponseMapper.source(
            sourceId,
            "/v1/wiki/pages/" + id + "/versions/" + version + "/sources/" + sourceId,
            material));
  }

  @GetMapping("/v1/wiki/proposals/{id}/sources/{sourceId}")
  public ResponseEntity<WikiSourceResponse> proposalSource(
      HttpServletRequest request, @PathVariable String id, @PathVariable String sourceId) {
    readOnly(request);
    var material = service.proposalSource(AuthenticatedActor.require(request), id, sourceId);
    return ok(
        WikiSourceResponseMapper.source(
            sourceId, "/v1/wiki/proposals/" + id + "/sources/" + sourceId, material));
  }

  @GetMapping("/v1/wiki/pages/{id}/versions/{version}/sources/{sourceId}/{part:content|frame}")
  public ResponseEntity<byte[]> sourceContent(
      HttpServletRequest request,
      @PathVariable String id,
      @PathVariable long version,
      @PathVariable String sourceId,
      @PathVariable String part) {
    readOnly(request);
    return content(
        request, service.source(AuthenticatedActor.require(request), id, version, sourceId), part);
  }

  @GetMapping("/v1/wiki/proposals/{id}/sources/{sourceId}/{part:content|frame}")
  public ResponseEntity<byte[]> proposalContent(
      HttpServletRequest request,
      @PathVariable String id,
      @PathVariable String sourceId,
      @PathVariable String part) {
    readOnly(request);
    return content(
        request, service.proposalSource(AuthenticatedActor.require(request), id, sourceId), part);
  }

  private static ResponseEntity<byte[]> content(
      HttpServletRequest request, SynopsisSourceMaterial material, String part) {
    if (part.equals("frame")) {
      if (material.frame() == null) {
        throw ModelValues.notFound();
      }
      return MediaContentResponse.create(
          material.frame().mediaType(),
          material.frame().content(),
          Collections.list(request.getHeaders(HttpHeaders.RANGE)));
    }
    return MediaContentResponse.create(
        material.mediaType(),
        material.content(),
        Collections.list(request.getHeaders(HttpHeaders.RANGE)));
  }

  private static <T> ResponseEntity<T> ok(T value) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(value);
  }

  private static byte[] body(HttpServletRequest request) {
    if (request.getQueryString() != null) {
      throw WikiRequestMapper.invalid();
    }
    try {
      return request.getInputStream().readNBytes(WikiRequestMapper.MAX_BYTES + 1);
    } catch (IOException failed) {
      throw WikiRequestMapper.invalid();
    }
  }

  private static void noBody(HttpServletRequest request) {
    if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
      throw WikiRequestMapper.invalid();
    }
  }

  private static void readOnly(HttpServletRequest request) {
    noBody(request);
    if (request.getQueryString() != null) {
      throw WikiRequestMapper.invalid();
    }
  }
}
