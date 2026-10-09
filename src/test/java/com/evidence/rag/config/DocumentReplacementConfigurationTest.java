package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.controller.DocumentReplacementController;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.web.ProblemHandler;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class DocumentReplacementConfigurationTest {
  private static final Actor OWNER = new Actor("org", "owner");
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(ints = {1500, 7300})
  void configuredUploadDeadlineReachesTheReplacementUploaderWithoutConfigCoupling(int timeoutMs)
      throws Exception {
    try (var authority = new AuthorityTestContext(directory);
        var context = new AnnotationConfigApplicationContext()) {
      var original =
          authority.uploadDocument(
              OWNER, "original.txt", "text/plain", "original".getBytes(StandardCharsets.UTF_8));
      var claim = authority.claimIngestion("org").orElseThrow();
      assertTrue(
          authority.completeIngestion(
              claim,
              new ParsedText(
                  List.of(new TextPage(1, "original")),
                  List.of(new TextSegment(0, 1, 0, 8, "original")))));
      var beans = context.getBeanFactory();
      beans.registerSingleton("store", authority.store());
      beans.registerSingleton("management", new ManagementRepository(authority.store()));
      beans.registerSingleton("permissions", new DocumentPermissionPolicy());
      beans.registerSingleton("ingestion", authority.ingestion());
      beans.registerSingleton("settings", new IngestionSettings(true, 5000, timeoutMs));
      beans.registerSingleton("unrelatedTimeout", 999);
      beans.registerSingleton("json", JsonMapper.builder().build());
      beans.registerSingleton("errors", new ProblemHandler());
      context.register(DocumentReplacementConfiguration.class, DocumentReplacementController.class);
      context.refresh();
      String documentId = original.get("document_id").toString();
      String revisionId = original.get("revision_id").toString();
      var request = request(documentId, revisionId);
      var response = new MockHttpServletResponse();
      context.getBean(DocumentReplacementController.class).upload(request, response, documentId);
      assertEquals(202, response.getStatus(), response.getContentAsString());
      assertEquals(timeoutMs, request.getAsyncContext().getTimeout());
      var result = JsonMapper.builder().build().readTree(response.getContentAsString());
      assertEquals(documentId, result.path("document_id").asString());
      assertEquals(revisionId, result.path("base_revision_id").asString());
      assertEquals("queued", result.path("state").asString());
    }
  }

  private static MockHttpServletRequest request(String documentId, String revisionId) {
    byte[] content = "replacement".getBytes(StandardCharsets.UTF_8);
    var input =
        new ServletInputStream() {
          private final ByteArrayInputStream bytes = new ByteArrayInputStream(content);

          @Override
          public boolean isFinished() {
            return bytes.available() == 0;
          }

          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public int read() {
            return bytes.read();
          }

          @Override
          public void setReadListener(ReadListener listener) {
            try {
              listener.onDataAvailable();
              listener.onAllDataRead();
            } catch (IOException failure) {
              listener.onError(failure);
            }
          }
        };
    var request =
        new MockHttpServletRequest("POST", "/v1/documents/" + documentId + "/replacement") {
          @Override
          public ServletInputStream getInputStream() {
            return input;
          }
        };
    request.setAsyncSupported(true);
    request.setContent(content);
    request.addHeader("Content-Type", "application/octet-stream");
    request.addParameter("filename", "replacement.txt");
    request.addParameter("base_revision_id", revisionId);
    request.setAttribute(AuthenticatedActor.class.getName(), OWNER);
    return request;
  }
}
