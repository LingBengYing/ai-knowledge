import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/** Test-only bounded loopback bridge; never included in the application image. */
public final class HttpProbe {
  public static void main(String[] args) {
    Thread.ofPlatform().daemon(true).start(() -> {
      try {
        Thread.sleep(12000);
        System.err.println("container_http_probe_deadline");
        System.exit(2);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    });
    try {
      if (args.length != 5 || !args[1].startsWith("/")) {
        throw new IllegalArgumentException();
      }
      URI uri = URI.create("http://127.0.0.1:18084" + args[1]);
      if (!"127.0.0.1".equals(uri.getHost()) || uri.getPort() != 18084) {
        throw new IllegalArgumentException();
      }
      var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
      if (!args[2].equals("-")) {
        request.header("X-Workspace-Id", "org-main");
        request.header("X-Principal-Id", args[2]);
      }
      if (!args[4].equals("-")) {
        request.header("Origin", args[4]);
      }
      if (!args[3].equals("-") && Files.size(Path.of(args[3])) > 1024 * 1024) {
        throw new IllegalArgumentException();
      }
      byte[] body = args[3].equals("-") ? new byte[0] : Files.readAllBytes(Path.of(args[3]));
      if (body.length > 1024 * 1024) throw new IllegalArgumentException();
      request.header("Content-Type", "application/octet-stream");
      request.method(args[0], HttpRequest.BodyPublishers.ofByteArray(body));
      try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER).build()) {
        // Cap buffering; the process watchdog also bounds body reads and client shutdown.
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var stream = response.body()) {
          bytes = stream.readNBytes(65537);
        }
        if (bytes.length > 65536) throw new IllegalStateException();
        System.out.println(response.statusCode());
        for (String name : new String[] {"cache-control", "x-content-type-options", "x-request-id",
            "referrer-policy", "content-security-policy"}) {
          System.out.println(response.headers().firstValue(name).orElse(""));
        }
        System.out.println(Base64.getEncoder().encodeToString(bytes));
      }
    } catch (Exception failure) {
      System.err.println("container_http_probe_failed");
      System.exit(1);
    }
  }
}
