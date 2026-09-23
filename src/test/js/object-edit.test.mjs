import test from 'node:test';
import assert from 'node:assert/strict';
import {objectField,toolNames,updateTool,updateParameter,parameterKeys,validateObjectValue,objectRetryAllowed} from '../../main/resources/static/js/object-fields.mjs';
import {teamChanges} from '../../main/resources/static/js/team-history.mjs';
test('editable fields are specific to kind and immutable supervisor has help only',()=>{
  for(const [kind,names] of Object.entries({AGENT:['role','profile_name','enable_human_tool','tools','short_term_memory_length'],TASK:['instruction','tools','input','enable_human_tool'],TOOL:['instruction','function','tool_type','tool_params','tool_inputs']}))for(const name of names)assert.ok(objectField(kind,name).help);
  assert.equal(objectField('AGENT','supervisor').type,'readonly');assert.ok(validateObjectValue('AGENT','supervisor','true'));
  assert.equal(objectField('TASK','profile_name'),null);assert.ok(validateObjectValue('TEAM','agents','[]'));
});
test('long unicode text is retained with byte limits rather than character truncation',()=>{
  const long='원문\n{query}\n'+'긴 지침 '.repeat(1700);
  assert.equal(validateObjectValue('TASK','instruction',long),'');
  for(const bad of ['', ' ', '\0', '\ud800', '한'.repeat(11000)])assert.ok(validateObjectValue('AGENT','role',bad));
});
test('toggles numbers and tool types are validated',()=>{
  for(const value of ['true','false','True'])assert.equal(validateObjectValue('AGENT','enable_human_tool',value),'');
  assert.ok(validateObjectValue('AGENT','enable_human_tool','yes'));
  for(const value of ['0','1.2','-1','NaN'])assert.ok(validateObjectValue('AGENT','short_term_memory_length',value));
  assert.equal(validateObjectValue('AGENT','short_term_memory_length','30'),'');
  assert.equal(validateObjectValue('TOOL','tool_type','RAG'),'');assert.ok(validateObjectValue('TOOL','tool_type','CUSTOM'));
});
test('tool list changes preserve unrelated references and reject duplicate names',()=>{
  assert.deepEqual(toolNames(updateTool('["A","B"]',1,'C')),['A','C']);
  assert.equal(validateObjectValue('TASK','tools','[]'),'');
  for(const value of ['{}','null','[7]','["A","a"]','[""]'])assert.ok(validateObjectValue('TASK','tools',value));
  assert.throws(()=>updateTool('["A"]',-1,'B'));
});
test('parameter selection preserves unknown nested fields and null values',()=>{
  const updated=JSON.parse(updateParameter('{"profile_name":"P","extra":{"x":true},"keep":null}','profile_name','Q'));
  assert.deepEqual(updated,{profile_name:'Q',extra:{x:true},keep:null});
  assert.throws(()=>updateParameter('[]','profile_name','Q'));
  assert.throws(()=>updateParameter('{}','__proto__','Q'));
  assert.ok(validateObjectValue('TOOL','tool_params','null'));assert.equal(validateObjectValue('TOOL','tool_params','{"x":null}'),'');
});
test('input argument JSON accepts extensions but rejects malformed or duplicate names',()=>{
  assert.equal(validateObjectValue('TOOL','tool_inputs','[{"name":"x","description":"설명","extra":true}]'),'');
  for(const value of ['{}','[{}]','[{"name":"x","description":7}]','[{"name":"x"},{"name":"x"}]'])assert.ok(validateObjectValue('TOOL','tool_inputs',value));
});
test('structured params do not round large or decimal unknown values',()=>{
  for(const number of ['9007199254740993','1.0000000000000002','-0'])assert.throws(()=>updateParameter(`{"profile_name":"P","nested":{"n":${number}}}`,'profile_name','Q'),/JSON 직접 편집/);
  assert.equal(JSON.parse(updateParameter('{"n":42}','profile_name','Q')).n,42);
  assert.deepEqual(parameterKeys('NOTIFICATION',{notification_type:'email'}),['notification_type','credential_name','recipient','sender','smtp_host']);
  assert.deepEqual(parameterKeys('NOTIFICATION',{notification_type:7}),['notification_type','credential_name']);
});
test('object comparison is reused with kind-specific labels and full multiline values',()=>{
  const before={info:{name:'A'},attributes:[{name:'instruction',value:'line\nold\n'}]},after={info:{name:'A'},attributes:[{name:'instruction',value:'line\nnew\n'}]};
  const diff=teamChanges(before,after,'Task');assert.equal(diff.length,1);assert.equal(diff[0].name,'instruction');
  assert.ok(diff[0].rows.some(row=>row.kind==='removed'));assert.ok(diff[0].rows.some(row=>row.kind==='added'));
  assert.equal(teamChanges(before,before,'Tool').length,0);
});
test('only pre-write input rejection allows another save in the same form',()=>{
  assert.equal(objectRetryAllowed(400),true);for(const status of [undefined,202,401,403,409,500,503])assert.equal(objectRetryAllowed(status),false);
});
