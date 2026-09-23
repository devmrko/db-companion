import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {detailRequest, sourceFields} from '../../main/resources/static/js/execution-history.mjs';

test('supplemental detail uses an encoded session selection, never schema or arbitrary SQL', () => {
  assert.equal(detailRequest('history','SYS','x&sql=drop'),'/ai-executions/sql/sources/detail?id=x%26sql%3Ddrop');
});
test('raw metadata preserves zero and text without HTML interpretation', () => {
  assert.deepEqual(sourceFields({fields:[{name:'RETURN_CODE',value:0},{name:'OBJECT_NAME',value:'<script>x</script>'},{name:'SCN',value:null}]}),
    [['RETURN_CODE','0'],['OBJECT_NAME','<script>x</script>'],['SCN','—']]);
  assert.deepEqual(sourceFields({}),[]);
});
test('full SQL is shown as text with existing stale request and modal cancellation guards', () => {
  const script=readFileSync(new URL('../../main/resources/static/js/execution-history.mjs',import.meta.url),'utf8');
  assert.ok(script.includes("prompt.textContent = data.sql ?? '—'"));
  assert.ok(script.includes('current !== version || !dialog.open'));
  assert.ok(script.includes("dialog.addEventListener('close'"));
  assert.ok(!script.includes('innerHTML'));
});
