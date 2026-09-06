import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../src/main/resources/static/index.html', import.meta.url), 'utf8');

test('all four supported document types are actual selectable option elements', () => {
  const select = html.match(/<select id="type"[^>]*>([\s\S]*?)<\/select>/u)?.[1];
  assert.ok(select);
  assert.deepEqual([...select.matchAll(/<option value="([^"]*)"/gu)].map(match => match[1]),
    ['', 'document', 'image', 'audio', 'video']);
});

test('HTML closing tags cannot contain accidental opening-tag attributes', () => {
  assert.doesNotMatch(html, /<\/[\w-]+\s+[^>\s]/u);
});
