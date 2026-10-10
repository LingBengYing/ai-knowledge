package com.evidence.rag.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.DeterministicProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.AnswerService;
import com.evidence.rag.service.EvidenceService;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.IntStream;

/** Real authority/publication fixture; remote stand-ins deliberately do not claim model quality. */
public final class AnswerTestContext implements AutoCloseable {
  public final Actor owner = new Actor("org-main", "owner");
  public final RecordingModels models = new RecordingModels();
  public final RecordingProjection projection = new RecordingProjection();
  public final IndexTarget target =
      new IndexTarget("embedding-v1", projection.identity(), models.revision(), 2);
  public final AuthorityTestContext authority;
  public final EvidenceService evidence;
  public final AnswerService answers;
  private final Path directory;

  public AnswerTestContext(Path directory, Duration timeout, int concurrency) {
    this.directory = directory;
    authority = new AuthorityTestContext(directory);
    evidence =
        new EvidenceService(
            authority.store(),
            new EvidenceRepository(authority.store()),
            new ManagementRepository(authority.store()),
            new DocumentPermissionPolicy());
    answers = new AnswerService(evidence, models, projection, target, timeout, concurrency);
  }

  public String publish(String filename, String content) {
    return publish(filename, "text/plain", content.getBytes(StandardCharsets.UTF_8));
  }

  public String publish(String filename, String mime, byte[] content) {
    return publish(owner, filename, mime, content);
  }

  public String publish(Actor actor, String filename, String mime, byte[] content) {
    authority.uploadDocument(actor, filename, mime, content);
    var ingestion = authority.claimIngestion(actor.workspaceId()).orElseThrow();
    assertTrue(
        authority.completeIngestion(ingestion, new TextParser().parse(filename, mime, content)));
    authority.createIndexing(actor, ingestion.documentId(), target);
    var claim = authority.claimIndexing(actor.workspaceId()).orElseThrow();
    var data =
        actor.workspaceId().equals(owner.workspaceId())
            ? projection.data
            : new DeterministicProjection(actor.workspaceId(), 2);
    data.initialize();
    var entries =
        claim.items().stream()
            .map(
                segment ->
                    new RetrievalProjection.Entry(
                        RetrievalProjection.physicalSegmentId(
                            claim.projectionGenerationId(), segment.evidenceId()),
                        actor.workspaceId(),
                        claim.documentId(),
                        claim.projectionGenerationId(),
                        segment.recallText(),
                        List.of(1.0, 0.0)))
            .toList();
    var digests = new TreeMap<String, String>();
    for (int offset = 0; offset < entries.size(); offset += RetrievalProjection.MAX_BATCH) {
      data.upsert(
          entries.subList(
              offset, Math.min(offset + RetrievalProjection.MAX_BATCH, entries.size())));
    }
    entries.forEach(
        entry -> digests.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            actor.workspaceId(), claim.documentId(), claim.projectionGenerationId(), digests);
    assertTrue(authority.completeIndexing(claim, digests, data.verify(manifest)));
    return claim.documentId();
  }

  public void revoke(String documentId) {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement =
            connection.prepareStatement(
                "DELETE FROM document_acl WHERE document_id=? AND principal_id=?")) {
      statement.setString(1, documentId);
      statement.setString(2, owner.principalId());
      statement.executeUpdate();
    } catch (Exception error) {
      throw new AssertionError("Private ACL fixture failed", error);
    }
  }

  public long scalar(String sql) {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    } catch (Exception error) {
      throw new AssertionError("Private read-only SQL fixture failed", error);
    }
  }

  @Override
  public void close() {
    answers.close();
    authority.close();
  }

  public static final class RecordingModels implements TextModels {
    public final List<String> calls = new CopyOnWriteArrayList<>();
    public String modelRevision = "test-answer-model-v1";
    public Function<List<String>, List<List<Double>>> embedding =
        values -> values.stream().map(value -> List.of(1.0, 0.0)).toList();
    public Runnable onEmbed = () -> {};
    public Runnable onRerank = () -> {};
    public Runnable onExtract = () -> {};
    public Runnable onRevision = () -> {};
    public Function<List<Evidence>, Extraction> extraction =
        values ->
            new Extraction(
                values.stream().map(value -> new Quote(value.id(), value.text())).toList(), false);
    public Function<List<String>, List<Ranked>> ranking =
        values ->
            IntStream.range(0, values.size())
                .mapToObj(index -> new Ranked(index, 100.0 - index))
                .toList();
    public List<Evidence> lastEvidence = List.of();
    public Function<List<AgentMessage>, String> agent =
        messages -> {
          throw new Failure("model_agent_unavailable");
        };

    @Override
    public String agentChat(List<AgentMessage> messages) {
      calls.add("agent");
      return agent.apply(messages);
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
      calls.add("embed");
      onEmbed.run();
      return embedding.apply(texts);
    }

    @Override
    public List<Ranked> rerank(String question, List<String> texts) {
      calls.add("rerank");
      onRerank.run();
      return ranking.apply(texts);
    }

    @Override
    public Extraction extract(String question, List<Evidence> values) {
      calls.add("extract");
      lastEvidence = List.copyOf(values);
      onExtract.run();
      return extraction.apply(values);
    }

    @Override
    public String revision() {
      String current = modelRevision;
      onRevision.run();
      return current;
    }
  }

  public static final class RecordingProjection implements RetrievalProjection {
    public final DeterministicProjection data = new DeterministicProjection("org-main", 2);
    public final List<String> calls = new CopyOnWriteArrayList<>();
    public Function<List<Candidate>, List<Candidate>> results = ArrayList::new;
    public Runnable onPrepare = () -> {};
    public Runnable onSearch = () -> {};
    public AuthorizedScope lastScope;

    @Override
    public String identity() {
      return data.identity();
    }

    @Override
    public VerifiedRevision verify(RevisionManifest manifest) {
      throw new AssertionError("Answers must not verify/write index publications");
    }

    @Override
    public void initialize() {
      throw new AssertionError("Answers must not initialize remote resources");
    }

    @Override
    public void prepareSearch() {
      calls.add("prepare");
      onPrepare.run();
    }

    @Override
    public void upsert(List<Entry> entries) {
      throw new AssertionError("Answers must not write remote evidence");
    }

    @Override
    public List<Candidate> search(Query query) {
      calls.add("search");
      lastScope = query.scope();
      onSearch.run();
      return results.apply(data.search(query));
    }
  }
}
