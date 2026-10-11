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
async function awaitAgentStream(id) {
  const response = await fetch(`${base}/v1/knowledge-agent/runs/${id}/events`, {
    headers: { ...identity, Accept: 'text/event-stream' }, signal: AbortSignal.timeout(120000), redirect: 'error',
  });
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type') ?? '', /^text\/event-stream/);
  assert.equal(response.headers.get('x-accel-buffering'), 'no');
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let buffer = '', previous = -1, terminal = null, snapshots = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
        const lines = frame.split('\n');
        const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
        if (!data) continue;
        const name = lines.find(line => line.startsWith('event:'))?.slice(6).trim();
        assert.ok(['snapshot', 'terminal'].includes(name));
        const packet = JSON.parse(data);
        assert.ok(packet.revision > previous, 'SSE revisions must increase');
        previous = packet.revision;
        assert.equal(packet.run.id, id);
        assert.equal(typeof packet.run.preview, 'string');
        snapshots++;
        if (name === 'terminal') terminal = packet.run;
      }
      if (done) break;
    }
  } finally { await reader.cancel(); }
  assert.ok(snapshots > 0);
  assert.equal(terminal?.status, 'completed');
  assert.equal(terminal.preview, '', 'Only validated final result survives');
  const durable = await request(`/v1/knowledge-agent/runs/${id}`);
  const { preview, ...streamResult } = terminal;
  assert.deepEqual(streamResult, durable, 'SSE terminal matches authoritative JSON');
  return durable;
}
function verifyExecutionDetails(run, documentIds) {
  const plans = run.events.filter(event => event.type === 'planning');
  assert.ok(plans.length > 0);
  for (const { details } of plans) {
    assert.equal(details.status, 'completed');
    assert.equal(details.reasoning, '正在核对本次问题所需的资料来源。');
    assert.equal(details.reasoning_truncated, false);
    assert.ok(details.tool_names.length > 0);
    assert.ok(details.tool_names.every(name => ['knowledge_search', 'knowledge_read', 'terminate'].includes(name)));
  }
  for (const event of run.events.filter(event => ['searching', 'reading'].includes(event.type))) {
    const { details } = event;
    assert.equal(details.status, 'completed');
    assert.ok(Number.isInteger(details.elapsed_ms) && details.elapsed_ms >= 0);
    assert.ok(details.sources.length > 0);
    assert.equal(details.result_count, details.sources.length);
    assert.equal(details.document_count, new Set(details.sources.map(source => source.document_id)).size);
    for (const source of details.sources) {
      assert.ok(documentIds.includes(source.document_id));
      assert.ok(source.source_id && source.revision_id && source.title && source.kind);
      assert.equal(source.page, 1);
      assert.equal(source.text, undefined);
      assert.equal(source.excerpt, undefined);
    }
    if (event.type === 'searching') {
      assert.equal(details.tool_name, 'knowledge_search');
      assert.ok(['灯塔项目', '灯塔使用指南'].includes(details.query));
    } else {
      assert.equal(details.tool_name, 'knowledge_read');
    }
  }
  assert.equal(new Set(run.events.map(event => event.sequence)).size, run.events.length);
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
  if (record.conversation_id) {
    const history = await request(`/v1/knowledge-conversations/${record.conversation_id}`);
    assert.equal(history.conversation.title, '灯塔项目连续讨论');
    assert.equal(history.conversation.turn_count, 2);
    assert.equal(history.conversation.active_run_id, null);
    assert.deepEqual(history.turns, record.conversation_turns);
    assert.equal(history.offset, 0);
    assert.equal(history.limit, 20);
    assert.equal(history.has_more, false);
    const firstPage = await request(`/v1/knowledge-conversations/${record.conversation_id}?offset=0&limit=1`);
    const secondPage = await request(`/v1/knowledge-conversations/${record.conversation_id}?offset=1&limit=1`);
    assert.equal(firstPage.has_more, true);
    assert.equal(secondPage.has_more, false);
    assert.deepEqual([...firstPage.turns, ...secondPage.turns], record.conversation_turns);
    assert.equal(history.conversation.compression.status, 'compressed');
    assert.equal(history.conversation.compression.covered_turn_count, 1);
    assert.ok(history.conversation.compression.summary_characters > 0);
  }
}

if (mode === '--read-only') {
  const record = JSON.parse(await readFile(stateFile, 'utf8'));
  await verifyStored(record);
  console.log(JSON.stringify({ status: 'STORED_SOURCES_CONVERSATION_AND_DRAFT_VERIFIED', citations: record.citations.length, history_turns: record.conversation_turns?.length ?? 0, cloud_requests: 0 }));
} else {
  const config = await request('/v1/knowledge-agent/config');
  assert.equal(config.enabled, true);
  assert.equal(config.engine, 'knowledge-native-agent');
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
    const until = Date.now() + 30000;
    let indexed = false;
    while (Date.now() < until) {
      const page = await request('/v1/management/documents');
      const document = page.items.find(item => item.document_id === upload.document_id);
      assert.ok(document, 'Uploaded document remains visible');
      assert.notEqual(document.auto_index?.state, 'failed', 'Automatic index submission failed');
      assert.notEqual(document.latest_index_job?.state, 'failed', 'Automatic indexing failed');
      if (document.index_status === 'indexed') { indexed = true; break; }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    assert.ok(indexed, 'Import must automatically publish the index without a manual index request');
  }
  const conversation = await request('/v1/knowledge-conversations', 'POST', {title:'灯塔讨论'}, 201);
  const spare = await request('/v1/knowledge-conversations', 'POST', {title:'待删除的空合成对话'}, 201);
  await request(`/v1/knowledge-conversations/${spare.id}`, 'DELETE', undefined, 204);
  await request(`/v1/knowledge-conversations/${spare.id}`, 'GET', undefined, 404);
  const listed = await request('/v1/knowledge-conversations');
  assert.ok(listed.items.some(item => item.id === conversation.id));
  assert.ok(!listed.items.some(item => item.id === spare.id));
  const input = { question: '根据灯塔项目说明和使用指南，整理项目计划及任务创建步骤，并提出知识整理建议。', request_id: randomUUID(), conversation_id: conversation.id };
  const created = await request('/v1/knowledge-agent/runs', 'POST', input, 202);
  const repeated = await request('/v1/knowledge-agent/runs', 'POST', input, 202);
  assert.equal(repeated.id, created.id, 'Repeated request_id must not create another run');
  const run = await awaitAgentStream(created.id);
  assert.equal(run.result.status, 'answered');
  assert.ok(run.result.answer.includes('48600'));
  assert.ok(run.result.answer.includes('创建任务'));
  assert.equal(run.result.citations.length, 2);
  assert.deepEqual(new Set(run.result.citations.map(c => c.document_id)), new Set(documentIds));
  assert.ok(run.suggestions.length > 0);
  assert.ok(run.suggestions.every(s => s.document_ids.every(id => documentIds.includes(id))));
  verifyExecutionDetails(run, documentIds);
  assert.ok(run.events.some(e => e.type === 'searching'));
  assert.ok(run.events.some(e => e.type === 'reading'));
  assert.ok(!JSON.stringify(run).includes('Thought:'));
  assert.ok(!JSON.stringify(run).includes('callback_token'));
  const firstHistory = await request(`/v1/knowledge-conversations/${conversation.id}`);
  assert.equal(firstHistory.turns.length, 1);
  assert.equal(firstHistory.turns[0].question, input.question);
  assert.equal(firstHistory.turns[0].result.result.answer, run.result.answer);
  const followup = await request('/v1/knowledge-agent/runs', 'POST', {question:'接着说明创建任务的步骤。', request_id:randomUUID(), conversation_id:conversation.id}, 202);
  const second = await awaitAgentStream(followup.id);
  assert.equal(second.result.status, 'answered');
  verifyExecutionDetails(second, documentIds);
  assert.ok(second.events.some(event => event.type === 'searching'), 'Prior answers never bypass fresh search');
  assert.ok(second.events.some(event => event.type === 'reading'), 'Prior citations never bypass fresh reading');
  const renamed = await request(`/v1/knowledge-conversations/${conversation.id}`, 'PATCH', {title:'灯塔项目连续讨论'});
  assert.equal(renamed.title, '灯塔项目连续讨论');
  const durable = await request(`/v1/knowledge-conversations/${conversation.id}`);
  assert.equal(durable.conversation.compression.status, 'compressed');
  assert.equal(durable.conversation.compression.covered_turn_count, 1);
  assert.equal(durable.turns.length, 2);
  assert.deepEqual(durable.turns[0], firstHistory.turns[0], 'Compaction must preserve every original turn');
  const draft = await request('/v1/wiki/drafts', 'POST', { title: '灯塔项目使用指南草稿', body: run.result.answer }, 201);
  const record = { verified_at: new Date().toISOString(), run_id: run.id, answer_id: run.result.answer_id, answer: run.result.answer, citations: run.result.citations, suggestions: run.suggestions, events: run.events, draft_id: draft.id, document_ids: documentIds, conversation_id:conversation.id, conversation_turns:durable.turns, provider: 'synthetic-loopback', engine: 'knowledge-native-agent' };
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
  console.log(JSON.stringify({ status: 'NATIVE_AGENT_LOCAL_INTEGRATION_VERIFIED', documents: documentIds.length, citations: record.citations.length, suggestions: record.suggestions.length, events: record.events.map(e => e.type), cancellation: 'verified', conversation_crud:'verified', history_turns:durable.turns.length, compaction:'verified', cloud_requests: 0 }));
}
