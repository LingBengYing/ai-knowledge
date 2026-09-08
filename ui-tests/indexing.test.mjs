import test from 'node:test';
import assert from 'node:assert/strict';
import * as stateModule from '../src/main/resources/static/workbench-state.mjs';

const task = (change = {}) => ({ task_id: 'index-one', document_id: 'doc-one', revision_id: 'rev-one', state: 'queued', attempt: 1, error_code: null, can_cancel: true, can_retry: false, ...change });
const parsed = { task_id: 'parse-one', document_id: 'doc-one', revision_id: 'rev-one', state: 'parsed', attempt: 1, error_code: null, can_cancel: false, can_retry: false };

test('index task accepts indexed but never conflates parsing with published indexing', () => {
  const indexed = task({ state: 'indexed', can_cancel: false });
  const result = stateModule.checkedIndexTask({ ...indexed, private_text: 'not copied' });
  assert.deepEqual(result, indexed);
  assert.ok(Object.isFrozen(result));
  assert.throws(() => stateModule.checkedTask(indexed));
  assert.throws(() => stateModule.checkedIndexTask(parsed));
  for (const invalid of [task({ state: 'ready' }), task({ attempt: 4 }), task({ task_id: '../x' }), task({ can_retry: true }), task({ state: 'indexed' })]) {
    assert.throws(() => stateModule.checkedIndexTask(invalid));
  }
  assert.match(stateModule.indexTaskLabel('indexed'), /已索引/);
  assert.doesNotMatch(stateModule.indexTaskLabel('indexed'), /可问答/);
});

test('index poll updates only index state on authorized rows and cannot publish authority pointers', () => {
  const state = new stateModule.WorkbenchState();
  state.items = [{ document_id: 'doc-one', status: 'parsed', latest_job: parsed, active_revision_id: null, can_answer: false }];
  state.watchIndexTask(task());
  assert.equal(state.commitIndexTask(state.beginRead('indexing'), task({ state: 'indexed', can_cancel: false, active_revision_id: 'forged' })), true);
  assert.equal(state.items[0].index_status, 'indexed');
  assert.equal(state.items[0].status, 'parsed');
  assert.deepEqual(state.items[0].latest_job, parsed);
  assert.equal(state.items[0].active_revision_id, null);
  assert.equal(state.items[0].can_answer, false);
});

test('index identity invalidation and task replacement reject delayed responses', () => {
  const state = new stateModule.WorkbenchState();
  state.watchIndexTask(task());
  const old = state.beginRead('indexing');
  state.invalidate();
  assert.equal(state.indexTask, null);
  assert.equal(state.commitIndexTask(old, task()), false);
  state.watchIndexTask(task());
  const replaced = state.beginRead('indexing');
  state.watchIndexTask(task({ task_id: 'index-two' }));
  assert.equal(state.commitIndexTask(replaced, task()), false);
});

test('index list and panel reconcile monotonically without overwriting parse task', () => {
  const state = new stateModule.WorkbenchState();
  state.watchTask(parsed);
  state.watchIndexTask(task({ attempt: 2, state: 'indexed', can_cancel: false }));
  state.commitPage(state.beginRead('documents'), [{ document_id: 'doc-one', status: 'parsed', latest_job: parsed, latest_index_job: task(), index_status: 'queued' }]);
  assert.equal(state.items[0].latest_index_job.attempt, 2);
  assert.equal(state.items[0].index_status, 'indexed');
  assert.equal(state.items[0].status, 'parsed');
  assert.deepEqual(state.task, parsed);
  state.openDetail('doc-one');
  assert.deepEqual(state.indexTaskForDocument('doc-one'), state.indexTask);
  state.commitPage(state.beginRead('documents'), []);
  assert.throws(() => state.indexTaskForDocument('doc-one'));
  assert.equal(state.items.length, 0);
});

test('current list permissions and newer attempts supersede cached index state, never reverse', () => {
  const state = new stateModule.WorkbenchState();
  state.watchIndexTask(task({ state: 'failed', can_cancel: false, can_retry: true }));
  const newer = task({ attempt: 2, state: 'processing', can_cancel: false });
  state.commitPage(state.beginRead('documents'), [{ document_id: 'doc-one', latest_index_job: newer }]);
  assert.deepEqual(state.indexTask, newer);
  assert.equal(state.commitIndexTask(state.beginRead('indexing'), task()), false);
  assert.throws(() => state.commitIndexTask(state.beginRead('indexing'), task({ attempt: 2 })));
  assert.throws(() => state.commitIndexTask(state.beginRead('indexing'), task({ attempt: 2, task_id: 'forged' })));
});

test('parse and index read tickets cannot be used across the two task chains', () => {
  const state = new stateModule.WorkbenchState();
  state.watchTask({ ...parsed, state: 'queued', can_cancel: true });
  state.watchIndexTask(task());
  assert.equal(state.commitTask(state.beginRead('indexing'), parsed), false);
  assert.equal(state.commitIndexTask(state.beginRead('ingestion'), task({ state: 'indexed', can_cancel: false })), false);
  assert.equal(state.task.state, 'queued');
  assert.equal(state.indexTask.state, 'queued');
});

test('index creation requires explicit paired capabilities and a current indexable parsed row', () => {
  const row = { document_id: 'doc-one', status: 'parsed', can_index: true, synthetic_fixture: false, active_revision_id: null, latest_index_job: null };
  const enabled = { capabilities: ['management', 'text_index', 'indexings'] };
  assert.equal(stateModule.canStartIndexing(enabled, row), true);
  for (const config of [{}, { capabilities: ['text_index'] }, { capabilities: ['indexings'] }, { capabilities: ['text_upload', 'ingestions'] }]) assert.equal(stateModule.canStartIndexing(config, row), false);
  for (const change of [{ can_index: false }, { can_index: 'true' }, { status: 'queued' }, { synthetic_fixture: true }, { active_revision_id: 'rev-one' }, { latest_index_job: task() }]) assert.equal(stateModule.canStartIndexing(enabled, { ...row, ...change }), false);
  assert.equal(stateModule.canStartIndexing(enabled, undefined), false);
});

test('published list rows and their parsing task never claim to be unindexed or answerable', () => {
  const row = { status: 'parsed', synthetic_fixture: false, active_revision_id: 'rev-one', index_publication_id: 'publication-one', index_status: 'indexed', can_answer: false };
  assert.match(stateModule.documentStatusLabel(row), /已索引/);
  assert.doesNotMatch(stateModule.documentStatusLabel(row), /未索引|可问答/);
  assert.doesNotMatch(stateModule.taskLabel('parsed', row), /未索引|可问答/);
  assert.match(stateModule.documentStatusLabel({ status: 'parsed' }), /未索引/);
  assert.match(stateModule.documentStatusLabel({ status: 'parsed', index_status: 'processing' }), /正在索引/);
});

test('index detail reopening uses the latest current row and refuses mismatched or unavailable documents', () => {
  for (const latest of [task({ attempt: 2, state: 'processing', can_cancel: false }), task({ state: 'indexed', can_cancel: false })]) {
    const state = new stateModule.WorkbenchState();
    const captured = { document_id: 'doc-one', status: 'parsed', latest_job: parsed, latest_index_job: task() };
    state.commitPage(state.beginRead('documents'), [captured]);
    state.openDetail('doc-one'); state.watchIndexTask(captured.latest_index_job);
    state.commitIndexTask(state.beginRead('indexing'), latest);
    state.watchIndexTask(state.indexTaskForDocument(captured.document_id));
    assert.deepEqual(state.indexTask, latest);
    assert.deepEqual(state.detail.latest_index_job, latest);
    assert.equal(captured.latest_index_job.state, 'queued');
    state.commitPage(state.beginRead('documents'), []);
    assert.throws(() => state.indexTaskForDocument('doc-one'));
    state.commitIndexTask(state.beginRead('indexing'), latest);
    assert.deepEqual(state.items, []);
    state.items = [{ document_id: 'doc-one', latest_index_job: task({ document_id: 'doc-other' }) }];
    assert.throws(() => state.indexTaskForDocument('doc-one'), /invalid task document/);
  }
});

test('a list captured before index creation cannot hide a known task for the same parsed revision', () => {
  const state = new stateModule.WorkbenchState();
  const before = { document_id: 'doc-one', status: 'parsed', latest_job: parsed, can_index: true, latest_index_job: null, index_status: 'not_indexed' };
  const page = state.beginRead('documents');
  state.watchIndexTask(task());
  state.commitPage(page, [before]);
  assert.deepEqual(state.indexTaskForDocument('doc-one'), task());
  assert.equal(stateModule.canStartIndexing({ capabilities: ['text_index', 'indexings'] }, state.items[0]), false);
  const different = { ...before, latest_job: { ...parsed, revision_id: 'rev-other' } };
  state.commitPage(state.beginRead('documents'), [different]);
  assert.equal(state.items[0].latest_index_job, null);
});
