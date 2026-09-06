import test from 'node:test';
import assert from 'node:assert/strict';
import { createApi, ApiError, validateUpload } from '../src/main/resources/static/api.mjs';
import { WorkbenchState, checkedTask, taskPending, taskLabel } from '../src/main/resources/static/workbench-state.mjs';

const task = (change = {}) => ({ task_id: 'task-one', document_id: 'doc-one', revision_id: 'rev-one', state: 'queued', attempt: 1, error_code: null, can_cancel: true, can_retry: false, ...change });

test('raw upload keeps original File bytes, filename and same-origin identity; no JSON serialization', async () => {
  const file = new File(['中文原文件'], '政策 2026.md', { type: 'text/markdown' });
  let seen;
  const api = createApi({ auth_mode: 'development_headers', workspace_id: 'org-main' }, () => 'owner', async (path, options) => {
    seen = { path, options };
    return { status: 202, ok: true, json: async () => task() };
  });
  const path = `/v1/documents?filename=${encodeURIComponent(file.name)}`;
  assert.equal(validateUpload(file), file);
  assert.deepEqual(await api(path, { method: 'POST', file }), task());
  assert.equal(seen.path, path);
  assert.equal(seen.options.body, file);
  assert.equal(seen.options.headers['Content-Type'], 'application/octet-stream');
  assert.equal(seen.options.headers['X-Principal-Id'], 'owner');
  assert.equal(seen.options.credentials, 'same-origin');
  assert.equal(await seen.options.body.text(), '中文原文件');
});

test('file validation rejects unsupported, empty, oversized or unsafe names before network', async () => {
  let calls = 0;
  const api = createApi({}, () => '', () => { calls++; });
  for (const file of [new File([], 'empty.txt'), new File(['x'], 'video.mp4'), new File(['x'], '../a.md'), new File(['x'], 'a\\b.txt'), new File(['x'], 'a\n.txt'), new File([new Uint8Array(20 * 1024 * 1024 + 1)], 'large.pdf'), {}, null]) {
    assert.throws(() => validateUpload(file), error => error instanceof ApiError);
  }
  const file = new File(['text'], 'safe.txt');
  for (const [path, options] of [['/v1/session', { method: 'POST', file }], ['/v1/documents?filename=other.txt', { method: 'POST', file }], ['/v1/documents?filename=safe.txt', { method: 'POST', file, body: {} }], ['/v1/documents?filename=safe.txt', { method: 'GET', file }]]) {
    await assert.rejects(api(path, options));
  }
  assert.equal(calls, 0);
});

test('task action sends no JSON body or unnecessary content-type', async () => {
  let seen;
  const api = createApi({ auth_mode: 'jwt' }, () => '', async (_path, options) => {
    seen = options;
    return { status: 200, ok: true, json: async () => task({ state: 'cancelled', can_cancel: false, can_retry: true }) };
  });
  await api('/v1/ingestions/task-one/cancel', { method: 'POST' });
  assert.equal(seen.body, undefined);
  assert.equal(seen.headers['Content-Type'], undefined);
});

test('checked task exposes safe finite fields only and refuses malformed capabilities', () => {
  const output = checkedTask({ ...task(), private_text: 'must not be copied' });
  assert.deepEqual(output, task());
  assert.ok(Object.isFrozen(output));
  for (const value of [null, {}, task({ state: 'ready' }), task({ attempt: 4 }), task({ attempt: '1' }), task({ task_id: '../secret' }), task({ can_cancel: 'true' }), task({ state: 'parsed' }), task({ error_code: 'private text\nexception' }), task({ state: 'failed', attempt: 3, can_cancel: false, can_retry: true })]) {
    assert.throws(() => checkedTask(value));
  }
  assert.equal(taskPending(task()), true);
  assert.equal(taskPending(task({ state: 'processing' })), true);
  for (const state of ['parsed', 'failed', 'cancelled']) assert.equal(taskPending(task({ state })), false);
  assert.match(taskLabel('parsed'), /未索引/);
});

test('identity or page invalidation clears tasks and refuses all late poll results', () => {
  const state = new WorkbenchState();
  state.watchTask(task());
  const ticket = state.beginRead('ingestion');
  state.invalidate();
  assert.equal(state.task, null);
  assert.equal(state.commitTask(ticket, task({ state: 'processing' })), false);
  assert.equal(state.task, null);
});

test('newer task and newer attempt cannot be overwritten by previous requests', () => {
  const state = new WorkbenchState();
  state.watchTask(task());
  const first = state.beginRead('ingestion');
  state.watchTask(task({ task_id: 'task-two', document_id: 'doc-two' }));
  assert.equal(state.commitTask(first, task({ state: 'processing' })), false);
  state.watchTask(task({ attempt: 2 }));
  const current = state.beginRead('ingestion');
  assert.equal(state.commitTask(current, task({ state: 'processing', attempt: 1 })), false);
  assert.equal(state.task.attempt, 2);
  assert.throws(() => state.commitTask(current, task({ task_id: 'forged' })));
});

test('parsed state stops polling and updates only the matching authorized current row', () => {
  const state = new WorkbenchState();
  state.items = [{ document_id: 'doc-one', status: 'queued' }, { document_id: 'doc-other', status: 'queued' }];
  state.watchTask(task());
  const current = state.beginRead('ingestion');
  assert.equal(state.commitTask(current, task({ state: 'parsed', can_cancel: false })), true);
  assert.equal(taskPending(state.task), false);
  assert.equal(state.items[0].status, 'parsed');
  assert.equal(state.items[1].status, 'queued');
  assert.equal(state.items[0].latest_job.task_id, 'task-one');
  const next = state.beginRead('ingestion');
  assert.throws(() => state.commitTask(next, task()));
});

test('an in-flight list snapshot cannot regress the task just advanced by polling or retry', () => {
  const state = new WorkbenchState();
  const older = task();
  state.watchTask(task({ attempt: 2, state: 'parsed', can_cancel: false }));
  const page = state.beginRead('documents');
  state.commitPage(page, [{ document_id: 'doc-one', status: 'queued', latest_job: older },
    { document_id: 'doc-other', status: 'queued', latest_job: task({ document_id: 'doc-other' }) }]);
  assert.equal(state.items[0].status, 'parsed');
  assert.equal(state.items[0].latest_job.attempt, 2);
  assert.equal(state.items[1].status, 'queued');
  state.commitPage(state.beginRead('documents'), []);
  assert.deepEqual(state.items, []); // A task must never insert a document absent from an authorized page.
});

test('a newer authorized list task advances the panel and adopts current action permissions', () => {
  const state = new WorkbenchState();
  state.watchTask(task({ state: 'failed', can_cancel: false, can_retry: true }));
  const newer = task({ attempt: 2, state: 'processing', can_cancel: false });
  state.commitPage(state.beginRead('documents'), [{ document_id: 'doc-one', status: 'processing', latest_job: newer }]);
  assert.equal(state.task.attempt, 2);
  assert.equal(state.task.state, 'processing');
  assert.equal(state.task.can_cancel, false);
  assert.equal(state.task.can_retry, false);
  assert.equal(state.items[0].latest_job, state.task);
  const parsed = task({ attempt: 2, state: 'parsed', can_cancel: false });
  state.commitPage(state.beginRead('documents'), [{ document_id: 'doc-one', status: 'parsed', latest_job: parsed }]);
  assert.equal(state.task.state, 'parsed');
  assert.equal(taskPending(state.task), false);
});

test('reopening a captured detail resolves the latest authorized attempt and terminal state', () => {
  for (const latest of [task({ attempt: 2, state: 'processing', can_cancel: false }),
    task({ state: 'parsed', can_cancel: false })]) {
    const state = new WorkbenchState();
    const captured = { document_id: 'doc-one', status: 'queued', latest_job: task() };
    state.commitPage(state.beginRead('documents'), [captured]);
    state.openDetail(captured.document_id);
    state.watchTask(captured.latest_job);
    state.commitTask(state.beginRead('ingestion'), latest);
    state.watchTask(state.taskForDocument(captured.document_id));
    assert.deepEqual(state.task, latest);
    assert.equal(state.task.can_cancel, false);
    assert.deepEqual(state.detail.latest_job, state.task);
    assert.equal(captured.latest_job.state, 'queued');
  }
});

test('task reopening cannot resurrect a row outside the current authorized page or mismatched identity', () => {
  const state = new WorkbenchState();
  state.items = [{ document_id: 'doc-one', latest_job: task() }];
  assert.deepEqual(state.taskForDocument('doc-one'), task());
  state.invalidate();
  assert.throws(() => state.taskForDocument('doc-one'), /unavailable task document/);
  state.items = [{ document_id: 'doc-one', latest_job: task({ document_id: 'doc-other' }) }];
  assert.throws(() => state.taskForDocument('doc-one'), /invalid task document/);
  state.items = [{ document_id: 'doc-one' }];
  assert.throws(() => state.taskForDocument('doc-one'), /invalid task response/);
});
