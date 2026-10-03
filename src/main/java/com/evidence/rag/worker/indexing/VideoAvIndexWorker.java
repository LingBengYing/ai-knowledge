package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.GeminiVideoAvEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvReceipt;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvRouteReceipt;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** All real media entries and both complete route receipts in one isolated JVM; no description. */
public final class VideoAvIndexWorker {
  private VideoAvIndexWorker() {}

  public static void main(String[] args) {
    var output = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    run(System.in, output, true);
  }

  public static void run(InputStream input, OutputStream output) {
    run(input, output, false);
  }

  private static void run(InputStream input, OutputStream output, boolean isolated) {
    try {
      var request = VideoAvIndexProtocol.readRequest(input);
      long started = System.nanoTime();
      output.write(VideoAvIndexProtocol.encode(index(request, started, isolated)));
      output.flush();
      return;
    } catch (IOException | RuntimeException failed) {
      try {
        output.write(
            VideoAvIndexProtocol.failure(
                failed instanceof ProcessTextIndexer.Failure f
                        && f.code().equals("indexing_timeout")
                    ? 2
                    : 1));
        output.flush();
      } catch (IOException ignored) {
        /* Parent rejects incomplete output without diagnostics. */
      }
    }
  }

  private static VideoAvReceipt index(
      VideoAvIndexProtocol.Request request, long started, boolean isolated) {
    var claim = request.claim();
    var entries = new ArrayList<VideoAvReceipt.Entry>();
    var receipts = new ArrayList<VideoAvRouteReceipt>();
    {
      for (var route : VideoAvRoute.values()) {
        var settings =
            route == VideoAvRoute.VISUAL ? request.visualProjection() : request.audioProjection();
        var target = claim.targets().target(route);
        var digests = new TreeMap<String, String>();
        var material =
            claim.compilation().windows().stream()
                .filter(w -> route == VideoAvRoute.VISUAL ? w.video() != null : w.audio() != null)
                .toList();
        if (material.isEmpty()) {
          receipts.add(
              new VideoAvRouteReceipt(
                  route,
                  0,
                  VideoAvProfile.absenceSha256(
                      claim.original().sourceSha256(),
                      claim.generationId(),
                      target,
                      route,
                      claim.compilation().epoch(),
                      VideoAvProfile.windowManifestSha256(claim.compilation())),
                  null));
          continue;
        }
        // Each route owns one lease, avoiding non-reentrant local stripe collision across roles.
        try (var lifetime =
                IndexWorkerLifetime.acquire(
                    settings, request.parent(), remaining(request, started), isolated);
            var projection =
                new MilvusRestProjection(bounded(settings, remaining(request, started)))) {
          lifetime.check();
          projection.initialize();
          lifetime.check();
          for (var window : material) {
            lifetime.check();
            List<Double> vector;
            try (var models =
                new GeminiVideoAvEmbeddingModels(
                    bounded(request.models(), remaining(request, started)))) {
              vector =
                  route == VideoAvRoute.VISUAL
                      ? models.embedVideo(window.video())
                      : models.embedAudio(window.audio());
            }
            lifetime.check();
            String media =
                route == VideoAvRoute.VISUAL ? window.video().sha256() : window.audio().pcmSha256();
            String physical =
                VideoAvProfile.physicalSegmentId(claim.generationId(), route, window.id());
            var entry =
                new RetrievalProjection.Entry(
                    physical,
                    claim.actor().workspaceId(),
                    claim.original().documentId(),
                    claim.generationId(),
                    media,
                    vector);
            projection.upsert(List.of(entry));
            lifetime.check();
            String digest = RetrievalProjection.entryDigest(entry);
            digests.put(physical, digest);
            entries.add(
                new VideoAvReceipt.Entry(window.id(), route, physical, entry.vector(), digest));
          }
          var manifest =
              new RetrievalProjection.RevisionManifest(
                  claim.actor().workspaceId(),
                  claim.original().documentId(),
                  claim.generationId(),
                  digests);
          var verified = projection.verify(manifest);
          lifetime.check();
          receipts.add(new VideoAvRouteReceipt(route, digests.size(), manifest.sha256(), verified));
        }
      }
      remaining(request, started);
      var receipt = new VideoAvReceipt(entries, receipts.get(0), receipts.get(1));
      VideoAvIndexProtocol.verify(claim, receipt);
      return receipt;
    }
  }

  private static Duration remaining(VideoAvIndexProtocol.Request request, long started) {
    if (!request.parent().alive()) {
      throw new ProcessTextIndexer.Failure("indexing_parent_lost");
    }
    if (Thread.currentThread().isInterrupted()) {
      throw new ProcessTextIndexer.Failure("worker_interrupted");
    }
    long nanos = request.timeout().toNanos() - (System.nanoTime() - started);
    if (nanos < Duration.ofMillis(10).toNanos()) {
      throw new ProcessTextIndexer.Failure("indexing_timeout");
    }
    return Duration.ofNanos(nanos);
  }

  private static Duration bounded(Duration original, Duration remaining) {
    return original.compareTo(remaining) < 0 ? original : remaining;
  }

  private static GeminiVideoAvEmbeddingModels.Configuration bounded(
      GeminiVideoAvEmbeddingModels.Configuration original, Duration remaining) {
    return new GeminiVideoAvEmbeddingModels.Configuration(
        original.endpoint(),
        original.modelRevision(),
        original.dimensions(),
        original.decoderRevision(),
        bounded(original.deadline(), remaining),
        original.maxResponseBytes(),
        original.allowLoopbackHttp());
  }

  private static MilvusRestProjection.Settings bounded(
      MilvusRestProjection.Settings original, Duration remaining) {
    return new MilvusRestProjection.Settings(
        original.endpoint(),
        original.token(),
        original.database(),
        original.collection(),
        original.workspaceId(),
        original.embeddingIdentity(),
        original.dimension(),
        bounded(original.timeout(), remaining),
        original.maxResponseBytes(),
        original.allowLoopbackHttp());
  }
}
