package com.evidence.rag.worker.parser;

import com.evidence.rag.tool.parser.TextParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.function.Function;
import java.util.function.Supplier;

/** One-request parser process entrypoint; owns child output but no authority or HTTP state. */
public final class ParserWorker {
  private static final int CLEANUP_FAILED_EXIT = 70;

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
    byte[] response =
        respondThenRelease(
            System.in,
            () -> new PdfWorkerLifetime(args),
            lifetime -> new PdfOcrCompiler(lifetime.ocr()));
    if (response == null) {
      // Parsed, but native OCR cleanup could not be confirmed. Exit non-zero without writing so the
      // parent reports parser_failed instead of reading a success frame plus a failure frame.
      Runtime.getRuntime().halt(CLEANUP_FAILED_EXIT);
    }
    write(protocol, response);
  }

  /**
   * Answers one PDF request inside its native resources and releases them before anything is
   * written. Returns the single frame to write, or null when the request was answered but the
   * resources could not be released.
   */
  @SuppressWarnings("try") // Release is never interrupted; any close() failure is handled below.
  static <T extends AutoCloseable> byte[] respondThenRelease(
      InputStream input, Supplier<T> open, Function<T, PdfOcrCompiler> compiler) {
    byte[] response = null;
    try (T resources = open.get()) {
      response = respond(input, compiler.apply(resources));
    } catch (Exception failed) {
      return response == null ? ParserProtocol.failure() : null;
    }
    return response;
  }

  /**
   * Exactly one bounded request. Failures disclose neither document data nor library exceptions.
   */
  public static void run(InputStream input, OutputStream output) {
    write(output, respond(input, null));
  }

  private static byte[] respond(InputStream input, PdfOcrCompiler pdfs) {
    try {
      var request = ParserProtocol.readRequest(input);
      return ParserProtocol.encode(
          pdfs == null
              ? new TextParser().parse(request.filename(), request.mime(), request.content())
              : pdfs.parse(request.filename(), request.mime(), request.content()));
    } catch (IOException | RuntimeException ignored) {
      return ParserProtocol.failure();
    }
  }

  private static void write(OutputStream output, byte[] response) {
    try {
      output.write(response);
      output.flush();
    } catch (IOException ignored) {
      // The parent rejects incomplete output. Never send diagnostics on the protocol stream.
    }
  }
}
