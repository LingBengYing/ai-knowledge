package com.evidence.rag.service;

import com.evidence.rag.client.model.AudioEmbeddingModels;
import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.AudioVectorEntry;
import com.evidence.rag.model.domain.AudioVectorPublication;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.AudioDecoder;
import com.evidence.rag.worker.parser.ImageOcr;
import com.evidence.rag.worker.parser.VideoDecoder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;

/** Synthetic same-pass PCM/ASR fixtures; no native or provider-quality claim. */
final class AudioVectorQueryFixture {
  static final String DECODER = "test-audio-decoder-v1";
  static final String FACT = "星港项目的预算为47万元。";
  static final String QUESTION = "星港项目的预算是多少？";
  static final Duration BUDGET = Duration.ofSeconds(10);
  static final IndexTarget TARGET =
      new IndexTarget("audio-profile-v1", "d".repeat(64), "audio-profile-v1", 2);
  final List<byte[]> asr = new ArrayList<>();
  int decodes;
  boolean longText;

  QueryAttachment attachment() {
    byte[] pcm = new byte[64_002];
    Arrays.fill(pcm, (byte) 2);
    pcm[64_000] = 7;
    return new QueryAttachment("reference.wav", "audio/wav", AudioPcm.wav(pcm, 0, pcm.length));
  }

  QueryPreparationService preparation(boolean retain) {
    AudioModels models =
        new AudioModels() {
          public String revision() {
            return "query-asr-v1";
          }

          public Transcript transcribe(byte[] wav) {
            asr.add(wav.clone());
            return new Transcript(longText ? "字".repeat(4090) : asr.size() % 3 == 2 ? "" : FACT);
          }

          public void close() {}
        };
    AudioDecoder decoder =
        new AudioDecoder() {
          public String revision() {
            return DECODER;
          }

          public DecodedAudio decode(String filename, String mediaType, byte[] source) {
            decodes++;
            return new DecodedAudio(
                ModelValues.sha256(source),
                revision(),
                Arrays.copyOfRange(source, 44, source.length));
          }

          public void close() {}
        };
    VideoDecoder videos =
        new VideoDecoder() {
          public String revision() {
            return "unused-video-v1";
          }

          public DecodedVideo decode(String filename, String mediaType, byte[] source) {
            throw new AssertionError("No query video");
          }

          public void close() {}
        };
    ImageOcr ocr =
        new ImageOcr() {
          public String revision() {
            return "unused-ocr-v1";
          }

          public Optional<ParsedImage> read(VisualImage image) {
            throw new AssertionError("No query image");
          }
        };
    return new QueryPreparationService(
        vision(),
        ocr,
        new AudioCompilationService(decoder, models, 1, BUDGET),
        new VideoCompilationService(
            videos, new AudioTranscriptionService(models, 1, BUDGET), vision(), BUDGET),
        BUDGET,
        retain);
  }

  QueryAttachmentService queries(
      AnswerTestContext context, AudioEmbeddingModels models, RetrievalProjection projection) {
    QueryRankingModels ranking =
        new QueryRankingModels() {
          public String revision() {
            return "query-ranking-v1";
          }

          public List<TextModels.Ranked> rank(
              PreparedQuery query, List<QueryRankCandidate> candidates) {
            throw new AssertionError("No image ranking");
          }
        };
    return new QueryAttachmentService(
        preparation(true),
        ranking,
        context.models,
        context.projection,
        context.target,
        null,
        null,
        null,
        models,
        projection,
        TARGET);
  }

  static AudioVectorPublication publishVectors(
      AnswerTestContext context, EvidenceScope scope, AudioTestFixture.Published published) {
    var sources = context.evidence.hydrateAudio(scope, published.physicalIds());
    var base = sources.getFirst().publication();
    String generation = UUID.randomUUID().toString();
    var entries = new ArrayList<AudioVectorEntry>();
    var digests = new TreeMap<String, String>();
    for (var source : sources) {
      var span = source.span();
      String pcmSha = ModelValues.sha256(new byte[(int) ((span.endMs() - span.startMs()) * 32)]);
      String vectorId = RetrievalProjection.physicalSegmentId(generation, span.id());
      String digest =
          RetrievalProjection.entryDigest(
              new RetrievalProjection.Entry(
                  vectorId,
                  context.owner.workspaceId(),
                  base.documentId(),
                  generation,
                  pcmSha,
                  List.of(1.0, 0.0)));
      entries.add(
          new AudioVectorEntry(
              span.id(),
              source.physicalSegmentId(),
              vectorId,
              span.ordinal(),
              span.startMs() * 16,
              span.endMs() * 16,
              pcmSha,
              digest));
      digests.put(vectorId, digest);
    }
    var result =
        new AudioVectorPublication(
            UUID.randomUUID().toString(),
            base,
            TARGET,
            generation,
            DECODER,
            entries,
            new RetrievalProjection.RevisionManifest(
                    context.owner.workspaceId(), base.documentId(), generation, digests)
                .sha256(),
            Instant.now().toString());
    context
        .authority
        .store()
        .transaction(
            () -> {
              new AudioVectorRepository(context.authority.store()).insert(result);
              return null;
            });
    return result;
  }

  private static VisionModels vision() {
    return new VisionModels() {
      public String revision() {
        return "unused-vision-v1";
      }

      public Description describe(VisualImage image) {
        throw new AssertionError("No image description");
      }

      public Draft draft(String question, VisualImage image) {
        throw new AssertionError("No image proof");
      }

      public Verification verify(String question, VisualImage image, List<String> claims) {
        throw new AssertionError("No image proof");
      }
    };
  }

  static final class Embeddings implements AudioEmbeddingModels {
    final List<byte[]> received = new ArrayList<>();
    String revision = TARGET.modelRevision();
    String decoder = DECODER;
    int dimensions = 2;
    List<Double> result = List.of(1.0, 0.0);
    Runnable after = () -> {};

    public String revision() {
      return revision;
    }

    public String decoderRevision() {
      return decoder;
    }

    public int dimensions() {
      return dimensions;
    }

    public List<Double> embed(byte[] wav) {
      received.add(wav.clone());
      after.run();
      return result;
    }
  }

  static final class Projection implements RetrievalProjection {
    String identity = TARGET.projectionIdentity();
    final List<Query> queries = new ArrayList<>();
    int preparations;
    Function<Query, List<Candidate>> response =
        query ->
            IntStream.range(0, 64)
                .mapToObj(index -> new Candidate("vector-" + index, 1.0 - index / 100.0))
                .toList();

    public String identity() {
      return identity;
    }

    public void initialize() {
      throw new AssertionError("Query never initializes");
    }

    public void prepareSearch() {
      preparations++;
    }

    public void upsert(List<Entry> entries) {
      throw new AssertionError("Query never writes");
    }

    public VerifiedRevision verify(RevisionManifest manifest) {
      throw new AssertionError("Query never builds");
    }

    public List<Candidate> search(Query query) {
      queries.add(query);
      return response.apply(query);
    }

    public void close() {}
  }
}
