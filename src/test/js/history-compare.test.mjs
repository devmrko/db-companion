import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {sameHistoryItem, selectHistoryVersion, readHistorySnapshot, compareHistorySnapshots, diffHistoryText}
  from '../../main/resources/static/js/history-compare.mjs';

// Pure value/state tests only; no DB, HTTP, login, or DOM substitutes.
const json = (value, exists = true) => JSON.stringify({exists, value});
const row = (seq, overrides = {}) => ({seq, schema: 'APP', table: 'T', column: 'C', kind: 'COMMENT', annotationName: null, ...overrides});
const textFor = (diff, side) => diff.parts.filter(p => p.kind !== (side === 'left' ? 'added' : 'removed')).map(p => p.text).join('');

test('same item requires every exact identifier, including case and table versus column', () => {
  assert.equal(sameHistoryItem(row('1'), row('2')), true);
  for (const [key, value] of Object.entries({schema: 'OTHER', table: 'OTHER', column: null, kind: 'ANNOTATION', annotationName: 'name'}))
    assert.equal(sameHistoryItem(row('1'), row('2', {[key]: value})), false);
  assert.equal(sameHistoryItem(row('1'), row('2', {column: 'c'})), false);
  assert.equal(sameHistoryItem(row('1', {annotationName: 'Unit'}), row('2', {annotationName: 'UNIT'})), false);
});

test('first choice compares the actual before and after of that record', () => {
  const entry = row('900719925474099312345678');
  for (const side of ['left', 'right']) {
    const result = selectHistoryVersion(null, entry, side);
    assert.equal(result.left.row.seq, entry.seq);
    assert.equal(result.left.phase, 'before');
    assert.equal(result.right.phase, 'after');
  }
});

test('a record from another page changes only the chosen side and retains the selected phase', () => {
  const initial = selectHistoryVersion(null, row('21'), 'left');
  initial.left.phase = 'after';
  const changed = selectHistoryVersion(initial, row('1'), 'left');
  assert.equal(changed.left.row.seq, '1');
  assert.equal(changed.left.phase, 'after');
  assert.strictEqual(changed.right, initial.right);
  assert.equal(initial.left.row.seq, '21');
});

test('different comparison item is rejected; choosing a new base resets both sides', () => {
  const initial = selectHistoryVersion(null, row('1'), 'left');
  const other = row('2', {kind: 'ANNOTATION', annotationName: 'UNITS'});
  assert.throws(() => selectHistoryVersion(initial, other, 'right'), /같은 항목/);
  assert.equal(initial.right.row.seq, '1');
  const changed = selectHistoryVersion(initial, other, 'left');
  assert.strictEqual(changed.left.row, other);
  assert.strictEqual(changed.right.row, other);
});

test('same value, additions, removals and edits have distinct change kinds', () => {
  assert.equal(compareHistorySnapshots(json('국가'), json('국가')).kind, 'equal');
  assert.equal(compareHistorySnapshots(json(null, false), json('원')).kind, 'added');
  assert.equal(compareHistorySnapshots(json('원'), json(null, false)).kind, 'removed');
  assert.equal(compareHistorySnapshots(json('원'), json('달러')).kind, 'changed');
});

test('absence, valueless annotation, empty string, literal null and whitespace are not conflated', () => {
  const states = [json(null, false), json(null), json(''), json('null'), json(' ')];
  for (let i = 0; i < states.length; i++) for (let j = 0; j < states.length; j++)
    assert.equal(compareHistorySnapshots(states[i], states[j]).kind === 'equal', i === j);
  assert.equal(readHistorySnapshot(json(null)).exists, true);
});

test('malformed and incompatible JSON are not silently compared as equal', () => {
  for (const raw of ['not json', 'null', '[]', '{}', '{"exists":true}', '{"exists":1,"value":"x"}',
    '{"exists":true,"value":{}}', '{"exists":false,"value":"x"}', null]) {
    assert.equal(readHistorySnapshot(raw).valid, false);
    const result = compareHistorySnapshots(raw, raw);
    assert.equal(result.kind, 'invalid');
    assert.equal(result.left.raw, raw ?? '');
  }
});

test('Korean insertion retains the unchanged prefix and highlights only inserted text', () => {
  const result = diffHistoryText('스냅샷일자.', '스냅샷일자. [검증]');
  assert.deepEqual(result.parts, [{kind: 'equal', text: '스냅샷일자.'}, {kind: 'added', text: ' [검증]'}]);
});

test('middle edits preserve multiple unchanged spans', () => {
  const result = diffHistoryText('국가 A / 통화 B', '국가 C / 통화 D');
  assert.equal(textFor(result, 'left'), '국가 A / 통화 B');
  assert.equal(textFor(result, 'right'), '국가 C / 통화 D');
  assert.equal(result.parts.some(p => p.kind === 'equal' && p.text.includes(' / 통화 ')), true);
});

test('grapheme diff does not split emoji, combining marks or Korean decomposed syllables', () => {
  for (const [before, after] of [['👍🏽', '👍🏻'], ['👨‍👩‍👦', '👨‍👩‍👧'], ['e\u0301', 'e'], ['가', '거']]) {
    const result = diffHistoryText(`시작 ${before} 끝`, `시작 ${after} 끝`);
    assert.equal(result.parts.find(p => p.kind === 'removed').text, before);
    assert.equal(result.parts.find(p => p.kind === 'added').text, after);
  }
});

test('large text uses bounded fallback and still preserves every character', () => {
  const before = '앞 ' + '가'.repeat(32_767) + ' 끝';
  const after = '앞 ' + '나'.repeat(32_767) + ' 끝';
  const result = diffHistoryText(before, after);
  assert.equal(result.coarse, true);
  assert.equal(textFor(result, 'left'), before);
  assert.equal(textFor(result, 'right'), after);
  assert.equal(result.parts.length, 4);
});

test('empty values, newlines and untrusted markup are preserved exactly as text', () => {
  for (const [before, after] of [['', ''], ['', 'a'], ['a', ''], ['a\n b\t', 'a\r\n b  '],
    ['<script>"x"</script>', '<img src=x onerror=alert(1)>']]) {
    const result = diffHistoryText(before, after);
    assert.equal(textFor(result, 'left'), before);
    assert.equal(textFor(result, 'right'), after);
  }
});

test('small sequence variations always reconstruct both originals', () => {
  const variants = ['', '가', '가 나', '가나다', '나가다', '🏠 가나다\n', '<b>가</b>', '가\t가\n나'];
  for (const a of variants) for (const b of variants) {
    const result = diffHistoryText(a, b);
    assert.equal(textFor(result, 'left'), a);
    assert.equal(textFor(result, 'right'), b);
  }
});

test('comparison UI only uses text nodes, never network calls or HTML injection', () => {
  const source = readFileSync(new URL('../../main/resources/static/js/history-compare.mjs', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /innerHTML|outerHTML|insertAdjacentHTML|fetch\(|localStorage|sessionStorage/);
  assert.match(source, /result.textContent = text/);
  assert.match(source, /label.htmlFor = select.id/);
});
