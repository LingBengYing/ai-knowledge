package com.evidence.rag.service;

import com.evidence.rag.client.model.SoundEmbeddingModels;
import com.evidence.rag.client.model.SoundModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SoundProfile;
import com.evidence.rag.model.domain.SoundPublication;
import com.evidence.rag.model.domain.SoundSpan;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SoundRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Real SQLite sound authority and explicit local model/decoder substitutes; no quality claim. */
final class SoundTestFixture implements AutoCloseable {
  static final Actor OWNER = new Actor("sound-workspace", "owner");
  static final String MODEL = "synthetic-sound-v1";
  static final String DECODER = "synthetic-sound-decoder-v1";
  static final String EMBEDDING = "synthetic-sound-embedding-v1";
  static final IndexTarget TARGET = new IndexTarget(EMBEDDING, "d".repeat(64), EMBEDDING, 2);
  static final String PROFILE = SoundProfile.fingerprint(TARGET, MODEL, DECODER, 1);
  static final String FACT = "片段中有三次连续敲击声。";
  static final String QUESTION = "片段里是什么声音，出现几次？";
  final Path directory;
  final SqliteAuthorityStore store;
  final ManagementRepository management;
  final SoundRepository repository;
  final SoundCompilationService compiler;
  final List<RetrievalProjection.Query> queries = new ArrayList<>();
  final List<byte[]> queryWavs = new ArrayList<>();
  final List<String> questions = new ArrayList<>();
  final List<AudioWaveform> drafts = new ArrayList<>();
  final List<AudioWaveform> verifies = new ArrayList<>();
  final List<SoundPublication> publications = new ArrayList<>();
  int decodes;
  int textEmbeds;
  String modelRevision = MODEL;
  String embeddingRevision = EMBEDDING;
  String decoderRevision = DECODER;
  Function<AudioWaveform, SoundModels.Draft> draft =
      waveform -> new SoundModels.Draft(true, List.of(FACT));
  Function<AudioWaveform, SoundModels.Verification> verify =
      waveform -> new SoundModels.Verification(true, List.of(true));
  Runnable afterVerify = () -> {};
  Function<RetrievalProjection.Query, List<RetrievalProjection.Candidate>> search =
      query ->
          publications.stream()
              .filter(
                  publication ->
                      query.scope().documentRevisions().containsKey(publication.documentId()))
              .flatMap(publication -> publication.spans().stream())
              .map(span -> new RetrievalProjection.Candidate(span.physicalSegmentId(), 1.0))
              .toList();

  SoundTestFixture(Path directory) {
    this.directory = directory;
    store = new SqliteAuthorityStore(directory);
    management = new ManagementRepository(store);
    repository = new SoundRepository(store);
    compiler =
        new SoundCompilationService(
            new AudioDecoder() {
              public String revision() {
                return decoderRevision;
              }

              public DecodedAudio decode(String filename, String mime, byte[] content) {
                decodes++;
                return new DecodedAudio(
                    ModelValues.sha256(content),
                    decoderRevision,
                    Arrays.copyOfRange(content, 44, content.length));
              }

              public void close() {}
            },
            1,
            Duration.ofSeconds(5));
  }

  DocumentOriginal register(String id, byte[] pcm, boolean publish) {
    byte[] bytes = AudioPcm.wav(pcm, 0, pcm.length);
    var original =
        new DocumentOriginal(
            id,
            "source-" + id,
            id + ".wav",
            "audio",
            "audio/wav",
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
                  "audio",
                  "audio/wav",
                  original.revisionId(),
                  original.sourceSha256(),
                  bytes.length),
              Instant.now().toString());
          management.insertGrant(id, OWNER.principalId(), "owner");
          repository.insertOriginal(original, Instant.now().toString());
          return null;
        });
    if (publish) {
      publish(original, pcm);
    }
    return original;
  }

  SoundPublication publish(DocumentOriginal original, byte[] pcm) {
    String generation = UUID.randomUUID().toString();
    var spans = new ArrayList<SoundSpan>();
    for (int start = 0; start < pcm.length; start += 32000) {
      int end = Math.min(pcm.length, start + 32000);
      int ordinal = start / 32000;
      String spanId = SoundProfile.spanId(original.revisionId(), ordinal);
      String physical = SoundProfile.physicalSegmentId(generation, spanId);
      String pcmSha = ModelValues.sha256(Arrays.copyOfRange(pcm, start, end));
      var entry =
          new RetrievalProjection.Entry(
              physical,
              OWNER.workspaceId(),
              original.documentId(),
              generation,
              pcmSha,
              List.of(1.0, 0.0));
      spans.add(
          new SoundSpan(
              spanId,
              ordinal,
              start / 2L,
              end / 2L,
              pcmSha,
              "same recall description",
              physical,
              RetrievalProjection.entryDigest(entry)));
    }
    var publication =
        new SoundPublication(
            UUID.randomUUID().toString(),
            OWNER.workspaceId(),
            original.documentId(),
            original.revisionId(),
            original.sourceSha256(),
            original.filename(),
            original.mediaType(),
            original.sizeBytes(),
            generation,
            TARGET,
            MODEL,
            DECODER,
            1,
            pcm.length / 2L,
            spans,
            SoundProfile.manifestSha256(
                OWNER.workspaceId(), original.documentId(), generation, spans),
            PROFILE,
            Instant.now().toString());
    store.transaction(
        () -> {
          repository.insertPublication(publication);
          return null;
        });
    publications.add(publication);
    return publication;
  }

  SoundAnswerService answers() {
    SoundModels models =
        new SoundModels() {
          public String revision() {
            return modelRevision;
          }

          public Description describe(AudioWaveform waveform) {
            throw new AssertionError("Answer must not describe or use ASR");
          }

          public Draft draft(String question, AudioWaveform waveform) {
            store.transaction(() -> null);
            questions.add(question);
            drafts.add(waveform);
            return draft.apply(waveform);
          }

          public Verification verify(String question, AudioWaveform waveform, List<String> claims) {
            store.transaction(() -> null);
            questions.add(question);
            verifies.add(waveform);
            var value = verify.apply(waveform);
            afterVerify.run();
            return value;
          }
        };
    SoundEmbeddingModels embedding =
        new SoundEmbeddingModels() {
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
            return List.of(1.0, 0.0);
          }

          public List<Double> embedAudio(byte[] wav) {
            store.transaction(() -> null);
            queryWavs.add(wav.clone());
            return List.of(0.0, 1.0);
          }
        };
    RetrievalProjection projection =
        new RetrievalProjection() {
          public String identity() {
            return TARGET.projectionIdentity();
          }

          public void initialize() {
            throw new AssertionError("Query cannot initialize index");
          }

          public void prepareSearch() {
            store.transaction(() -> null);
          }

          public VerifiedRevision verify(RevisionManifest manifest) {
            throw new AssertionError("Query cannot verify projection writes");
          }

          public void upsert(List<Entry> entries) {
            throw new AssertionError("Query cannot write projection");
          }

          public List<Candidate> search(Query query) {
            store.transaction(() -> null);
            queries.add(query);
            return search.apply(query);
          }
        };
    return new SoundAnswerService(
        store,
        repository,
        management,
        new DocumentPermissionPolicy(),
        compiler,
        models,
        embedding,
        projection,
        TARGET,
        PROFILE,
        Duration.ofSeconds(10),
        2);
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

  static byte[] pcm(int length, int value) {
    byte[] pcm = new byte[length];
    Arrays.fill(pcm, (byte) value);
    return pcm;
  }

  public void close() {
    compiler.close();
    store.close();
  }
}
