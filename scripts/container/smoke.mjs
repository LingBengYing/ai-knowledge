import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

// Explicit engine and artifact selection. Never defaults to Docker Desktop or a workspace JAR.
const { values } = parseArgs({ options: {
  host: { type: 'string' }, config: { type: 'string' }, jar: { type: 'string' },
  'jar-sha256': { type: 'string' }, 'java-home': { type: 'string' },
  docker: { type: 'string', default: 'docker' },
} });
for (const key of ['host', 'config', 'jar', 'jar-sha256', 'java-home']) {
  assert.ok(values[key], `missing --${key}`);
}
assert.match(values.host, /^unix:\/\//, 'requires an explicitly selected local engine');
assert.match(values['jar-sha256'], /^[a-f0-9]{64}$/);
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
assert.equal(digest(readFileSync(values.jar)), values['jar-sha256'], 'unverified JAR');
const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const id = randomUUID();
const name = `java-runtime-it-${id}`;
const label = `com.evidence.rag.acceptance=${id}`;
const output = mkdtempSync(join(tmpdir(), 'java-container-acceptance-'));
const build = join(output, 'build');
const probe = join(output, 'probe');
mkdirSync(build); mkdirSync(probe);
copyFileSync(join(here, 'Dockerfile'), join(build, 'Dockerfile'));
copyFileSync(values.jar, join(build, 'application.jar'));
assert.equal(digest(readFileSync(join(build, 'application.jar'))), values['jar-sha256']);
const fixture = join(root, 'src/test/resources/corpus/星河制造差旅政策.pdf');
copyFileSync(fixture, join(probe, 'acceptance.pdf'));
const fixtureBytes = readFileSync(fixture);
const fixtureSha = digest(fixtureBytes);
const env = { ...process.env };
for (const key of ['DOCKER_CONTEXT', 'DOCKER_HOST', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH']) {
  delete env[key];
}
const command = (bin, args, timeout = 30000) => execFileSync(bin, args, {
  encoding: 'utf8', env, timeout, maxBuffer: 2 * 1024 * 1024,
});
const docker = (...args) => command(values.docker,
  ['--config', values.config, '--host', values.host, ...args]);
const inspection = () => JSON.parse(docker('inspect', name))[0];
const report = {
  started_at: new Date().toISOString(), source_commit: command('git', ['-C', root, 'rev-parse', 'HEAD']).trim(),
  jar_sha256: values['jar-sha256'], fixture_sha256: fixtureSha,
  script_sha256: digest(readFileSync(fileURLToPath(import.meta.url))),
  probe_sha256: digest(readFileSync(join(here, 'HttpProbe.java'))),
  dockerfile_sha256: digest(readFileSync(join(here, 'Dockerfile'))),
  resource_name: name, checks: [], status: 'running',
};
let created = false;
const check = (name, body) => {
  body(); report.checks.push(name); console.log(`PASS ${name}`);
};
const request = (method, path, actor = 'container-uploader', file = '-', origin = '-') => {
  const raw = docker('exec', name, 'java', '-Xmx64m', '-cp', '/tmp/probe', 'HttpProbe',
    method, path, actor, file, origin);
  const lines = raw.trimEnd().split('\n');
  assert.equal(lines.length, 7, 'invalid probe envelope');
  const result = { status: Number(lines[0]), headers: lines.slice(1, 6),
    body: JSON.parse(Buffer.from(lines[6], 'base64').toString('utf8')) };
  assert.equal(result.headers[0], 'private, no-store');
  assert.equal(result.headers[1], 'nosniff');
  assert.match(result.headers[2], /^[a-f0-9-]{36}$/i);
  assert.equal(result.headers[3], 'no-referrer');
  assert.ok(result.headers[4].includes("frame-ancestors 'none'"));
  return result;
};
const expectStatus = (result, status, code) => {
  assert.equal(result.status, status);
  if (code) assert.equal(result.body.error_code, code);
  return result.body;
};
const installProbe = () => docker('cp', probe, `${name}:/tmp/probe`);
const ready = async () => {
  const until = Date.now() + 45000;
  while (Date.now() < until) {
    assert.equal(inspection().State.Running, true, 'application stopped during startup');
    try {
      const response = request('GET', '/health/live', '-');
      if (response.status === 200) return;
    } catch { /* Local liveness only; no upload or model request is retried. */ }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error('container_startup_timeout');
};
const stop = () => {
  assert.equal(inspection().Config.Labels['com.evidence.rag.acceptance'], id);
  docker('stop', '--time', '15', name);
  const state = inspection().State;
  assert.equal(state.Running, false); assert.equal(state.OOMKilled, false);
  assert.ok([0, 143].includes(state.ExitCode), `unexpected exit ${state.ExitCode}`);
  return state.ExitCode;
};
try {
  command(join(values['java-home'], 'bin/javac'), ['--release', '21', '-d', probe, join(here, 'HttpProbe.java')]);
  assert.throws(() => command(join(values['java-home'], 'bin/java'), ['-cp', probe, 'HttpProbe']),
    'probe must reject missing arguments');
  assert.throws(() => expectStatus({ status: 500 }, 200), 'status assertion must reject failure');
  report.checks.push('probe_negative_controls');
  report.engine = JSON.parse(docker('info', '--format', '{{json .}}'));
  report.engine = { os: report.engine.OSType, architecture: report.engine.Architecture,
    cpus: report.engine.NCPU, memory: report.engine.MemTotal };
  assert.equal(report.engine.os, 'linux');
  command(values.docker, ['--config', values.config, '--host', values.host,
    'build', '--network=none', '--pull=false', '--label', label, '-t', name, build], 60000);
  const image = JSON.parse(docker('image', 'inspect', name))[0];
  report.image = { id: image.Id, architecture: image.Architecture, os: image.Os };
  docker('volume', 'create', '--label', label, name);
  docker('create', '--name', name, '--label', label, '--network=none', '--read-only',
    '--cap-drop=ALL', '--security-opt=no-new-privileges:true', '--memory=1536m',
    '--memory-swap=1536m', '--cpus=2', '--pids-limit=192',
    '--tmpfs', '/tmp:rw,nosuid,nodev,size=256m,mode=1777',
    '--mount', `type=volume,source=${name},target=/data`,
    '-e', 'RAG_AUTH_MODE=development_headers', '-e', 'RAG_WORKSPACE_ID=org-main',
    '-e', 'RAG_BIND_ADDRESS=127.0.0.1', '-e', 'RAG_PORT=18084', '-e', 'RAG_ENVIRONMENT=test',
    '-e', 'RAG_INGESTION_ENABLED=true', '-e', 'RAG_INDEXING_ENABLED=false',
    '-e', 'RAG_ANSWERS_ENABLED=false', '-e', 'RAG_DOCUMENT_REMOVAL_ENABLED=false', name);
  created = true;
  const config = inspection();
  report.controls = { user: config.Config.User, network: config.HostConfig.NetworkMode,
    read_only: config.HostConfig.ReadonlyRootfs, memory: config.HostConfig.Memory,
    memory_swap: config.HostConfig.MemorySwap, nano_cpus: config.HostConfig.NanoCpus,
    pids: config.HostConfig.PidsLimit, capabilities_dropped: config.HostConfig.CapDrop,
    security_options: config.HostConfig.SecurityOpt, tmpfs: config.HostConfig.Tmpfs,
    published_ports: config.HostConfig.PortBindings };
  check('container_controls', () => {
    assert.equal(report.controls.user, '10001:10001');
    assert.equal(report.controls.network, 'none'); assert.equal(report.controls.read_only, true);
    assert.deepEqual(report.controls.capabilities_dropped, ['ALL']);
    assert.equal(Object.keys(report.controls.published_ports ?? {}).length, 0);
    assert.equal(config.Mounts.length, 1); assert.equal(config.Mounts[0].Name, name);
  });
  docker('start', name); installProbe(); await ready();
  report.jre = docker('exec', name, 'java', '--version').trim();
  check('runtime_identity_and_immutable_jar', () => {
    assert.match(report.jre, /openjdk 21\./);
    assert.equal(docker('exec', name, 'id', '-u').trim(), '10001');
    assert.equal(docker('exec', name, 'sha256sum', '/app/application.jar').split(/\s/)[0], values['jar-sha256']);
  });
  check('health_and_closed_capabilities', () => {
    expectStatus(request('GET', '/health/ready', '-'), 503, 'migration_incomplete');
    const runtime = expectStatus(request('GET', '/v1/config', '-'), 200);
    assert.equal(runtime.migration_stage, 'text_ingestion');
    for (const capability of ['answers', 'sources', 'text_index', 'indexings', 'document_removal']) {
      assert.ok(!runtime.capabilities.includes(capability));
    }
  });
  check('empty_store_and_identity_origin_rejection', () => {
    assert.equal(expectStatus(request('GET', '/v1/management/documents'), 200).total, 0);
    expectStatus(request('GET', '/v1/management/documents', '-'), 422, 'invalid_identity');
    expectStatus(request('POST', '/v1/documents?filename=acceptance.pdf', 'container-uploader',
      '/tmp/probe/acceptance.pdf', 'https://untrusted.example'), 403, 'cross_origin_denied');
    assert.equal(expectStatus(request('GET', '/v1/management/documents'), 200).total, 0);
  });
  const upload = expectStatus(request('POST', '/v1/documents?filename=acceptance.pdf',
    'container-uploader', '/tmp/probe/acceptance.pdf'), 202);
  assert.ok(upload.task_id && upload.document_id && upload.revision_id);
  assert.equal(upload.attempt, 1);
  let task;
  const until = Date.now() + 45000;
  do {
    task = expectStatus(request('GET', `/v1/ingestions/${upload.task_id}`), 200);
    if (task.state === 'parsed') break;
    assert.ok(['queued', 'processing'].includes(task.state), `unexpected ingestion state ${task.state}`);
    await new Promise((resolve) => setTimeout(resolve, 250));
  } while (Date.now() < until);
  const documents = () => expectStatus(request('GET', '/v1/management/documents?status=parsed'), 200);
  const list = documents();
  const row = list.items.find((row) => row.document_id === upload.document_id);
  check('real_pdf_ingestion_and_parsed_not_indexed', () => {
    assert.equal(task.state, 'parsed'); assert.equal(task.status, 'parsed');
    assert.equal(task.can_retry, false); assert.equal(task.can_cancel, false);
    assert.equal(list.total, 1); assert.ok(row); assert.equal(row.synthetic_fixture, false);
    assert.ok(row.segment_count > 0); assert.equal(row.active_revision_id, null);
    assert.equal(row.index_status, 'not_indexed'); assert.equal(row.latest_index_job, null);
    for (const name of ['can_answer', 'can_delete', 'can_reindex']) assert.equal(row[name], false);
    assert.equal(row.media_info.mime_type, 'application/pdf');
  });
  check('unauthorized_document_and_task_hidden', () => {
    assert.equal(expectStatus(request('GET', '/v1/management/documents', 'container-stranger'), 200).total, 0);
    expectStatus(request('GET', `/v1/ingestions/${upload.task_id}`, 'container-stranger'), 404, 'not_found');
    expectStatus(request('POST', `/v1/ingestions/${upload.task_id}/retry`), 409, 'ingestion_state_conflict');
  });
  report.document = { id: upload.document_id, revision_id: upload.revision_id,
    task_id: upload.task_id, segment_count: row.segment_count, fixture_bytes: fixtureBytes.length };
  report.first_exit_code = stop();
  docker('start', name); installProbe(); await ready();
  check('same_volume_restart_preserves_evidence_and_acl', () => {
    const reopenedTask = expectStatus(request('GET', `/v1/ingestions/${upload.task_id}`), 200);
    assert.equal(reopenedTask.revision_id, upload.revision_id);
    assert.equal(reopenedTask.state, 'parsed'); assert.equal(reopenedTask.attempt, 1);
    const reopened = documents();
    assert.equal(reopened.total, 1); assert.deepEqual(reopened.items, list.items);
    expectStatus(request('GET', `/v1/ingestions/${upload.task_id}`, 'container-stranger'), 404, 'not_found');
  });
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.failure = error.code ?? error.name ?? 'acceptance_failed';
  console.error(error.message);
  process.exitCode = 1;
} finally {
  if (created) {
    try { report.final_exit_code = stop(); } catch {
      report.status = 'failed'; report.shutdown_failed = true; process.exitCode = 1;
    }
  }
  report.finished_at = new Date().toISOString();
  writeFileSync(join(output, 'report.json'), `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 });
  console.log(JSON.stringify({ status: report.status, evidence: output, resource_name: name,
    retained: 'stopped container, synthetic data volume and image; no resources deleted' }));
}
