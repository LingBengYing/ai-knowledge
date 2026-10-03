package com.evidence.rag.service;

import com.evidence.rag.client.model.VideoAvEmbeddingModels;
import com.evidence.rag.client.model.VideoAvModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoAvClip;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvEpoch;
import com.evidence.rag.model.domain.VideoAvFact;
import com.evidence.rag.model.domain.VideoAvFrameTiming;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvWindow;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.VideoAvDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** New reference-query fixture; the frozen text-question fixture and its assertions stay intact. */
final class VideoAvQueryTestFixture implements AutoCloseable {
  final VideoAvTestFixture base;
  final VideoAvCompilationService compiler;
  final List<String> queryDecoded = new ArrayList<>();
  final List<VideoAvClip> clips = new ArrayList<>();
  final List<AudioWaveform> waveforms = new ArrayList<>();
  final List<String> referenceSources = new ArrayList<>();
  Consumer<QueryAttachment> afterQueryDecode = attachment -> {};
  Runnable beforeProvider = () -> {};
  Consumer<VideoAvClip> onClip = clip -> {};
  Function<VideoAvClip, List<Double>> clipVector = clip -> List.of(0.0, 1.0);
  Function<AudioWaveform, List<Double>> audioVector = wave -> List.of(0.0, 1.0);
  String failedSource;
  boolean mediaOnlyMatch = true;

  VideoAvQueryTestFixture(Path directory) {
    base = new VideoAvTestFixture(directory);
    compiler =
        new VideoAvCompilationService(
            new VideoAvDecoder() {
              public String revision() {
                return base.decoderRevision;
              }

              public VideoAvCompilation decode(String filename, String mime, byte[] bytes) {
                base.store.transaction(() -> null);
                String sha = ModelValues.sha256(bytes);
                base.decodes++;
                if (referenceSources.contains(sha)) {
                  queryDecoded.add(sha);
                  if (sha.equals(failedSource)) {
                    throw new TextParser.Failure("parser_invalid_output");
                  }
                  afterQueryDecode.accept(new QueryAttachment(filename, mime, bytes));
                }
                return base.compilations.get(sha);
              }

              public void close() {}
            },
            1,
            Duration.ofSeconds(5));
    base.search =
        (route, query) -> {
          if (mediaOnlyMatch && query.vector().getFirst() == 1.0) {
            return List.of();
          }
          return base.publications.stream()
              .filter(p -> query.scope().documentRevisions().containsKey(p.documentId()))
              .flatMap(p -> p.windows().stream())
              .map(w -> route == VideoAvRoute.VISUAL ? w.visualPhysicalId() : w.audioPhysicalId())
              .filter(Objects::nonNull)
              .map(id -> new RetrievalProjection.Candidate(id, 1.0))
              .toList();
        };
  }

  QueryAttachment reference(String id, int windows, int videos, int samples) {
    byte[] source = ("0000ftypisom0000-reference-" + id).getBytes(StandardCharsets.UTF_8);
    var input = new QueryAttachment(id + ".mp4", "video/mp4", source);
    String sha = input.sha256();
    var material = new ArrayList<VideoAvWindow>();
    for (int ordinal = 0; ordinal < windows; ordinal++) {
      long start = ordinal * VideoAvTestFixture.EPOCH.ticksPerSecond();
      VideoAvClip clip = null;
      if (ordinal < videos) {
        byte[] bytes = ("query-clip-" + id + "-" + ordinal).getBytes(StandardCharsets.UTF_8);
        var frames =
            List.of(new VideoAvFrameTiming(ordinal, 0, 720000, 128, 72, ModelValues.sha256(bytes)));
        clip =
            new VideoAvClip(
                bytes,
                ModelValues.sha256(bytes),
                0,
                720000,
                frames,
                VideoAvProfile.framesManifestSha256(frames));
      }
      AudioWaveform audio = null;
      int first = ordinal * 16000, end = Math.min(first + 16000, samples);
      if (end > first) {
        byte[] pcm = new byte[2 * (end - first)];
        if (ordinal != 1) {
          Arrays.fill(pcm, (byte) (ordinal + 1));
        }
        audio = new AudioWaveform(sha, VideoAvTestFixture.DECODER, first, end, pcm);
      }
      material.add(
          new VideoAvWindow(
              VideoAvProfile.windowId(sha, ordinal), ordinal, start, start + 720000, clip, audio));
    }
    base.compilations.put(
        sha,
        new VideoAvCompilation(
            sha,
            VideoAvTestFixture.DECODER,
            VideoAvTestFixture.EPOCH,
            windows * 720000L,
            samples > 0,
            material));
    referenceSources.add(sha);
    return input;
  }

  VideoAvAnswerService answers() {
    return answers(Duration.ofSeconds(10));
  }

  VideoAvAnswerService answers(Duration budget) {
    var models =
        new VideoAvModels() {
          public String revision() {
            return base.modelRevision;
          }

          public Draft draft(
              String question, VideoAvWindow window, VideoAvEpoch epoch, VideoAvMode mode) {
            base.store.transaction(() -> null);
            base.questions.add(question);
            base.drafts.add(window);
            base.epochs.add(epoch);
            return base.draft.apply(window, mode);
          }

          public Verification verify(
              String question,
              VideoAvWindow window,
              VideoAvEpoch epoch,
              VideoAvMode mode,
              List<VideoAvFact> facts) {
            base.store.transaction(() -> null);
            base.questions.add(question);
            base.verifies.add(window);
            base.epochs.add(epoch);
            var result = base.verify.apply(facts);
            base.afterVerify.run();
            return result;
          }
        };
    var embedding =
        new VideoAvEmbeddingModels() {
          public String revision() {
            return base.embeddingRevision;
          }

          public int dimensions() {
            return 2;
          }

          public List<Double> embedText(String question) {
            base.store.transaction(() -> null);
            beforeProvider.run();
            base.questions.add(question);
            base.textEmbeds++;
            return List.of(1.0, 0.0);
          }

          public List<Double> embedVideo(VideoAvClip clip) {
            base.store.transaction(() -> null);
            beforeProvider.run();
            clips.add(clip);
            onClip.accept(clip);
            return clipVector.apply(clip);
          }

          public List<Double> embedAudio(AudioWaveform wave) {
            base.store.transaction(() -> null);
            beforeProvider.run();
            waveforms.add(wave);
            return audioVector.apply(wave);
          }
        };
    return new VideoAvAnswerService(
        base.store,
        base.repository,
        base.management,
        new DocumentPermissionPolicy(),
        compiler,
        models,
        embedding,
        projection(VideoAvRoute.VISUAL),
        projection(VideoAvRoute.AUDIO),
        VideoAvTestFixture.TARGETS,
        VideoAvTestFixture.PROFILE,
        budget,
        2);
  }

  private RetrievalProjection projection(VideoAvRoute route) {
    return new RetrievalProjection() {
      public String identity() {
        return VideoAvTestFixture.TARGETS.target(route).projectionIdentity();
      }

      public void initialize() {
        throw new AssertionError("References cannot initialize index");
      }

      public void prepareSearch() {
        base.store.transaction(() -> null);
      }

      public VerifiedRevision verify(RevisionManifest manifest) {
        throw new AssertionError("References cannot verify index writes");
      }

      public void upsert(List<Entry> entries) {
        throw new AssertionError("References cannot index");
      }

      public List<Candidate> search(Query query) {
        base.store.transaction(() -> null);
        base.queries.add(new VideoAvTestFixture.Invocation(route, query));
        return base.search.apply(route, query);
      }
    };
  }

  public void close() {
    compiler.close();
    base.close();
  }
}
