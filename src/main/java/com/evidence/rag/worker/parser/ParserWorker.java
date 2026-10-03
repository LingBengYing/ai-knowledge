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
    if (args.length == 0) {
      run(System.in, protocol);
      return;
    }
    try (var lifetime = new PdfWorkerLifetime(args)) {
      run(System.in, protocol, new PdfOcrCompiler(lifetime.ocr()));
    } catch (RuntimeException ignored) {
      try {
        protocol.write(ParserProtocol.failure());
        protocol.flush();
      } catch (IOException disconnected) {
        // Parent rejects incomplete output.
      }
    }
  }

  /**
   * Exactly one bounded request. Failures disclose neither document data nor library exceptions.
   */
  public static void run(InputStream input, OutputStream output) {
    run(input, output, null);
  }

  private static void run(InputStream input, OutputStream output, PdfOcrCompiler pdfs) {
    byte[] response;
    try {
      var request = ParserProtocol.readRequest(input);
      response =
          ParserProtocol.encode(
              pdfs == null
                  ? new TextParser().parse(request.filename(), request.mime(), request.content())
                  : pdfs.parse(request.filename(), request.mime(), request.content()));
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
