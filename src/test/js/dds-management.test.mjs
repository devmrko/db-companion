import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {canApply,grantDraft,roleMapping} from '../../main/resources/static/js/dds-management.mjs';
test('OCI mapping parser preserves named domains and rejects unknown types',()=>{
  assert.deepEqual(roleMapping('IAM_GROUP_NAME=Domain/한글'),{mode:'IAM_GROUP_NAME',group:'Domain/한글'});
  assert.deepEqual(roleMapping('IAM_OAUTH_GROUP=Group'),{mode:'IAM_OAUTH_GROUP',group:'Group'});
  assert.deepEqual(roleMapping(null),{mode:'LOCAL',group:''});
  assert.throws(()=>roleMapping('OTHER=Group'));
});
test('DDS apply requires current preview exact confirmation and consent',()=>{
  assert.equal(canApply({confirmation:'A'},'A',true,false),true);
  for(const args of [[null,'A',true,false],[{confirmation:'A'},'B',true,false],[{confirmation:'A'},'A',false,false],[{confirmation:'A'},'A',true,true]])assert.equal(canApply(...args),false);
});
test('DDS supported predicate form preserves unicode and quotes without executing input',()=>{
  const row={GRANT_NAME:'G',OBJECT_NAME:'T',GRANTEE:'R',PREDICATE:'"C" = \'한글 \'\' 🧪\''};
  assert.equal(grantDraft(row,'APP').value,"한글 ' 🧪");
  assert.equal(grantDraft({...row,PREDICATE:'C = 10'},'APP').valueType,'NUMBER');
  assert.throws(()=>grantDraft({...row,PREDICATE:'C = 1 OR 1=1'},'APP'));
});
test('DDS mutation text is safe and translations cover all four languages',()=>{
  const code=fs.readFileSync('src/main/resources/static/js/dds-management.mjs','utf8');assert.doesNotMatch(code,/innerHTML|insertAdjacentHTML|eval\(/);
  const messages=JSON.parse(fs.readFileSync('tools/i18n/feature-dds-management.json','utf8'));
  for(const [key,values] of Object.entries(messages)){assert.equal(values.length,4,key);values.forEach((v,i)=>{assert.ok(v.trim(),key);if(i)assert.doesNotMatch(v,/[가-힣]/u,key);});}
});
