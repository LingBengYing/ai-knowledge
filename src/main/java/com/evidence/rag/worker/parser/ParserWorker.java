package com.evidence.rag.worker.parser;

import com.evidence.rag.tool.parser.TextParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/** One-request parser process entrypoint; owns child output but no authority or HTTP state. */
public final class ParserWorker {
  private ParserWorker() {}

  public static void main(String[] args) {
    var protocol = System.out;
    // Libraries may log to System.out. Only this entrypoint owns the child JVM globals;
    // run(InputStream, OutputStream) remains safe to exercise inside a test/application JVM.
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    run(System.in, protocol);
  }

  /**
   * Exactly one bounded request. Failures disclose neither document data nor library exceptions.
   */
  public static void run(InputStream input, OutputStream output) {
    byte[] response;
    try {
      var request = ParserProtocol.readRequest(input);
      response =
          ParserProtocol.encode(
              new TextParser().parse(request.filename(), request.mime(), request.content()));
    } catch (IOException | RuntimeException ignored) {
      response = ParserProtocol.failure();
    }
    try {
      output.write(response);
      output.flush();
    } catch (IOException ignored) {
      // The parent rejects incomplete output. Never send diagnostics on the protocol stream.
    }
  }
}
