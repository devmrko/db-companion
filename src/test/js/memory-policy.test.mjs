import test from 'node:test';
import assert from 'node:assert/strict';
import {linkChoices,normalizeResourceName,memoryPrerequisite} from '../../main/resources/static/js/memory-policy.mjs';
import {validateProfileValue} from '../../main/resources/static/js/profile-editor.mjs';
test('only memory and guardrail links accept an explicit empty unlink',()=>{
  assert.equal(validateProfileValue('guardrail',''),'');
  assert.equal(validateProfileValue('memory_store_name',''),'');
  assert.ok(validateProfileValue('model',''));
  assert.ok(validateProfileValue('guardrail',' '));
});
test('guardrail selection excludes disabled policies but memory stores need no status',()=>{
  const status={stores:[{name:'MEMORY'}],guardrails:[{name:'ON',status:'ENABLED'},{name:'OFF',status:'DISABLED'}]};
  assert.deepEqual(linkChoices('memory_store_name',status),['MEMORY']);
  assert.deepEqual(linkChoices('guardrail',status),['ON']);
});
test('lowercase resource names normalize before submission and missing embedding explains next action',()=>{
  assert.equal(normalizeResourceName(' agentmemory '),'AGENTMEMORY');
  assert.equal(memoryPrerequisite('APP',{conversation:'true'}).ready,false);
  assert.match(memoryPrerequisite('APP',{}).message,/임베딩 모델 설정/);
  assert.equal(memoryPrerequisite('APP',{embedding_model:'database: APP.EMBED_MODEL'}).ready,true);
  assert.equal(memoryPrerequisite('',{}).ready,false);
});
