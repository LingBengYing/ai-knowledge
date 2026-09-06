import test from 'node:test';
import assert from 'node:assert/strict';
import { showNotice } from '../src/main/resources/static/notices.mjs';

function target(text = '') { return { textContent: text, hidden: !text }; }

for (const status of [409, 422, 500]) {
  test(`late PATCH ${status} remains visible when detail notice was removed`, async () => {
    const global = target();
    const nodes = new Map([['detail-error', target()], ['global-error', global]]);
    const failure = Promise.resolve(`保存失败（${status}），请核对后重试。`);
    nodes.delete('detail-error');
    showNotice(id => nodes.get(id), 'detail-error', await failure);
    assert.equal(global.textContent, `保存失败（${status}），请核对后重试。`);
    assert.equal(global.hidden, false);
  });
}

test('late dialog failure falls back when its target is missing or its dialog is closed', () => {
  for (const local of [null, { ...target(), closest: () => ({ open: false }) }]) {
    const global = target();
    showNotice(id => id === 'global-error' ? global : local, 'dialog-error', '目录操作未完成。');
    assert.equal(global.textContent, '目录操作未完成。');
    assert.equal(global.hidden, false);
  }
});

test('an existing visible local target does not overwrite the global notice', () => {
  const local = { ...target(), closest: () => ({ open: true }) };
  const global = target('其他请求失败。');
  showNotice(id => id === 'global-error' ? global : local, 'dialog-error', '目录非空。');
  assert.equal(local.textContent, '目录非空。');
  assert.equal(local.hidden, false);
  assert.equal(global.textContent, '其他请求失败。');
});

test('clearing a missing notice must not erase an unrelated global failure', () => {
  const global = target('保留这个错误。');
  showNotice(id => id === 'global-error' ? global : null, 'detail-error');
  assert.equal(global.textContent, '保留这个错误。');
  assert.equal(global.hidden, false);
});

test('missing local and global nodes never throw while presenting an error', () => {
  assert.doesNotThrow(() => showNotice(() => null, 'detail-error', '保存未完成。'));
});
