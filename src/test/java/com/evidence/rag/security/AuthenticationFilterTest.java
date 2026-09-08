package com.evidence.rag.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.security.web.AuthenticationFilter;
import com.evidence.rag.security.web.RequestAuthenticator;
import com.evidence.rag.web.HttpProblemMapper;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

class AuthenticationFilterTest {
  @TempDir Path directory;

  private AuthenticationFilter filter() {
    RequestAuthenticator module =
        RequestAuthenticatorTest.authenticator(
            RequestAuthenticatorTest.properties(directory, "development_headers"),
            RequestAuthenticatorTest.CLOCK);
    return new AuthenticationFilter(
        module,
        (request, response, handler, exception) -> {
          assertInstanceOf(ApplicationException.class, exception);
          ApplicationException problem = (ApplicationException) exception;
          response.setStatus(HttpProblemMapper.status(problem));
          response.setHeader("X-Test-ApplicationException-Code", problem.code());
          return new ModelAndView();
        });
  }

  private MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }

  @Test
  void storesOnlyValidatedIdentityBeforeCallingController() throws Exception {
    MockHttpServletRequest request = request("GET", "/v1/management/documents");
    request.addHeader("X-Workspace-Id", "org-main");
    request.addHeader("X-Principal-Id", "editor");
    AtomicBoolean called = new AtomicBoolean();
    filter()
        .doFilter(
            request,
            new MockHttpServletResponse(),
            (req, res) -> {
              called.set(true);
              assertEquals(
                  new Actor("org-main", "editor"),
                  AuthenticatedActor.require((jakarta.servlet.http.HttpServletRequest) req));
            });
    assertTrue(called.get());
  }

  @Test
  void authenticationFailureDoesNotEnterController() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter()
        .doFilter(
            request("GET", "/v1/management/documents"),
            response,
            (req, res) -> fail("Unauthorized controller"));
    assertEquals(422, response.getStatus());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/v1/config", "/", "/health/live", "/assets/workbench.js"})
  void publicReadDoesNotRequireIdentity(String path) throws Exception {
    AtomicBoolean called = new AtomicBoolean();
    filter()
        .doFilter(
            request("GET", path), new MockHttpServletResponse(), (req, res) -> called.set(true));
    assertTrue(called.get());
  }

  @Test
  void postToConfigIsNotPublic() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter()
        .doFilter(
            request("POST", "/v1/config"), response, (req, res) -> fail("Unauthorized mutation"));
    assertEquals(422, response.getStatus());
  }

  @Test
  void sessionExchangesDoNotNeedAnExistingIdentity() throws Exception {
    for (String method : new String[] {"POST", "DELETE"}) {
      AtomicBoolean called = new AtomicBoolean();
      filter()
          .doFilter(
              request(method, "/v1/session"),
              new MockHttpServletResponse(),
              (req, res) -> called.set(true));
      assertTrue(called.get());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://evil.example",
        "null",
        "http://localhost:9999",
        "https://localhost",
        "http://localhost/extra",
        "http://user@localhost",
        "http://localhost?x=1"
      })
  void sessionMutationRejectsCrossOriginIncludingSameHostDifferentPort(String origin)
      throws Exception {
    MockHttpServletRequest request = request("POST", "/v1/session");
    request.addHeader("Origin", origin);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter().doFilter(request, response, (req, res) -> fail("Cross-origin mutation"));
    assertEquals(403, response.getStatus());
  }

  @Test
  void sameOriginMutationAndNoOriginCliRemainAvailable() throws Exception {
    MockHttpServletRequest request = request("DELETE", "/v1/session");
    request.addHeader("Origin", "http://localhost");
    AtomicBoolean called = new AtomicBoolean();
    filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> called.set(true));
    assertTrue(called.get());
    request = request("POST", "/v1/session");
    request.setServerPort(18084);
    request.addHeader("Origin", "http://localhost:18084");
    called.set(false);
    filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> called.set(true));
    assertTrue(called.get());
  }

  @Test
  void duplicateOriginIsRejectedAndCrossOriginReadsStillNeedAuthentication() throws Exception {
    MockHttpServletRequest request = request("DELETE", "/v1/session");
    request.addHeader("Origin", "http://localhost");
    request.addHeader("Origin", "http://evil.example");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter().doFilter(request, response, (req, res) -> fail("Ambiguous origin"));
    assertEquals(403, response.getStatus());
    request = request("GET", "/v1/management/documents");
    request.addHeader("Origin", "http://evil.example");
    response = new MockHttpServletResponse();
    filter().doFilter(request, response, (req, res) -> fail("Unauthorized read"));
    assertEquals(422, response.getStatus());
  }

  @Test
  void encodedAndContextRelativeApiPathsCannotBypassAuthentication() throws Exception {
    MockHttpServletRequest encoded = request("GET", "/%76%31/management/documents");
    encoded.setServletPath("/v1/management/documents");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter().doFilter(encoded, response, (req, res) -> fail("Encoded auth bypass"));
    assertEquals(422, response.getStatus());
    MockHttpServletRequest context = request("GET", "/app/v1/management/documents");
    context.setContextPath("/app");
    context.setServletPath("/v1/management/documents");
    response = new MockHttpServletResponse();
    filter().doFilter(context, response, (req, res) -> fail("Context auth bypass"));
    assertEquals(422, response.getStatus());
  }

  @Test
  void resolverFailureStillLeavesDeniedStatusAndBearerChallenge() throws Exception {
    RequestAuthenticator module =
        RequestAuthenticatorTest.authenticator(
            RequestAuthenticatorTest.properties(directory, "jwt"), RequestAuthenticatorTest.CLOCK);
    AuthenticationFilter filter =
        new AuthenticationFilter(module, (request, response, handler, exception) -> null);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request("GET", "/v1/management/documents"), response, (req, res) -> fail("Unauthorized"));
    assertEquals(401, response.getStatus());
    assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
  }
}
