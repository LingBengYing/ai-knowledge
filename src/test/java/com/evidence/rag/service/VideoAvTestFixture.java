package com.evidence.rag.service;

import com.evidence.rag.client.model.VideoAvEmbeddingModels;
import com.evidence.rag.client.model.VideoAvModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoAvAudioMetadata;
import com.evidence.rag.model.domain.VideoAvClip;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvEpoch;
import com.evidence.rag.model.domain.VideoAvFact;
import com.evidence.rag.model.domain.VideoAvFrameTiming;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvPublication;
import com.evidence.rag.model.domain.VideoAvPublishedWindow;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvRouteReceipt;
import com.evidence.rag.model.domain.VideoAvTargets;
import com.evidence.rag.model.domain.VideoAvVideoMetadata;
import com.evidence.rag.model.domain.VideoAvWindow;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.VideoAvRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.worker.parser.VideoAvDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Real temporary SQLite with explicit synthetic media/model seams; no native quality claim. */
final class VideoAvTestFixture implements AutoCloseable {
  static final Actor OWNER = new Actor("av-workspace", "owner");
  static final String MODEL = "synthetic-av-model-v1";
  static final String DECODER = "synthetic-av-decoder-v1";
  static final String EMBEDDING = "synthetic-av-embedding-v1";
  static final VideoAvEpoch EPOCH = new VideoAvEpoch(9000, 1, 90000, 720000);
  static final VideoAvTargets TARGETS =
      new VideoAvTargets(
          new IndexTarget(EMBEDDING, "a".repeat(64), EMBEDDING, 2),
          new IndexTarget(EMBEDDING, "b".repeat(64), EMBEDDING, 2));
  static final String PROFILE = VideoAvProfile.fingerprint(TARGETS, MODEL, DECODER, 1);
  static final String QUESTION = "画面和声音出现了什么，是否同步？";
  static final String FACT = "敲击动作与响声同步。";
  final Path directory;
  final SqliteAuthorityStore store;
  final ManagementRepository management;
  final VideoAvRepository repository;
  final VideoAvCompilationService compiler;
  final List<VideoAvPublication> publications = new ArrayList<>();
  final Map<String, VideoAvCompilation> compilations = new HashMap<>();
  final List<Invocation> queries = new ArrayList<>();
  final List<VideoAvWindow> drafts = new ArrayList<>();
  final List<VideoAvWindow> verifies = new ArrayList<>();
  final List<String> questions = new ArrayList<>();
  final List<VideoAvEpoch> epochs = new ArrayList<>();
  int decodes;
  int textEmbeds;
  String modelRevision = MODEL;
  String embeddingRevision = EMBEDDING;
  String decoderRevision = DECODER;
  List<Double> vector = List.of(1.0, 0.0);
  Runnable afterVerify = () -> {};
  BiFunction<VideoAvWindow, VideoAvMode, VideoAvModels.Draft> draft =
      (window, mode) ->
          new VideoAvModels.Draft(
              true,
              List.of(new VideoAvModels.Claim(FACT, VideoAvRequirement.valueOf(mode.name()))));
  Function<List<VideoAvFact>, VideoAvModels.Verification> verify =
      facts ->
          new VideoAvModels.Verification(
              true,
              facts.stream()
                  .map(
                      f ->
                          new VideoAvModels.Support(
                              f.id(),
                              true,
                              f.requirement() != VideoAvRequirement.AUDIO,
                              f.requirement() != VideoAvRequirement.VISUAL))
                  .toList());
  BiFunction<VideoAvRoute, RetrievalProjection.Query, List<RetrievalProjection.Candidate>> search =
      (route, query) ->
          publications.stream()
              .filter(p -> query.scope().documentRevisions().containsKey(p.documentId()))
              .flatMap(p -> p.windows().stream())
              .map(w -> route == VideoAvRoute.VISUAL ? w.visualPhysicalId() : w.audioPhysicalId())
              .filter(java.util.Objects::nonNull)
              .map(id -> new RetrievalProjection.Candidate(id, 1.0))
              .toList();

  VideoAvTestFixture(Path directory) {
    this.directory = directory;
    store = new SqliteAuthorityStore(directory);
    management = new ManagementRepository(store);
    repository = new VideoAvRepository(store);
    compiler =
        new VideoAvCompilationService(
            new VideoAvDecoder() {
              public String revision() {
                return decoderRevision;
              }

              public VideoAvCompilation decode(String filename, String mime, byte[] bytes) {
                store.transaction(() -> null);
                decodes++;
                return compilations.get(ModelValues.sha256(bytes));
              }

              public void close() {}
            },
            1,
            Duration.ofSeconds(5));
  }

  DocumentOriginal register(
      String id, int windows, int videoWindows, int audioSamples, boolean publish) {
    byte[] bytes = ("0000ftypisom0000-synthetic-" + id).getBytes(StandardCharsets.UTF_8);
    var original =
        new DocumentOriginal(
            id,
            "revision-" + id,
            id + ".mp4",
            "video",
            "video/mp4",
            ModelValues.sha256(bytes),
            bytes.length,
            bytes);
    store.transaction(
        () -> {
          management.insertDocument(
              OWNER,
              new SyntheticDocument(
                  id,
                  original.filename(),
                  "video",
                  original.mediaType(),
                  original.revisionId(),
                  original.sourceSha256(),
                  bytes.length),
              Instant.now().toString());
          management.insertGrant(id, OWNER.principalId(), "owner");
          repository.insertOriginal(original, Instant.now().toString());
          return null;
        });
    var material = new ArrayList<VideoAvWindow>();
    for (int ordinal = 0; ordinal < windows; ordinal++) {
      long start = ordinal * EPOCH.ticksPerSecond();
      VideoAvClip clip = null;
      if (ordinal < videoWindows) {
        byte[] content = ("synthetic-clip-" + id + "-" + ordinal).getBytes(StandardCharsets.UTF_8);
        var frames =
            List.of(
                new VideoAvFrameTiming(
                    ordinal * 2, 0, 360000, 128, 72, ModelValues.sha256(content)),
                new VideoAvFrameTiming(
                    ordinal * 2 + 1, 360000, 360000, 128, 72, ModelValues.sha256(content)));
        clip =
            new VideoAvClip(
                content,
                ModelValues.sha256(content),
                0,
                720000,
                frames,
                VideoAvProfile.framesManifestSha256(frames));
      }
      AudioWaveform audio = null;
      int firstSample = ordinal * 16000;
      int lastSample = Math.min(firstSample + 16000, audioSamples);
      if (firstSample < lastSample) {
        byte[] pcm = new byte[2 * (lastSample - firstSample)];
        Arrays.fill(pcm, (byte) (ordinal + 1));
        audio = new AudioWaveform(original.sourceSha256(), DECODER, firstSample, lastSample, pcm);
      }
      material.add(
          new VideoAvWindow(
              VideoAvProfile.windowId(original.revisionId(), ordinal),
              ordinal,
              start,
              start + 720000,
              clip,
              audio));
    }
    var compilation =
        new VideoAvCompilation(
            original.sourceSha256(), DECODER, EPOCH, windows * 720000L, audioSamples > 0, material);
    compilations.put(original.sourceSha256(), compilation);
    if (publish) {
      publish(original, compilation);
    }
    return original;
  }

  VideoAvPublication publish(DocumentOriginal original, VideoAvCompilation compilation) {
    String id = UUID.randomUUID().toString();
    var windows = new ArrayList<VideoAvPublishedWindow>();
    for (var window : compilation.windows()) {
      String visual =
          window.video() == null
              ? null
              : VideoAvProfile.physicalSegmentId(id, VideoAvRoute.VISUAL, window.id());
      String audio =
          window.audio() == null
              ? null
              : VideoAvProfile.physicalSegmentId(id, VideoAvRoute.AUDIO, window.id());
      String visualDigest =
          visual == null ? null : entry(id, original.documentId(), visual, window.video().sha256());
      String audioDigest =
          audio == null
              ? null
              : entry(id, original.documentId(), audio, window.audio().pcmSha256());
      windows.add(
          new VideoAvPublishedWindow(
              window.id(),
              window.ordinal(),
              window.startTick(),
              window.endTick(),
              VideoAvVideoMetadata.from(window.video()),
              VideoAvAudioMetadata.from(window.audio()),
              visual,
              visualDigest,
              audio,
              audioDigest));
    }
    String manifest = VideoAvProfile.windowManifestSha256(compilation);
    var publication =
        new VideoAvPublication(
            id,
            OWNER.workspaceId(),
            original.documentId(),
            original.revisionId(),
            original.sourceSha256(),
            original.filename(),
            original.mediaType(),
            original.sizeBytes(),
            compilation.epoch(),
            compilation.durationTick(),
            compilation.hasAudio(),
            DECODER,
            MODEL,
            PROFILE,
            TARGETS.visual(),
            TARGETS.audio(),
            1,
            manifest,
            windows,
            receipt(original, id, windows, manifest, VideoAvRoute.VISUAL),
            receipt(original, id, windows, manifest, VideoAvRoute.AUDIO),
            System.currentTimeMillis());
    store.transaction(
        () -> {
          repository.insertPublication(publication);
          return null;
        });
    publications.add(publication);
    return publication;
  }

  private VideoAvRouteReceipt receipt(
      DocumentOriginal original,
      String generation,
      List<VideoAvPublishedWindow> windows,
      String windowManifest,
      VideoAvRoute route) {
    int count =
        (int)
            windows.stream()
                .filter(w -> route == VideoAvRoute.VISUAL ? w.video() != null : w.audio() != null)
                .count();
    var target = route == VideoAvRoute.VISUAL ? TARGETS.visual() : TARGETS.audio();
    String manifest =
        count == 0
            ? VideoAvProfile.absenceSha256(
                original.sourceSha256(), generation, target, route, EPOCH, windowManifest)
            : VideoAvProfile.routeManifestSha256(
                OWNER.workspaceId(), original.documentId(), generation, route, windows);
    return new VideoAvRouteReceipt(
        route,
        count,
        manifest,
        count == 0 ? null : new VerifiedRevision(target.projectionIdentity(), manifest, count));
  }

  private static String entry(String generation, String document, String physical, String text) {
    return RetrievalProjection.entryDigest(
        new RetrievalProjection.Entry(
            physical, OWNER.workspaceId(), document, generation, text, List.of(1.0, 0.0)));
  }

  VideoAvAnswerService answers() {
    return answers(Duration.ofSeconds(10), 2);
  }

  VideoAvAnswerService answers(Duration budget, int concurrency) {
    var models =
        new VideoAvModels() {
          public String revision() {
            return modelRevision;
          }

          public Draft draft(
              String question, VideoAvWindow window, VideoAvEpoch epoch, VideoAvMode mode) {
            store.transaction(() -> null);
            questions.add(question);
            drafts.add(window);
            epochs.add(epoch);
            return draft.apply(window, mode);
          }

          public Verification verify(
              String question,
              VideoAvWindow window,
              VideoAvEpoch epoch,
              VideoAvMode mode,
              List<VideoAvFact> facts) {
            store.transaction(() -> null);
            questions.add(question);
            verifies.add(window);
            epochs.add(epoch);
            var result = verify.apply(facts);
            afterVerify.run();
            return result;
          }
        };
    var embedding =
        new VideoAvEmbeddingModels() {
          public String revision() {
            return embeddingRevision;
          }

          public int dimensions() {
            return 2;
          }

          public List<Double> embedText(String question) {
            store.transaction(() -> null);
            questions.add(question);
            textEmbeds++;
            return vector;
          }

          public List<Double> embedVideo(VideoAvClip clip) {
            throw new AssertionError("Text question never embeds query clips");
          }

          public List<Double> embedAudio(AudioWaveform waveform) {
            throw new AssertionError("Text question never embeds query audio");
          }
        };
    return new VideoAvAnswerService(
        store,
        repository,
        management,
        new DocumentPermissionPolicy(),
        compiler,
        models,
        embedding,
        projection(VideoAvRoute.VISUAL),
        projection(VideoAvRoute.AUDIO),
        TARGETS,
        PROFILE,
        budget,
        concurrency);
  }

  private RetrievalProjection projection(VideoAvRoute route) {
    return new RetrievalProjection() {
      public String identity() {
        return (route == VideoAvRoute.VISUAL ? TARGETS.visual() : TARGETS.audio())
            .projectionIdentity();
      }

      public void initialize() {
        throw new AssertionError("Question cannot initialize index");
      }

      public void prepareSearch() {
        store.transaction(() -> null);
      }

      public VerifiedRevision verify(RevisionManifest manifest) {
        throw new AssertionError("Question cannot verify writes");
      }

      public void upsert(List<Entry> entries) {
        throw new AssertionError("Question cannot index");
      }

      public List<Candidate> search(Query query) {
        store.transaction(() -> null);
        queries.add(new Invocation(route, query));
        return search.apply(route, query);
      }
    };
  }

  long count(String sql) {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  void sql(String sql) {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  record Invocation(VideoAvRoute route, RetrievalProjection.Query query) {}

  public void close() {
    compiler.close();
    store.close();
  }
}
