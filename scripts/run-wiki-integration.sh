#!/usr/bin/env bash
set -euo pipefail
umask 077

# This is an explicit test-source runtime, never a production server launcher.
wiki_mode="${1:-run}"
if [[ $# -gt 1 || ( "$wiki_mode" != run && "$wiki_mode" != --build-only && "$wiki_mode" != --help ) ]]; then
  echo "Usage: bash scripts/run-wiki-integration.sh [--build-only|--help]" >&2
  exit 2
fi
if [[ "$wiki_mode" == --help ]]; then
  echo "Build a NEW isolated Wiki integration runtime, then listen on 127.0.0.1:18091."
  echo "--build-only runs the local HTTP workflow test and validates classpath without starting the runtime."
  echo "Overrides: WIKI_JAVA_HOME, WIKI_MAVEN, WIKI_NODE, WIKI_TEMP_ROOT."
  echo "Model and vector servers are deterministic loopback test doubles; no private configuration is loaded."
  exit 0
fi

wiki_project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
wiki_java_home="${WIKI_JAVA_HOME:-${JAVA_HOME:-/Applications/PyCharm.app/Contents/jbr/Contents/Home}}"
wiki_maven="${WIKI_MAVEN:-/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn}"
wiki_node="${WIKI_NODE:-$(command -v node || true)}"
wiki_temp_root="${WIKI_TEMP_ROOT:-${TMPDIR:-/tmp}}"
for wiki_executable in "$wiki_java_home/bin/java" "$wiki_maven" "$wiki_node"; do
  if [[ ! -x "$wiki_executable" ]]; then
    echo "Required executable unavailable: $wiki_executable" >&2
    exit 1
  fi
done
if [[ "$wiki_temp_root" != /* || ! -d "$wiki_temp_root" || ! -w "$wiki_temp_root" ]]; then
  echo "WIKI_TEMP_ROOT must be an existing writable absolute directory." >&2
  exit 1
fi

# Ignore JVM option injection from the invoking terminal; all configuration below is explicit.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS MAVEN_OPTS MAVEN_ARGS
wiki_java_version="$("$wiki_java_home/bin/java" -version 2>&1)"
if [[ ! "$wiki_java_version" =~ version\ \"21([.\"]|$) ]]; then
  echo "This integration runtime requires JDK 21; set WIKI_JAVA_HOME." >&2
  exit 1
fi

wiki_check_port() {
  "$wiki_node" --input-type=module - <<'NODE'
import net from 'node:net';
const server = net.createServer();
server.once('error', () => {
  console.error('127.0.0.1:18091 is unavailable. Stop the existing local integration runtime explicitly; nothing was replaced.');
  process.exitCode = 1;
});
server.listen({ host: '127.0.0.1', port: 18091, exclusive: true }, () => server.close());
NODE
}

if [[ "$wiki_mode" == run ]]; then
  wiki_check_port
fi
wiki_runtime_dir="$(mktemp -d "$wiki_temp_root/wiki-0056-integration.XXXXXX")"
wiki_runtime_dir="$(cd "$wiki_runtime_dir" && pwd -P)"
wiki_build_dir="$wiki_runtime_dir/build"
wiki_data_dir="$wiki_runtime_dir/data"
wiki_report="$wiki_build_dir/surefire-reports/TEST-com.evidence.rag.support.WikiWorkflowHttpTest.xml"
echo "New isolated runtime: $wiki_runtime_dir"
echo "Build output: $wiki_build_dir"
echo "NEW data directory (created only at server start): $wiki_data_dir"

cd "$wiki_project_dir"
MAVEN_SKIP_RC=true JAVA_HOME="$wiki_java_home" "$wiki_maven" -o \
  -s "$wiki_project_dir/.mvn/settings.xml" -gs "$wiki_project_dir/.mvn/settings.xml" \
  "-Drag.build.directory=$wiki_build_dir" -Dtest=WikiWorkflowHttpTest,WikiModelRebuildHttpTest test

# Keep the quoted heredoc outside command substitution for macOS Bash 3.2 compatibility.
# Extract only the test-generated classpath, not environment properties or model credentials.
wiki_extract_classpath() {
  "$wiki_node" --input-type=module - "$wiki_report" "$wiki_build_dir" <<'NODE'
import fs from 'node:fs';
import path from 'node:path';
const [report, build] = process.argv.slice(2);
const xml = fs.readFileSync(report, 'utf8');
const suite = xml.match(/<testsuite\b[^>]*>/)?.[0];
const attributes = (tag) => Object.fromEntries(
  [...tag.matchAll(/([\w.:-]+)="([^"]*)"/g)].map((match) => [match[1], match[2]])
);
if (!suite) throw new Error('Missing workflow test suite');
const results = attributes(suite);
if (results.name !== 'com.evidence.rag.support.WikiWorkflowHttpTest'
    || !(Number(results.tests) > 0)
    || ['failures', 'errors', 'skipped'].some((key) => results[key] !== '0')) {
  throw new Error('The complete local HTTP workflow test must pass before startup');
}
const values = [...xml.matchAll(/<property\b[^>]*\/?>/g)]
  .map((match) => attributes(match[0]))
  .filter((property) => property.name === 'java.class.path');
if (values.length !== 1 || !values[0].value) throw new Error('Missing or ambiguous test classpath');
const entities = { amp: '&', quot: '"', apos: "'", lt: '<', gt: '>' };
const decoded = values[0].value.replace(/&([^;]+);/g, (_, entity) => {
  if (Object.hasOwn(entities, entity)) return entities[entity];
  if (/^#x[0-9a-f]+$/i.test(entity)) return String.fromCodePoint(parseInt(entity.slice(2), 16));
  if (/^#\d+$/.test(entity)) return String.fromCodePoint(Number(entity.slice(1)));
  throw new Error('Unknown XML entity in classpath');
});
// Surefire may append an empty entry; never implicitly include the source checkout as classpath.
const entries = decoded.split(path.delimiter).filter(Boolean);
const expected = [path.join(build, 'test-classes'), path.join(build, 'classes')];
if (entries[0] !== expected[0] || entries[1] !== expected[1]
    || entries.some((entry) => !path.isAbsolute(entry) || !fs.existsSync(entry))) {
  throw new Error('Classpath must use this new build and existing absolute dependency paths');
}
if (!fs.existsSync(path.join(expected[0], 'com/evidence/rag/support/WikiLocalIntegrationServer.class'))) {
  throw new Error('Local integration launcher was not compiled');
}
process.stdout.write(entries.join(path.delimiter));
NODE
}
wiki_classpath="$(wiki_extract_classpath)"

if [[ "$wiki_mode" == --build-only ]]; then
  echo "BUILD_ONLY_VERIFIED: workflow test passed and isolated test-source classpath validated."
  echo "No persistent server started; no integration data directory created."
  exit 0
fi

# Check again after compilation, before creating data or activating local fixtures.
wiki_check_port
echo "Starting LOCAL SYNTHETIC PROTOCOL runtime. This is not a production deployment."
echo "Keep this terminal open; type restart for same-data restart, or stop to shut down."
exec "$wiki_java_home/bin/java" -Djava.awt.headless=true -cp "$wiki_classpath" \
  com.evidence.rag.support.WikiLocalIntegrationServer "$wiki_data_dir"
