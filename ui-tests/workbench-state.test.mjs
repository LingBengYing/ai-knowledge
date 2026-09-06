import test from 'node:test';
import assert from 'node:assert/strict';
import { WorkbenchState, batchFeedback, parseTags } from '../src/main/resources/static/workbench-state.mjs';

const documents = [{ document_id: 'one', display_name: '资料一' }, { document_id: 'two', display_name: '资料二' }];
function populated() {
  const state = new WorkbenchState();
  state.commitPage(state.beginRead('documents'), documents);
  return state;
}

test('selection is limited to current page and detail comes from the real row', () => {
  const state = populated();
  state.select('outside', true);
  assert.deepEqual([...state.selected], []);
  state.selectPage(true);
  assert.deepEqual([...state.selected], ['one', 'two']);
  assert.equal(state.openDetail('one'), documents[0]);
  assert.equal(state.openDetail('outside'), null);
});

test('filter, page or identity change immediately clears data and invalidates old reads', () => {
  const state = populated();
  state.selectPage(true);
  state.openDetail('one');
  const old = state.beginRead('documents');
  state.invalidate();
  assert.deepEqual(state.items, []);
  assert.equal(state.detail, null);
  assert.equal(state.selected.size, 0);
  assert.equal(state.commitPage(old, documents), false);
});

test('latest request wins without letting an older response repopulate the page', () => {
  const state = new WorkbenchState();
  const old = state.beginRead('documents');
  const latest = state.beginRead('documents');
  assert.equal(state.commitPage(latest, [documents[1]]), true);
  assert.equal(state.commitPage(old, [documents[0]]), false);
  assert.deepEqual(state.items, [documents[1]]);
});

test('mutations cannot double submit and old completion cannot unlock a new identity', () => {
  const state = new WorkbenchState();
  const old = state.beginMutation();
  assert.equal(state.beginMutation(), null);
  state.invalidate();
  const current = state.beginMutation();
  assert.ok(current);
  assert.equal(state.finishMutation(old), false);
  assert.equal(state.mutating, true);
  assert.equal(state.finishMutation(current), true);
  assert.equal(state.mutating, false);
});

test('pending detail save cannot dismiss its error target, then unlocks after failure', () => {
  const state = populated();
  state.openDetail('one');
  const request = state.beginMutation();
  assert.equal(state.closeDetail(), false);
  assert.equal(state.detail, documents[0]);
  assert.equal(state.finishMutation(request), true);
  assert.equal(state.closeDetail(), true);
  assert.equal(state.detail, null);
});

test('cookie-changing identity requests stay serialized even when page context is cleared', () => {
  const state = new WorkbenchState();
  assert.equal(state.beginIdentityChange(), true);
  state.invalidate();
  assert.equal(state.beginIdentityChange(), false);
  state.finishIdentityChange();
  assert.equal(state.beginIdentityChange(), true);
});

test('partial batch preserves each failure and treats missing results as failed', () => {
  const feedback = batchFeedback(['one', 'two', 'three'], [
    { document_id: 'one', ok: true },
    { document_id: 'two', ok: false, error_code: 'not_found', detail: '当前身份不可操作。' },
  ]);
  assert.equal(feedback.succeeded, 1);
  assert.equal(feedback.failed, 2);
  assert.equal(feedback.items[1].detail, '当前身份不可操作。');
  assert.equal(feedback.items[2].ok, false);
  assert.equal(feedback.items[2].error_code, 'missing_result');
});

test('tag input is deduplicated while an empty detail edit can clear tags', () => {
  assert.deepEqual(parseTags('制度， 财务,制度\n重要'), ['制度', '财务', '重要']);
  assert.deepEqual(parseTags('  '), []);
});
