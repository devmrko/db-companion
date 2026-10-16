import test from 'node:test';
import assert from 'node:assert/strict';
import {repairCanApply} from '../../main/resources/static/js/function-repair.mjs';
import {adminRemedy} from '../../main/resources/static/js/ai-assistant.mjs';
test('function replacement requires preview, consent and an idle form',()=>{
  assert.equal(repairCanApply({},true,false),true);
  for(const args of [[null,true,false],[{},false,false],[{},true,true]])assert.equal(repairCanApply(...args),false);
});
test('permission remedies quote exact account and never invent privileges for validation errors',()=>{
  assert.match(adminRemedy({error:'CTXSYS.CTX_DDL 권한 없음'},403,'/business-glossary/setup-preview','A"B'),/GRANT EXECUTE ON CTXSYS.CTX_DDL TO "A""B";/);
  assert.match(adminRemedy({error:'Failed (ORA-01031)'},503,'/db/functions/repair/apply','APP_TEST'),/GRANT CREATE PROCEDURE TO "APP_TEST";/);
  assert.equal(adminRemedy({error:'Invalid confirmation'},403,'/x','APP_TEST'),'');
  assert.equal(adminRemedy({error:'ORA-01031'},503,'/x',''),'');
});
