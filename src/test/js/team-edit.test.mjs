import test from 'node:test';
import assert from 'node:assert/strict';
import {teamField,validateTeamValue,referenceName,referenceValue,moveAssignment,changeAssignment,removeAssignment} from '../../main/resources/static/js/team-fields.mjs';
import {teamRetryAllowed} from '../../main/resources/static/js/team-editor.mjs';
import {teamChanges,outcomeLabel,historyFallbackSelectors} from '../../main/resources/static/js/team-history.mjs';
const pairs=JSON.stringify([{name:'A',task:'T',extra:{keep:true}},{name:'B',task:'U'}]);
test('known Team fields are typed and have help; no task/agent-body editing',()=>{
  for(const key of ['agents','process','supervisor_agent','long_term_memory_length'])assert.ok(teamField(key).help);
  assert.equal(teamField('instruction'),null);assert.equal(teamField('profile_name'),null);
});
test('pairs preserve extra fields and ordering during edit/move/remove',()=>{
  const changed=changeAssignment(pairs,0,'task','V');assert.deepEqual(JSON.parse(changed)[0],{name:'A',task:'V',extra:{keep:true}});
  assert.equal(JSON.parse(moveAssignment(pairs,1,-1))[0].name,'B');assert.equal(moveAssignment(pairs,0,-1),pairs);
  assert.deepEqual(JSON.parse(removeAssignment(pairs,1)),[{name:'A',task:'T',extra:{keep:true}}]);
  assert.throws(()=>changeAssignment(pairs,0,'constructor','x'));assert.throws(()=>removeAssignment(pairs,9));
});
test('invalid references, duplicates, empty arrays and broken Unicode are rejected',()=>{
  assert.equal(validateTeamValue('agents',pairs),'');
  for(const value of ['[]','{}','null','[{"name":"","task":"T"}]','[{"name":"A","task":"T"},{"name":"a","task":"t"}]'])assert.ok(validateTeamValue('agents',value));
  assert.ok(validateTeamValue('supervisor_agent','\ud800'));assert.ok(validateTeamValue('supervisor_agent','한'.repeat(43)));
  assert.ok(validateTeamValue('agents',pairs,1));
});
test('Oracle reference names preserve quoted identifiers',()=>{
  assert.equal(referenceName('agent'),'AGENT');assert.equal(referenceName('"MiX""ed"'),'MiX"ed');
  assert.equal(referenceValue('AGENT'),'AGENT');assert.equal(referenceValue('MiX"ed'),'"MiX""ed"');
});
test('memory integer and supported process; no invented modes',()=>{
  assert.equal(validateTeamValue('long_term_memory_length','30'),'');assert.equal(validateTeamValue('process','sequential'),'');
  for(const value of ['0','-1','1.2','1e2'])assert.ok(validateTeamValue('long_term_memory_length',value));
  assert.ok(validateTeamValue('process','parallel'));assert.ok(validateTeamValue('description','x'));
});
test('uncertain save never auto-retries',()=>{
  assert.equal(teamRetryAllowed(400),true);for(const status of [202,401,403,409,503,undefined])assert.equal(teamRetryAllowed(status),false);
});
test('full Team snapshots are compared by attribute with plus/minus lines',()=>{
  const before={info:{id:'1',name:'TEAM'},attributes:[{name:'agents',value:pairs}]};
  const after={info:{id:'1',name:'TEAM'},attributes:[{name:'agents',value:changeAssignment(pairs,0,'task','V')}]};
  const changes=teamChanges(before,after);assert.equal(changes.length,1);assert.equal(changes[0].name,'agents');
  assert.ok(changes[0].rows.some(row=>row.kind==='removed'));assert.ok(changes[0].rows.some(row=>row.kind==='added'));
  assert.deepEqual(teamChanges(before,before),[]);
  assert.ok(teamChanges(before,{info:null,attributes:[]}).some(change=>change.name==='Team 존재'));
});
test('missing post-save snapshot is not represented as success',()=>{
  assert.match(outcomeLabel('BEFORE_SAVED'),/미확인/);assert.match(outcomeLabel('UNCERTAIN'),/확인 필요/);
});
test('cached history markup has a bounded fallback for every controller hook',()=>{
  assert.deepEqual(Object.keys(historyFallbackSelectors).sort(),['close','csrf','refresh','install','message','comparison','entries','prev','page','next'].sort());
  assert.equal(new Set(Object.values(historyFallbackSelectors)).size,10);
  for(const selector of Object.values(historyFallbackSelectors))assert.ok(selector.startsWith('.app-'));
});
