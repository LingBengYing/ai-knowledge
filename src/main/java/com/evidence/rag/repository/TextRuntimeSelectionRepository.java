package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.entity.TextRuntimeSelectionEntity;
import java.util.Objects;

/** The caller's SQLite transaction owns the active configuration selection. */
public final class TextRuntimeSelectionRepository {
  private final SqliteAuthorityStore store;

  public TextRuntimeSelectionRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public TextRuntimeSelectionEntity read() {
    var row = store.rows("SELECT * FROM text_runtime_selection WHERE id=1").getFirst();
    return new TextRuntimeSelectionEntity(AuthorityRows.integer(row,"initialized") == 1,
        row.get("active_version") == null ? null : AuthorityRows.number(row,"active_version"),
        AuthorityRows.text(row,"configuration_sha256"),AuthorityRows.text(row,"anchor_sha256"),
        AuthorityRows.text(row,"batch_id"),AuthorityRows.text(row,"updated_at"));
  }

  public void initialize(Long version, String configurationSha256, String anchorSha256, String now) {
    var previous = read();
    if (previous.initialized()) {
      throw ModelValues.invalid();
    }
    validate(version,configurationSha256,anchorSha256);
    store.execute("UPDATE text_runtime_selection SET initialized=1,active_version=?,configuration_sha256=?,anchor_sha256=?,updated_at=? WHERE id=1 AND initialized=0",version,configurationSha256,anchorSha256,now);
    if (store.count("SELECT changes()") != 1) {
      throw ModelValues.invalid();
    }
  }

  public void select(TextRuntimeSelectionEntity expected, long version, String configurationSha256,
      String anchorSha256, String batchId, String now) {
    if (expected == null || !expected.initialized() || !expected.equals(read())) {
      throw ModelValues.invalid();
    }
    validate(version,configurationSha256,anchorSha256);
    if (batchId != null && !store.modelRebuildAuthorized(batchId)) {
      throw ModelValues.invalid();
    }
    store.execute("UPDATE text_runtime_selection SET active_version=?,configuration_sha256=?,anchor_sha256=?,batch_id=?,updated_at=? WHERE id=1",version,configurationSha256,anchorSha256,batchId,now);
  }

  private static void validate(Long version,String config,String anchor) {
    if (version == null) {
      if (config != null || anchor != null) {
        throw ModelValues.invalid();
      }
    } else if (version < 1 || version > 9_007_199_254_740_991L || config == null
        || anchor == null || !config.matches("[a-f0-9]{64}") || !anchor.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
  }
}
