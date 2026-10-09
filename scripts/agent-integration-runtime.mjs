#!/usr/bin/env node
/** Owns only newly spawned local child processes; never discovers or kills existing services. */
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { readFile, writeFile } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const [runtime, java, python] = process.argv.slice(2);
const project = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const web = path.resolve(project, '../ai-knowledge-web');
if (![runtime, java, python].every(v => v && path.isAbsolute(v))) throw new Error('Absolute runtime paths required');
const ports = {
  web: Number(process.env.AGENT_WEB_PORT ?? 18110),
  java: Number(process.env.AGENT_JAVA_PORT ?? 18111),
  python: Number(process.env.AGENT_SERVICE_PORT ?? 18112),
};
if (new Set(Object.values(ports)).size !== 3 || Object.values(ports).some(p => !Number.isInteger(p) || p < 1024 || p > 65535)) throw new Error('Three distinct valid nonprivileged ports required');
for (const port of Object.values(ports)) await new Promise((resolve, reject) => {
  const server = net.createServer();
  server.once('error', reject);
  server.listen({host:'127.0.0.1',port,exclusive:true}, () => server.close(resolve));
});
const token = randomBytes(32).toString('hex');
const base = `http://127.0.0.1:${ports.java}`;
const logs = [];
const children = [];
let shuttingDown = false;
function start(name, command, args, options = {}) {
  const stream = createWriteStream(path.join(runtime, `${name}.log`), { mode: 0o600 });
  logs.push(stream);
  const child = spawn(command, args, { stdio: ['pipe','pipe','pipe'], ...options });
  child.stdout.pipe(stream, {end:false});
  child.stderr.pipe(stream, {end:false});
  child.once('error', error => { console.error(`${name} could not start: ${error.code ?? 'startup_failed'}`); void stop(1); });
  child.once('exit', (code, signal) => {
    if (!shuttingDown) { console.error(`${name} exited (${code ?? signal}); see ${name}.log`); void stop(1); }
  });
  children.push(child);
  return child;
}
async function stop(code = 0) {
  if (shuttingDown) return;
  shuttingDown = true;
  process.stdin.pause();
  for (const child of children.slice().reverse()) if (child.exitCode === null) child.kill('SIGTERM');
  await Promise.all(children.map(child => child.exitCode !== null ? Promise.resolve() : new Promise(resolve => {
    const deadline = setTimeout(() => { child.kill('SIGKILL'); resolve(); }, 5000);
    child.once('exit', () => { clearTimeout(deadline); resolve(); });
  })));
  for (const log of logs) log.end();
  process.exitCode = code;
}
for (const signal of ['SIGINT','SIGTERM']) process.once(signal, () => { void stop(); });
async function ready(url, timeout = 60000) {
  const until = Date.now() + timeout;
  while (Date.now() < until && !shuttingDown) {
    try { const response = await fetch(url, { signal:AbortSignal.timeout(1500), redirect:'error' }); if (response.ok) return; } catch { /* bounded startup wait */ }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error('Local startup readiness deadline exceeded');
}
try {
  start('python', python, ['-m', 'knowledge_agent'], {
    cwd: path.join(project,'agent-service'),
    env: { ...process.env, KNOWLEDGE_JAVA_ORIGIN:base, AGENT_SERVICE_TOKEN:token, AGENT_SERVICE_PORT:String(ports.python), PYTHONDONTWRITEBYTECODE:'1' },
  });
  await ready(`http://127.0.0.1:${ports.python}/health`);
  const cp = await readFile(path.join(runtime,'build/classpath.txt'),'utf8');
  const backend = start('java', java, ['-cp',cp,'com.evidence.rag.support.DbGptLocalIntegrationServer',path.join(runtime,'data'),String(ports.java),String(ports.python)], {
    cwd: project,
    env: { ...process.env, JAVA_TOOL_OPTIONS:'', JDK_JAVA_OPTIONS:'', _JAVA_OPTIONS:'', AGENT_SERVICE_TOKEN:token },
  });
  await ready(`${base}/health/live`);
  const until = Date.now() + 30000;
  while (Date.now() < until && !shuttingDown) {
    const log = await readFile(path.join(runtime,'java.log'),'utf8');
    if (log.includes('DBGPT_INTEGRATION_READY')) break;
    if (Date.now()+200 >= until) throw new Error('Local model activation did not finish');
    await new Promise(resolve => setTimeout(resolve,200));
  }
  start('frontend', process.execPath, ['scripts/wiki-workspace-server.mjs'], {
    cwd: web,
    env: { ...process.env, WIKI_WORKSPACE_PORT:String(ports.web), RAG_WEB_BACKEND_ORIGIN:base },
  });
  const page = `http://127.0.0.1:${ports.web}`;
  await ready(page);
  await writeFile(path.join(runtime,'runtime.json'),JSON.stringify({page,backend:base,agent:`http://127.0.0.1:${ports.python}`,data:path.join(runtime,'data'),model_mode:'synthetic-loopback',engine:'real-dbgpt'},null,2)+'\n',{mode:0o600});
  console.log(`READY ${page}/#/ask`);
  console.log(`Run record: ${runtime}`);
  console.log('Real DB-GPT; synthetic local models and NEW empty data. Type stats, restart, or stop.');
  let buffer = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', chunk => {
    buffer += chunk;
    let newline;
    while ((newline = buffer.indexOf('\n')) >= 0) {
      const command = buffer.slice(0,newline).trim(); buffer = buffer.slice(newline+1);
      if (command === 'stop') { void stop(); return; }
      if (['restart','stats'].includes(command) && backend.exitCode === null) backend.stdin.write(command+'\n');
      if (command === 'stats') setTimeout(async () => {
        const log = await readFile(path.join(runtime,'java.log'),'utf8');
        console.log(log.split('\n').filter(l => /^(AGENT_MODEL_CALLS|SEARCH_ACTIONS|READ_ACTIONS)=/.test(l)).slice(-3).join('\n'));
      },300);
    }
  });
  process.stdin.once('end', () => { void stop(); });
  process.stdin.resume();
} catch (error) {
  console.error(`${error.message}; logs retained in ${runtime}`);
  await stop(1);
}
