#!/usr/bin/env node
/** Explicit synthetic acceptance against a NEW isolated local integration instance. */
import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';

const [base, stateFile, mode] = process.argv.slice(2);
assert.match(base ?? '', /^http:\/\/127\.0\.0\.1:[0-9]{2,5}$/);
assert.ok(stateFile?.startsWith('/'), 'Pass an absolute acceptance record path');
assert.ok(!mode || mode === '--read-only');
const identity = { 'X-Workspace-Id': 'org-main', 'X-Principal-Id': 'owner', Origin: base };
async function request(path, method = 'GET', body, expected = 200, headers = {}) {
  const response = await fetch(base + path, {
    method, headers: { ...identity, ...(body && !(body instanceof Uint8Array) ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: body === undefined ? undefined : body instanceof Uint8Array ? body : JSON.stringify(body),
    signal: AbortSignal.timeout(15000), redirect: 'error',
  });
  const text = await response.text();
  assert.equal(response.status, expected, `${method} ${path}: HTTP ${response.status} ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}
async function awaitTask(path, field, active, terminal, timeout = 120000) {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    const result = await request(path);
    if (!active.includes(result[field])) {
      assert.equal(result[field], terminal, `unexpected terminal: ${JSON.stringify(result)}`);
      return result;
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('Local integration deadline exceeded');
}
async function verifyStored(record) {
  for (const citation of record.citations) {
    const source = await request(citation.source_url);
    assert.deepEqual(source.citation, citation);
    const original = await fetch(base + citation.content_url, { headers: identity, redirect: 'error' });
    assert.equal(original.status, 200);
    const bytes = new Uint8Array(await original.arrayBuffer());
    assert.equal(createHash('sha256').update(bytes).digest('hex'), citation.source_sha256);
  }
  const draft = await request(`/v1/wiki/drafts/${record.draft_id}`);
  assert.equal(draft.body, record.answer);
}

if (mode === '--read-only') {
  const record = JSON.parse(await readFile(stateFile, 'utf8'));
  await verifyStored(record);
  console.log(JSON.stringify({ status: 'STORED_SOURCES_AND_DRAFT_VERIFIED', citations: record.citations.length }));
} else {
  const config = await request('/v1/knowledge-agent/config');
  assert.equal(config.enabled, true);
  assert.equal(config.engine, 'db-gpt');
  const before = await request('/v1/wiki/catalog?offset=0&limit=20');
  assert.equal(before.total, 0, 'Only seed a NEW empty synthetic integration instance');
  const documents = [
    ['灯塔项目说明.txt', '青榆灯塔项目。计划启动日期：2026年11月18日。计划预算：人民币48600元。'],
    ['灯塔使用指南.txt', '青榆灯塔使用指南。打开工作台，选择项目，点击创建任务，填写任务名称后点击保存。保存成功后，任务出现在项目任务列表。'],
  ];
  const documentIds = [];
  for (const [filename, text] of documents) {
    const upload = await request(`/v1/documents?filename=${encodeURIComponent(filename)}`, 'POST', new TextEncoder().encode(text), 202, { 'Content-Type': 'application/octet-stream' });
    await awaitTask(`/v1/ingestions/${upload.task_id}`, 'state', ['queued', 'processing'], 'parsed', 30000);
    documentIds.push(upload.document_id);
    const indexing = await request(`/v1/documents/${upload.document_id}/index`, 'POST', undefined, 202);
    await awaitTask(`/v1/indexings/${indexing.task_id}`, 'state', ['queued', 'processing'], 'indexed', 30000);
  }
  const input = { question: '根据灯塔项目说明和使用指南，整理项目计划及任务创建步骤，并提出知识整理建议。', request_id: randomUUID() };
  const created = await request('/v1/knowledge-agent/runs', 'POST', input, 202);
  const repeated = await request('/v1/knowledge-agent/runs', 'POST', input, 202);
  assert.equal(repeated.id, created.id, 'Repeated request_id must not create another run');
  const run = await awaitTask(`/v1/knowledge-agent/runs/${created.id}`, 'status', ['running'], 'completed');
  assert.equal(run.result.status, 'answered');
  assert.ok(run.result.answer.includes('48600'));
  assert.ok(run.result.answer.includes('创建任务'));
  assert.equal(run.result.citations.length, 2);
  assert.deepEqual(new Set(run.result.citations.map(c => c.document_id)), new Set(documentIds));
  assert.ok(run.suggestions.length > 0);
  assert.ok(run.suggestions.every(s => s.document_ids.every(id => documentIds.includes(id))));
  assert.ok(run.events.some(e => e.type === 'searching'));
  assert.ok(run.events.some(e => e.type === 'reading'));
  assert.ok(!JSON.stringify(run).includes('Thought:'));
  assert.ok(!JSON.stringify(run).includes('callback_token'));
  const draft = await request('/v1/wiki/drafts', 'POST', { title: '灯塔项目使用指南草稿', body: run.result.answer }, 201);
  const record = { verified_at: new Date().toISOString(), run_id: run.id, answer_id: run.result.answer_id, answer: run.result.answer, citations: run.result.citations, suggestions: run.suggestions, events: run.events, draft_id: draft.id, document_ids: documentIds, provider: 'synthetic-loopback', engine: 'real-dbgpt' };
  await verifyStored(record);
  const pages = await request('/v1/wiki/pages?offset=0&limit=20');
  assert.equal(pages.total ?? pages.items?.length ?? pages.length, 0, 'Suggestions must not automatically create Wiki pages');
  const cancelled = await request('/v1/knowledge-agent/runs', 'POST', { question: '慢速取消验证', request_id: randomUUID() }, 202);
  const stopped = await request(`/v1/knowledge-agent/runs/${cancelled.id}/cancel`, 'POST', {});
  assert.equal(stopped.status, 'cancelled');
  assert.equal(stopped.result, null);
  await new Promise(resolve => setTimeout(resolve, 1200));
  const late = await request(`/v1/knowledge-agent/runs/${cancelled.id}`);
  assert.equal(late.status, 'cancelled');
  assert.equal(late.result, null);
  await writeFile(stateFile, JSON.stringify(record, null, 2) + '\n', { mode: 0o600 });
  console.log(JSON.stringify({ status: 'REAL_DBGPT_LOCAL_INTEGRATION_VERIFIED', documents: documentIds.length, citations: record.citations.length, suggestions: record.suggestions.length, events: record.events.map(e => e.type), cancellation: 'verified', cloud_requests: 0 }));
}
