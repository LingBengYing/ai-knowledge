#!/usr/bin/env node
// Defense in depth for this repository, not a complete secret-discovery product.
import { spawnSync } from 'node:child_process';
import { readFileSync, lstatSync, openSync, closeSync, fstatSync, constants } from 'node:fs';
import { join } from 'node:path';

const provider = /\b(?:sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/g;
const aws = /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g;
const jwtShape = /(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{16,}(?![A-Za-z0-9_-])/g;
const MAX_BYTES = 32 * 1024 * 1024;
// Exactly reviewed, deliberately public JWT test fixtures. No directory-wide test exemption.
const publicTestValues = new Map([
  ['src/test/java/com/evidence/rag/security/JwtHttpTest.java#SECRET', '0123456789abcdef0123456789abcdef'],
  ['src/test/java/com/evidence/rag/security/AuthenticationModuleTest.java#SECRET', 'isolated-test-only-signing-secret-at-least-64-characters-abcdefghijk'],
  ['ui-tests/api.test.mjs#token', 'test-token'],
]);

function sensitiveName(name) {
  return /(?:secret|password|passwd|passphrase|apikey|accesskey|privatekey|token)$/.test(name.toLowerCase().replace(/[_.-]/g, ''));
}

function placeholder(value) {
  return value === '' || /^(?:<YOUR_[A-Z0-9_]+>|YOUR_[A-Z0-9_]+_HERE|REPLACE_ME|CHANGE_ME|CHANGEME|<REDACTED>|REDACTED|replace-me|change-me|replace-with-a-long-random-secret)$/.test(value);
}

function environmentReference(value) {
  if (/^\$[A-Z_][A-Z0-9_]*$/.test(value)) return true;
  const match = /^\$\{[A-Z_][A-Z0-9_]*(?::(-?)([^}]*))?\}$/.exec(value);
  return Boolean(match && (match[2] === undefined || placeholder(match[2])));
}

function forbiddenPath(path) {
  const parts = path.toLowerCase().split('/');
  const basename = parts.at(-1);
  return parts.some(part => ['target', 'node_modules', '.idea', '.vscode', '.settings', '.gradle', '.data',
    '.codex', '.claude', '.playwright', 'playwright-report', 'test-results', 'logs', '.ssh', '.aws'].includes(part))
    || /\.(?:pem|key|p12|pfx|jks|keystore|db|sqlite|sqlite3|log|iml)(?:$|[.-])/.test(basename)
    || ['.ds_store', '.netrc', '.npmrc', '.envrc', '.classpath', '.project', 'credentials', 'cookies.txt', 'cookies.json',
      'session-cookies.json', 'storage-state.json', 'storagestate.json'].includes(basename)
    || (basename === '.env' || (basename.startsWith('.env.') && basename !== '.env.example'));
}

function finding(path, commit, type, line = 0) {
  return { path: path.replace(provider, '[redacted]').replace(aws, '[redacted]').replace(jwtShape, '[redacted]'), commit, line, type };
}

function inspectEntry(directory, entry, commit, working, findings) {
  const tab = entry.indexOf('\t');
  if (tab < 0) throw new Error('Invalid tree entry');
  const metadata = entry.slice(0, tab).split(' ');
  const [mode] = metadata;
  const object = metadata[working ? 1 : 2];
  const path = entry.slice(tab + 1);
  if (!path || path.startsWith('/') || path.split('/').some(part => part === '..' || part === '.')) throw new Error('Unsafe path');
  if (forbiddenPath(path)) findings.push(finding(path, commit, 'forbidden-path'));
  if (mode === '120000') { findings.push(finding(path, commit, 'symlink')); return; }
  if (mode !== '100644' && mode !== '100755') { findings.push(finding(path, commit, 'unscanned-object')); return; }
  if (working && metadata[2] !== '0') { findings.push(finding(path, commit, 'unmerged-index')); return; }
  findings.push(...inspect(path, commit, git(directory, ['cat-file', 'blob', object], null)));
  if (!working) return;
  let current = directory;
  let lastStat;
  for (const part of path.split('/')) {
    current = join(current, part);
    let stat;
    try { stat = lstatSync(current); } catch (error) {
      if (error.code === 'ENOENT') return; // A tracked deletion still has its index blob checked above.
      throw error;
    }
    if (stat.isSymbolicLink()) { findings.push(finding(path, 'worktree', 'symlink')); return; }
    lastStat = stat;
  }
  if (!lastStat.isFile() || lastStat.size > MAX_BYTES) { findings.push(finding(path, 'worktree', 'unscanned-file')); return; }
  const descriptor = openSync(current, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = fstatSync(descriptor);
    if (!stat.isFile() || stat.size > MAX_BYTES) { findings.push(finding(path, 'worktree', 'unscanned-file')); return; }
    findings.push(...inspect(path, 'worktree', readFileSync(descriptor)));
  } finally { closeSync(descriptor); }
}

function git(directory, args, encoding = 'utf8') {
  const env = { ...process.env };
  for (const key of Object.keys(env)) if (key.startsWith('GIT_')) delete env[key];
  Object.assign(env, { GIT_NO_LAZY_FETCH: '1', GIT_TERMINAL_PROMPT: '0', GIT_OPTIONAL_LOCKS: '0',
    GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_SYSTEM: '/dev/null', GIT_CONFIG_NOSYSTEM: '1' });
  const result = spawnSync('git', ['--no-replace-objects', '-C', directory, ...args],
    { encoding, env, timeout: 30_000, maxBuffer: MAX_BYTES });
  if (result.status !== 0 || result.error) throw new Error('Git inspection failed');
  return result.stdout;
}

function inspect(path, commit, bytes) {
  const text = bytes.toString('utf8');
  const findings = [];
  const add = (index, type) => findings.push(finding(path, commit, type, text.slice(0, index).split('\n').length));
  for (const [pattern, type] of [
    [provider, 'provider-token'], [aws, 'aws-access-key'],
    [/-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----/g, 'private-key'],
    [/\brag_session=[A-Za-z0-9._~-]{12,}/g, 'session-cookie'],
    [new RegExp(['# Netscape', ' HTTP Cookie File'].join(''), 'g'), 'session-cookie'],
  ]) for (const match of text.matchAll(pattern)) add(match.index, type);
  for (const match of text.matchAll(jwtShape)) {
    try {
      const header = JSON.parse(Buffer.from(match[0].split('.')[0], 'base64url').toString('utf8'));
      if (header && typeof header === 'object' && typeof header.alg === 'string') add(match.index, 'jwt-token');
    } catch { /* Dotted package names are not JWTs. */ }
  }
  if (bytes.subarray(0, 16).equals(Buffer.from('SQLite format 3\0'))) add(0, 'database-content');
  const example = path.split('/').at(-1) === '.env.example';
  const acceptable = value => placeholder(value) || (!example && environmentReference(value));
  // Properties, dotenv and simple YAML assignments; never resolve environment variables.
  let offset = 0;
  const sourceCode = /\.(?:java|js|mjs|cjs|ts|tsx|jsx|py|kt|go|rs|c|cpp|h)$/.test(path);
  for (const line of text.split('\n')) {
    const assignment = !sourceCode && /^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_.-]*)\s*(?::|=(?!=))\s*(.*?)\s*$/.exec(line);
    if (assignment && sensitiveName(assignment[1])) {
      let value = assignment[2].replace(/\s+#.*$/, '').trim();
      if (value.startsWith('#')) value = '';
      if (/^(['"]).*\1$/.test(value)) value = value.slice(1, -1);
      if (!acceptable(value)) add(offset, 'secret-assignment');
    }
    offset += line.length + 1;
  }
  // Separate assignment from object-property syntax; comparisons and ternaries are not assignments.
  const literals = /\b([A-Za-z_][A-Za-z0-9_.-]*)\s*=(?!=)\s*(["'`])([^"'`\r\n]*)\2/g;
  const properties = /(?:^|[{,])\s*(?:(["'])([A-Za-z_][A-Za-z0-9_.-]*)\1|([A-Za-z_][A-Za-z0-9_.-]*))\s*:\s*(["'`])([^"'`\r\n]*)\4/gm;
  const checkLiteral = (match, name, value, terminator) => {
    if (!sensitiveName(name) || acceptable(value)) return;
    const end = text.slice(match.index + match[0].length);
    const reviewed = publicTestValues.get(`${path}#${name}`) === value && terminator.test(end);
    if (!reviewed) add(match.index, 'secret-assignment');
  };
  for (const match of text.matchAll(literals)) checkLiteral(match, match[1], match[3], /^\s*;/);
  for (const match of text.matchAll(properties)) checkLiteral(match, match[2] || match[3], match[5], /^\s*[,}]/);
  for (const match of text.matchAll(/<([A-Za-z_][A-Za-z0-9_-]*)>\s*([^<>]*?)\s*<\/\1>/g)) {
    if (sensitiveName(match[1]) && !acceptable(match[2])) add(match.index, 'secret-assignment');
  }
  return findings;
}

try {
  const args = process.argv.slice(2);
  let directory = process.cwd();
  let history = false;
  for (let index = 0; index < args.length; index++) {
    if (args[index] === '--repo' && args[index + 1]) directory = args[++index];
    else if (args[index] === '--history') history = true;
    else throw new Error('Unsupported arguments');
  }
  directory = git(directory, ['rev-parse', '--show-toplevel']).trim();
  const findings = [];
  for (const entry of git(directory, ['ls-files', '--stage', '-z']).split('\0').filter(Boolean)) {
    inspectEntry(directory, entry, 'index', true, findings);
  }
  if (history) {
    if (git(directory, ['rev-parse', '--is-shallow-repository']).trim() !== 'false') throw new Error('Incomplete history');
    for (const commit of git(directory, ['rev-list', '--all']).split('\n').filter(Boolean)) {
      for (const entry of git(directory, ['ls-tree', '-r', '-z', '--full-tree', commit]).split('\0').filter(Boolean)) {
        inspectEntry(directory, entry, commit, false, findings);
      }
    }
  }
  process.stdout.write(JSON.stringify(findings, null, 2) + '\n');
  process.exitCode = findings.length ? 1 : 0;
} catch {
  process.stdout.write(JSON.stringify([{ path: '.', commit: '', line: 0, type: 'scan-failed' }]) + '\n');
  process.exitCode = 2;
}
