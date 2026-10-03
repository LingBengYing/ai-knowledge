package com.evidence.rag.tool.synopsis;

import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.TagSuggestions;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;

/** Deterministic whole-entry metadata derivation; the caller authorizes the current synopsis. */
public final class TagSuggestionCompiler {
  private TagSuggestionCompiler() {}

  public static TagSuggestions compile(String synopsisId, FileSynopsis synopsis) {
    ModelValues.identifier(synopsisId, 128);
    if (synopsis == null || synopsis.unavailableReason() != null) {
      throw ModelValues.invalid();
    }
    var tags = new LinkedHashSet<String>();
    for (var section : List.of(SynopsisDraft.Section.TERM, SynopsisDraft.Section.TOPIC)) {
      for (var entry : synopsis.entries()) {
        if (entry.item().section() != section) {
          continue;
        }
        String text = entry.item().text().strip();
        if (eligible(text)) {
          tags.add(ModelValues.label(text, 40));
          if (tags.size() == 8) {
            break;
          }
        }
      }
      if (tags.size() == 8) {
        break;
      }
    }
    var candidates = new ArrayList<TagSuggestions.Candidate>();
    for (String tag : tags) {
      candidates.add(new TagSuggestions.Candidate(candidates.size() + 1, tag));
    }
    return new TagSuggestions(
        synopsisId,
        synopsis.publication(),
        synopsis.inputFingerprint(),
        synopsis.modelRevision(),
        synopsis.policyRevision(),
        fingerprint(synopsisId, synopsis, candidates),
        candidates);
  }

  private static boolean eligible(String text) {
    return !text.isEmpty()
        && text.codePointCount(0, text.length()) <= 40
        && text.codePoints()
            .noneMatch(
                c ->
                    Character.isISOControl(c)
                        || (c >= 0xD800 && c <= 0xDFFF)
                        || c == ','
                        || c == '，'
                        || c == ';'
                        || c == '；');
  }

  private static String fingerprint(
      String synopsisId, FileSynopsis synopsis, List<TagSuggestions.Candidate> candidates) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      field(digest, TagSuggestions.POLICY_REVISION);
      field(digest, synopsisId);
      PublicationVersion publication = synopsis.publication();
      field(digest, publication.documentId());
      field(digest, publication.publicationId());
      field(digest, publication.sourceRevisionId());
      field(digest, publication.projectionGenerationId());
      field(digest, publication.sourceSha256());
      field(digest, publication.parserRevision());
      field(digest, publication.target().embeddingIdentity());
      field(digest, publication.target().projectionIdentity());
      field(digest, publication.target().modelRevision());
      field(digest, Integer.toString(publication.target().dimensions()));
      field(digest, publication.manifestSha256());
      field(digest, Integer.toString(publication.segmentCount()));
      field(digest, synopsis.inputFingerprint());
      field(digest, synopsis.modelRevision());
      field(digest, synopsis.policyRevision());
      field(digest, Integer.toString(candidates.size()));
      for (var candidate : candidates) {
        field(digest, Integer.toString(candidate.ordinal()));
        field(digest, candidate.tag());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void field(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
