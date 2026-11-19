import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {localRoles,userAssignments,probeSummary} from '../../main/resources/static/js/dds-identities.mjs';
test('mapped roles and grants to a role are not local end-user assignments',()=>{
  assert.deepEqual(localRoles([{DATA_ROLE:'LOCAL',MAPPED_TO:null},{DATA_ROLE:'EXTERNAL',MAPPED_TO:'IAM_OAUTH_GROUP=G'}]),['LOCAL']);
  assert.deepEqual(userAssignments({assignments:[{GRANTEE:'U',GRANTEE_TYPE:'DATA ROLE'},{GRANTEE:'U',GRANTEE_TYPE:'END USER',DATA_ROLE:'R'}]},'U'),[{GRANTEE:'U',GRANTEE_TYPE:'END USER',DATA_ROLE:'R'}]);
});
test('zero rows and role lookup failure are observations, not isolation claims',()=>{
  const text=probeSummary({endUser:'U',rolesStatus:'UNVERIFIED',queryStatus:'READ_COMPLETED',visibleRows:0});
  assert.match(text,/unverified/);assert.match(text,/: 0/);assert.doesNotMatch(text,/isolated|passed/i);
  assert.match(probeSummary({endUser:'U',rolesStatus:'VERIFIED',activeRoles:['R'],queryStatus:'REJECTED_OR_FAILED',queryCode:'ORA-00942'}),/ORA-00942/);
});
test('identity UI renders untrusted text safely and has complete translations',()=>{
  const source=fs.readFileSync('src/main/resources/static/js/dds-identities.mjs','utf8');
  assert.doesNotMatch(source,/innerHTML|localStorage|sessionStorage|console\.|insertAdjacentHTML/);
  for(const [key,values] of Object.entries(JSON.parse(fs.readFileSync('tools/i18n/feature-dds-identities.json','utf8')))){
    assert.equal(values.length,4,key);values.forEach((v,i)=>{assert.ok(v.trim(),key);if(i)assert.doesNotMatch(v,/[가-힣]/u,key);});
  }
});
