import test from 'node:test';
import assert from 'node:assert/strict';
import { createApi, ApiError } from '../src/main/resources/static/api.mjs';

test('development requests capture explicit identity and use only same-origin no-store fetch', async () => {
  let request;
  const api = createApi({ auth_mode: 'development_headers', workspace_id: 'org-main' }, () => 'owner', async (url, options) => {
    request = { url, options };
    return { ok: true, status: 200, json: async () => ({ items: [] }) };
  });
  await api('/v1/management/documents');
  assert.equal(request.options.headers['X-Workspace-Id'], 'org-main');
  assert.equal(request.options.headers['X-Principal-Id'], 'owner');
  assert.equal(request.options.credentials, 'same-origin');
  assert.equal(request.options.cache, 'no-store');
  await assert.rejects(api('https://elsewhere.example/data'));
});

test('JWT session uses cookie requests, not forged development headers or bearer persistence', async () => {
  let options;
  const api = createApi({ auth_mode: 'jwt', workspace_id: 'org-main' }, () => 'forged', async (_url, value) => {
    options = value;
    return { ok: true, status: 200, json: async () => ({ status: 'authenticated' }) };
  });
  await api('/v1/session', { method: 'POST', body: { token: 'test-token' } });
  assert.deepEqual(options.headers, { Accept: 'application/json', 'Content-Type': 'application/json' });
  assert.equal(options.body, JSON.stringify({ token: 'test-token' }));
});

test('RFC9457 conflict remains an error with its safe detail', async () => {
  const api = createApi({}, () => '', async () => ({ ok: false, status: 409, json: async () => ({ detail: '目录非空，不能删除。' }) }));
  await assert.rejects(api('/v1/management/folders/f', { method: 'DELETE' }), error => error instanceof ApiError && error.status === 409 && error.message === '目录非空，不能删除。');
});
