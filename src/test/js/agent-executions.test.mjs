import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {agentRequest, taskFields, createReadGate} from '../../main/resources/static/js/agent-executions.mjs';

test('agent URLs include run scope, exact task order and bound page', () => {
  assert.equal(agentRequest('task','APP','run-1',0), '/ai-executions/agents/task?schema=APP&runId=run-1&order=0');
  assert.equal(agentRequest('conversations','A&B','run-1',2,3), '/ai-executions/agents/conversations?schema=A%26B&runId=run-1&order=2&page=3');
  assert.throws(() => agentRequest('execute','APP','r',0));
});
test('metadata retains absent values, unknown status and exact text', () => {
  const fields = taskFields({task:'<script>x</script>',order:0,state:'FUTURE'});
  assert.deepEqual(fields[0], ['Task','<script>x</script>']);
  assert.deepEqual(fields[2], ['순번','0']);
  assert.deepEqual(fields[3], ['상태','FUTURE']);
  assert.deepEqual(fields.at(-1), ['대화 ID','—']);
});
test('reselection and close invalidate older successful and failing reads', () => {
  const gate = createReadGate(); const first = gate.begin(), second = gate.begin();
  assert.equal(first.signal.aborted,true); assert.equal(first.current(),false);
  assert.equal(second.current(),true); gate.cancel();
  assert.equal(second.current(),false); assert.equal(second.signal.aborted,true);
  const reopened = gate.begin(); assert.equal(reopened.current(),true); assert.equal(reopened.signal.aborted,false);
});
test('nested reads only start on selection and display text without truncation', () => {
  const js = readFileSync(new URL('../../main/resources/static/js/agent-executions.mjs', import.meta.url),'utf8');
  assert.ok(js.includes("element('input').textContent = data.input ?? '—'"));
  assert.ok(js.includes("element('result').textContent = data.result ?? '—'"));
  assert.ok(js.includes("element('conversations').addEventListener('click'"));
  assert.ok(js.includes("if (!request.current() || !dialog.open) return"));
  assert.ok(js.includes("clearConversations();"));
  assert.ok(js.includes("cache: 'no-store'"));
  for (const forbidden of ['innerHTML','insertAdjacentHTML','setInterval','eval(',"method: 'POST'",'.slice(','.substring(']) assert.ok(!js.includes(forbidden));
});
