package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.security.web.AuthenticatedActor;
import jakarta.servlet.Filter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Real embedded Tomcat: async timeouts are measured by the container from startAsync. */
class BoundedMediaQueryServletTest {
  @TempDir Path directory;
  private Tomcat tomcat;
  private int port;

  @BeforeEach
  void start() throws Exception {
    tomcat = new Tomcat();
    tomcat.setBaseDir(directory.toString());
    tomcat.setPort(0);
    tomcat.getConnector();
    Context context = tomcat.addContext("", null);
    // 2s processing window; the handler needs 1.5s of it.
    add(context, "fits", 2000, 1500);
    // A handler slower than its window still times out.
    add(context, "slow", 200, 5000);
    Filter actor =
        (request, response, chain) -> {
          request.setAttribute(AuthenticatedActor.class.getName(), new Actor("org", "user"));
          chain.doFilter(request, response);
        };
    var definition = new FilterDef();
    definition.setFilterName("actor");
    definition.setFilter(actor);
    definition.setAsyncSupported("true");
    context.addFilterDef(definition);
    var mapping = new FilterMap();
    mapping.setFilterName("actor");
    mapping.addURLPattern("/*");
    context.addFilterMap(mapping);
    tomcat.start();
    port = tomcat.getConnector().getLocalPort();
  }

  @AfterEach
  void stop() throws Exception {
    tomcat.stop();
    tomcat.destroy();
  }

  @Test
  void slowUploadDoesNotShortenTheProcessingWindow() throws Exception {
    long started = System.nanoTime();
    // Upload for 2.5s, then process for 1.5s: past processing+1s measured from startAsync.
    String response = post("fits", 2500);
    assertTrue(response.startsWith("HTTP/1.1 200"), response);
    assertTrue(response.contains("{\"ok\":true}"), response);
    assertTrue(System.nanoTime() - started >= 4_000_000_000L);
  }

  @Test
  void processingBeyondItsWindowStillTimesOut() throws Exception {
    long started = System.nanoTime();
    String response = post("slow", 0);
    assertTrue(response.startsWith("HTTP/1.1 408"), response);
    assertTrue(response.contains("query_attachment_timeout"), response);
    assertTrue(System.nanoTime() - started < 4_000_000_000L, "timeout was not enforced");
  }

  private static void add(Context context, String name, int processingMs, long handlerMs) {
    var servlet =
        new BoundedMediaQueryServlet(
            (actor, body) -> {
              assertEquals("{}", new String(body, StandardCharsets.UTF_8));
              try {
                Thread.sleep(handlerMs);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
              }
              return Map.of("ok", true);
            },
            1024,
            10000,
            processingMs,
            1,
            JsonMapper.builder().build(),
            new ProblemHandler());
    Tomcat.addServlet(context, name, servlet).setAsyncSupported(true);
    context.addServletMappingDecoded("/" + name, name);
  }

  /** Sends half of a JSON body, waits, then the rest, as a slow client upload would. */
  private String post(String path, long uploadMs) throws Exception {
    try (var socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(15000);
      var out = socket.getOutputStream();
      out.write(
          ("POST /"
                  + path
                  + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                  + "Content-Length: 2\r\nConnection: close\r\n\r\n{")
              .getBytes(StandardCharsets.UTF_8));
      out.flush();
      Thread.sleep(uploadMs);
      out.write("}".getBytes(StandardCharsets.UTF_8));
      out.flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
