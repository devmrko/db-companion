import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {detailFields, mappedSqlFields, detailRequest} from '../../main/resources/static/js/execution-history.mjs';

test('detail metadata keeps unmodified text, zero and absent values', () => {
  const fields = detailFields({profile: '<script>x</script>', action: 'runsql', sid: 0, serial: null});
  assert.deepEqual(fields[0], ['프로필', '<script>x</script>']);
  assert.deepEqual(fields.at(-2), ['세션 ID', '0']);
  assert.deepEqual(fields.at(-1), ['세션 일련번호', '—']);
});
test('detail requests are bounded read-only and discard stale responses', () => {
  const source = readFileSync(new URL('../../main/resources/static/js/execution-history.mjs', import.meta.url), 'utf8');
  assert.ok(source.includes('detailRequest(root.dataset.executionMode, root.dataset.schema, button.dataset.executionId)'));
  assert.ok(source.includes('controller?.abort()'));
  assert.ok(source.includes('current !== version || !dialog.open'));
  assert.ok(source.includes("prompt.textContent = data.prompt ?? '—'"));
  assert.ok(source.includes("response.textContent = data.response ?? '—'"));
  assert.ok(source.includes("cache: 'no-store'"));
  for (const forbidden of ['innerHTML', 'insertAdjacentHTML', "method: 'POST'", 'setInterval', '.slice(', '.substring(']) assert.ok(!source.includes(forbidden));
});

test('mapped SQL has a separate endpoint and never sends a pretend schema filter', () => {
  assert.equal(detailRequest('sql', 'OTHER', 'safe_key'), '/ai-executions/sql/detail?id=safe_key');
  assert.equal(detailRequest(undefined, 'APP', 'abc-123'), '/ai-executions/detail?schema=APP&id=abc-123');
  assert.equal(detailRequest('sql', '', 'x&schema=SYS'), '/ai-executions/sql/detail?id=x%26schema%3DSYS');
});
test('mapping metadata preserves zero and separates translation from execution', () => {
  const fields = mappedSqlFields({sqlId:'id', useCount:0, cpuTime:0, elapsedTime:'123', translationProfileId:null});
  assert.deepEqual(fields[0], ['SQL ID','id']);
  assert.deepEqual(fields[4], ['매핑 사용 횟수','0']);
  assert.deepEqual(fields[5], ['SQL 번역 프로필 ID','—']);
  assert.ok(fields.every(([label]) => !label.includes('실행') && !label.includes('AI 프로필')));
});
test('both full SQL texts are displayed without executing or truncating them', () => {
  const source = readFileSync(new URL('../../main/resources/static/js/execution-history.mjs', import.meta.url), 'utf8');
  assert.ok(source.includes("prompt.textContent = data.originalSql ?? '—'"));
  assert.ok(source.includes("response.textContent = data.mappedSql ?? '—'"));
  assert.ok(source.includes('aria-expanded'));
  assert.ok(!source.includes('eval('));
});
