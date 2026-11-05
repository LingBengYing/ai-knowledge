package com.evidence.rag.repository;

/**
 * v39: records which attempt of a batch import task was created automatically (restart recovery or
 * resuming a halted batch), so a halt still stops automatic work while an explicit user retry runs.
 * The existing three-attempt column bounds how often a task can be requeued.
 */
final class BatchRequeueSchema {
  static final int VERSION = 39;
  private final SqliteAuthorityStore store;

  BatchRequeueSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void verify() {
    verify(VERSION);
  }

  void verify(int version) {
    new DocumentBatchSchema(store).verify(version);
    if (store.count("SELECT COUNT(*) FROM pragma_table_info('batch_task_requeues')") != 5)
      throw new IllegalStateException("Unsupported batch requeue schema");
  }
}
