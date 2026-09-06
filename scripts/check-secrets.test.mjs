import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const checker = fileURLToPath(new URL('./check-secrets.mjs', import.meta.url));
const jwtVariable = ['RAG_JWT_', 'SECRET'].join('');
const apiVariable = ['OPENAI_API_', 'KEY'].join('');
const envAssignment = (name, value) => `${name}=${value}`;
const javaAssignment = value => ['private static final String ', 'SEC', 'RET = "', value, '";'].join('');

function repository(t) {
  const directory = mkdtempSync(join(tmpdir(), 'ai-knowledge-secret-tests-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  git(directory, ['init', '--quiet']);
  return directory;
}

function git(directory, args) {
  const result = spawnSync('git', ['-C', directory, '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgSign=false', ...args], { encoding: 'utf8' });
  assert.equal(result.status, 0, 'Synthetic Git fixture operation succeeds');
  return result.stdout;
}

function file(directory, path, contents) {
  mkdirSync(dirname(join(directory, path)), { recursive: true });
  writeFileSync(join(directory, path), contents);
  git(directory, ['add', '--', path]);
}

function scan(directory, ...args) {
  const result = spawnSync(process.execPath, [checker, '--repo', directory, ...args], { encoding: 'utf8' });
  return { status: result.status, findings: JSON.parse(result.stdout), output: result.stdout + result.stderr };
}

test('tracked provider credential is blocked without printing its value', t => {
  const directory = repository(t);
  const credential = ['s', 'k-', 'a'.repeat(40)].join('');
  file(directory, 'source.txt', `first line\n${credential}\n`);
  const result = scan(directory);
  assert.equal(result.status, 1);
  assert.ok(result.findings.some(finding => finding.path === 'source.txt' && finding.line === 2 && finding.type === 'provider-token'));
  assert.ok(!result.output.includes(credential));
});

test('private material and generated runtime file names are blocked', t => {
  const directory = repository(t);
  const forbidden = ['key.pem', 'key.key', 'certificate.p12', '.env', '.env.local', '.env.production',
    'library.db', 'library.db-wal', 'library.sqlite3', 'request.log', 'target/output.txt', 'node_modules/package/index.js',
    '.idea/workspace.xml', '.vscode/settings.json', '.DS_Store', 'cookies.txt', 'storage-state.json', 'nested/.data/content.txt'];
  for (const path of forbidden) file(directory, path, 'benign fixture');
  const result = scan(directory);
  assert.equal(result.status, 1);
  for (const path of forbidden) assert.ok(result.findings.some(finding => finding.path === path && finding.type === 'forbidden-path'), path);
});

test('symlinks are refused without following or requiring their target', t => {
  const directory = repository(t);
  symlinkSync('/nonexistent-ai-knowledge-fixture/never-read', join(directory, 'link.txt'));
  git(directory, ['add', '--', 'link.txt']);
  const result = scan(directory);
  assert.equal(result.status, 1);
  assert.ok(result.findings.some(finding => finding.path === 'link.txt' && finding.type === 'symlink'));
});

test('history detects a credential deleted from the current tree', t => {
  const directory = repository(t);
  const credential = ['gh', 'p_', 'A'.repeat(36)].join('');
  file(directory, 'removed.txt', credential);
  git(directory, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '--quiet', '-m', 'Synthetic first version']);
  const original = git(directory, ['rev-parse', 'HEAD']).trim();
  git(directory, ['rm', '--quiet', '--', 'removed.txt']);
  git(directory, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '--quiet', '-m', 'Remove synthetic value']);
  assert.equal(scan(directory).status, 0);
  const result = scan(directory, '--history');
  assert.equal(result.status, 1);
  assert.ok(result.findings.some(finding => finding.path === 'removed.txt' && finding.commit === original && finding.type === 'provider-token'));
  assert.ok(!result.output.includes(credential));
});

test('private keys, AWS IDs, JWTs, session cookies and renamed databases are detected by content', t => {
  const directory = repository(t);
  const signedShape = [Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url'),
    Buffer.from(JSON.stringify({ sub: 'fixture-user', exp: 2000000000 })).toString('base64url'), 'z'.repeat(43)].join('.');
  const fixtures = [
    ['key.txt', ['-----BEGIN ', 'OPENSSH PRIVATE KEY-----\nfixture\n'].join(''), 'private-key'],
    ['aws.txt', ['AK', 'IA', 'A'.repeat(16)].join(''), 'aws-access-key'],
    ['jwt.txt', signedShape, 'jwt-token'],
    ['cookie.txt', ['rag_', 'session=', 'fixture-session-value-abcdefgh'].join(''), 'session-cookie'],
    ['database.txt', Buffer.from(['SQLite format ', '3\0'].join('')), 'database-content'],
  ];
  for (const [path, contents] of fixtures) file(directory, path, contents);
  const result = scan(directory);
  assert.equal(result.status, 1);
  for (const [path, contents, type] of fixtures) {
    assert.ok(result.findings.some(finding => finding.path === path && finding.type === type), type);
    assert.ok(!result.output.includes(String(contents)));
  }
});

test('example env allows only empty or explicit placeholders and other configs permit safe env references', t => {
  const directory = repository(t);
  file(directory, '.env.example', [envAssignment(jwtVariable, ''), envAssignment(apiVariable, '<YOUR_API_KEY>'), 'RAG_AUTH_MODE=jwt'].join('\n'));
  file(directory, 'application.properties', ['rag.jwt-secret=${RAG_JWT_SECRET:}', 'remote.api-key=${PROVIDER_API_KEY}',
    'spring.jackson.factory.constraints.read.max-token-count=20000'].join('\n'));
  assert.equal(scan(directory).status, 0);
  file(directory, '.env.example', [envAssignment(jwtVariable, ''), envAssignment(apiVariable, 'ordinary-looking-hardcoded-value')].join('\n'));
  let result = scan(directory);
  assert.equal(result.status, 1);
  assert.ok(result.findings.some(finding => finding.path === '.env.example' && finding.line === 2 && finding.type === 'secret-assignment'));
  assert.ok(!result.output.includes('ordinary-looking-hardcoded-value'));
  file(directory, '.env.example', 'RAG_JWT_SECRET=${ANOTHER_SECRET}');
  assert.equal(scan(directory).status, 1, 'Example files must contain placeholders, not secret resolution expressions');
  file(directory, 'application.properties', 'rag.jwt-secret=${RAG_JWT_SECRET:hardcoded-default-value}');
  result = scan(directory);
  assert.ok(result.findings.some(finding => finding.path === 'application.properties' && finding.type === 'secret-assignment'));
});

test('only exact reviewed synthetic path and value are allowed, not arbitrary test constants', t => {
  const directory = repository(t);
  const reviewed = 'src/test/java/com/evidence/rag/security/JwtHttpTest.java';
  const publicValue = ['0123456789abcdef', '0123456789abcdef'].join('');
  const assignment = javaAssignment(publicValue);
  file(directory, reviewed, assignment);
  assert.equal(scan(directory).status, 0);
  file(directory, 'src/test/java/UnreviewedTest.java', assignment);
  let result = scan(directory);
  assert.equal(result.status, 1);
  assert.ok(result.findings.some(finding => finding.path === 'src/test/java/UnreviewedTest.java' && finding.type === 'secret-assignment'));
  file(directory, reviewed, javaAssignment(publicValue).replace('";', '" + "changed";'));
  result = scan(directory);
  assert.ok(result.findings.some(finding => finding.path === reviewed && finding.type === 'secret-assignment'));
  const credential = ['s', 'k-', 'B'.repeat(40)].join('');
  file(directory, reviewed, javaAssignment(credential));
  result = scan(directory);
  assert.ok(result.findings.some(finding => finding.path === reviewed && finding.type === 'provider-token'));
  assert.ok(!result.output.includes(credential));
});

test('checker and its own fixture generators do not contain material requiring broad exemptions', t => {
  const directory = repository(t);
  file(directory, 'scripts/check-secrets.mjs', readFileSync(checker));
  file(directory, 'scripts/check-secrets.test.mjs', readFileSync(fileURLToPath(import.meta.url)));
  const result = scan(directory);
  assert.equal(result.status, 0, JSON.stringify(result.findings));
});

test('credential-shaped filenames are redacted in findings too', t => {
  const directory = repository(t);
  const credential = ['s', 'k-', 'C'.repeat(40)].join('');
  file(directory, `${credential}.txt`, credential);
  const result = scan(directory);
  assert.equal(result.status, 1);
  assert.ok(!result.output.includes(credential));
  assert.ok(result.findings.every(finding => Object.keys(finding).sort().join(',') === 'commit,line,path,type'));
});

test('index and unstaged changes are both inspected and missing git fails closed', t => {
  const directory = repository(t);
  const credential = ['github_', 'pat_', 'D'.repeat(50)].join('');
  file(directory, 'staged.txt', credential);
  writeFileSync(join(directory, 'staged.txt'), 'clean worktree');
  let result = scan(directory);
  assert.ok(result.findings.some(finding => finding.commit === 'index'));
  file(directory, 'staged.txt', 'clean index');
  writeFileSync(join(directory, 'staged.txt'), credential);
  result = scan(directory);
  assert.ok(result.findings.some(finding => finding.commit === 'worktree'));
  const outside = mkdtempSync(join(tmpdir(), 'ai-knowledge-secret-tests-'));
  t.after(() => rmSync(outside, { recursive: true, force: true }));
  assert.equal(scan(outside).status, 2);
});

test('source expressions and ternary labels are not mistaken for literal credentials', t => {
  const directory = repository(t);
  file(directory, 'Authentication.java', ['token = cookie.getValue();', 'if (token == "comparison") {}',
    'String label = good ? "secret" : "public";'].join('\n'));
  const body = value => JSON.stringify({ [['to', 'ken'].join('')]: value });
  file(directory, 'ui-tests/api.test.mjs', body('test-token'));
  assert.equal(scan(directory).status, 0);
  file(directory, 'ui-tests/unknown.test.mjs', body('test-token'));
  let result = scan(directory);
  assert.ok(result.findings.some(finding => finding.path === 'ui-tests/unknown.test.mjs' && finding.type === 'secret-assignment'));
  file(directory, 'Authentication.java', javaAssignment('unreviewed-literal-secret'));
  result = scan(directory);
  assert.ok(result.findings.some(finding => finding.path === 'Authentication.java' && finding.type === 'secret-assignment'));
});
