package com.evidence.rag.worker.indexing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/** Fixed-field model telemetry only; stderr is never forwarded as arbitrary diagnostic text. */
final class IndexWorkerModelHttpLog {
  private static final String LOGGER = "com.evidence.rag.client.model.ModelHttpTransport";
  private static final String READY = "index_model_audit_ready";
  private static final String DONE = "index_model_audit_complete";
  private static final String UUID =
      "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";
  private static final Pattern START =
      Pattern.compile(
          "model_http_started id="
              + UUID
              + " provider=(siliconflow|deepseek|compatible) operation=(embedding|rerank|generation|transcription|model)");
  private static final Pattern FINISH =
      Pattern.compile(
          "model_http_finished id="
              + UUID
              + " status=(-1|[1-5][0-9]{2}) transport_ok=(true|false) elapsed_ms=[0-9]{1,12}");

  private IndexWorkerModelHttpLog() {}

  static ChildLog child() {
    return new ChildLog();
  }

  static void drain(InputStream input) {
    var logger = LoggerFactory.getLogger(LOGGER);
    var reader = new Reader(logger);
    byte[] line = new byte[256];
    int length = 0;
    boolean oversized = false;
    try (input) {
      byte[] bytes = new byte[4096];
      int count;
      while ((count = input.read(bytes)) != -1) {
        for (int i = 0; i < count; i++) {
          int value = bytes[i] & 0xff;
          if (value == '\n') {
            if (!oversized) {
              reader.accept(new String(line, 0, length, StandardCharsets.US_ASCII));
            } else {
              reader.discard(new String(line, 0, length, StandardCharsets.US_ASCII));
            }
            length = 0;
            oversized = false;
          } else if (length < line.length && value >= 32 && value <= 126 && !oversized) {
            line[length++] = (byte) value;
          } else {
            oversized = true;
          }
        }
      }
      if (length != 0 || oversized) {
        reader.discard(new String(line, 0, length, StandardCharsets.US_ASCII));
      }
    } catch (IOException ignored) {
      reader.complete = false;
    } finally {
      logger.info(
          "index_model_audit_closed started={} finished={} complete={}",
          reader.started.size(),
          reader.finished.size(),
          reader.ready
              && reader.complete
              && reader.valid
              && reader.started.equals(reader.finished));
    }
  }

  private static final class Reader {
    private final org.slf4j.Logger logger;
    private final Set<String> started = new HashSet<>();
    private final Set<String> finished = new HashSet<>();
    private boolean ready;
    private boolean complete;
    private boolean valid = true;

    Reader(org.slf4j.Logger logger) {
      this.logger = logger;
    }

    void accept(String line) {
      if (line.equals(READY) && !ready) {
        ready = true;
        return;
      }
      if (!ready) {
        return;
      }
      if (complete) {
        discard(line);
        return;
      }
      if (line.equals(DONE)) {
        complete = true;
        return;
      }
      var start = START.matcher(line);
      if (start.matches()
          && started.size() < IndexProtocol.MAX_SEGMENTS
          && started.add(start.group(1))) {
        logger.info("{}", line);
        return;
      }
      var finish = FINISH.matcher(line);
      if (finish.matches() && started.contains(finish.group(1)) && finished.add(finish.group(1))) {
        logger.info("{}", line);
        return;
      }
      discard(line);
    }

    void discard(String line) {
      if (ready && (line.startsWith("model_http_") || line.startsWith("index_model_audit_"))) {
        valid = false;
      }
    }
  }

  static final class ChildLog implements AutoCloseable {
    private final PrintStream diagnostics = System.err;
    private final Logger logger = (Logger) LoggerFactory.getLogger(LOGGER);
    private final PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    private final OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();

    ChildLog() {
      encoder.setContext(logger.getLoggerContext());
      encoder.setPattern("%msg%n");
      encoder.start();
      appender.setContext(logger.getLoggerContext());
      appender.setEncoder(encoder);
      appender.setOutputStream(diagnostics);
      appender.start();
      logger.detachAndStopAllAppenders();
      logger.setAdditive(false);
      logger.setLevel(Level.INFO);
      logger.addAppender(appender);
      diagnostics.println(READY);
      diagnostics.flush();
    }

    @Override
    public void close() {
      diagnostics.println(DONE);
      diagnostics.flush();
      logger.detachAppender(appender);
      appender.stop();
      encoder.stop();
    }
  }
}
