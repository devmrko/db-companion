import {test} from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';
import {auditControls,rlsFields,validAuditSettings,auditSucceeded} from '../../main/resources/static/js/audit-history.mjs';
test('length-delimited security predicates preserve embedded quotes, Unicode and multiple policies',()=>{
 const field=(name,value)=>name+'=['+new TextEncoder().encode(value).length+"]'"+value+"'";
 const raw='(('+field('POLICY_TYPE','DATA GRANT')+'),('+field('PREDICATE',"지역 = '한국'")+'));(('+field('POLICY_TYPE','VPD')+'),('+field('PREDICATE',"x = 'EU'")+'));';
 assert.deepEqual(rlsFields(raw).map(v=>v.value),['DATA GRANT',"지역 = '한국'",'VPD',"x = 'EU'"]);
 assert.deepEqual(rlsFields("((PREDICATE=[999]'broken'));"),[]);assert.deepEqual(rlsFields(null),[]);
 assert.deepEqual(rlsFields("((PREDICATE=[0]''));"),[{name:'PREDICATE',value:''}]);
});
test('controls honor readiness, source access, running jobs and defaults',()=>{
 assert.equal(auditControls(null).INSTALL,undefined);
 const s={store:'READY',packageState:'READY',jobState:'READY',enabled:true,sourceState:'READY',job:{STATE:'SCHEDULED'}};
 assert.equal(auditControls(s).RUN,true);assert.equal(auditControls(s).PAUSE,true);
 assert.equal(auditControls({...s,sourceState:'UNAVAILABLE'}).RUN,false);assert.equal(auditControls({...s,job:{STATE:'RUNNING'}}).CONFIGURE,false);
 assert.equal(auditControls(s,true).RUN,false);assert.equal(validAuditSettings(60,0),true);assert.equal(validAuditSettings(60,-1),false);assert.equal(validAuditSettings(4,0),false);
 for(const code of [null,undefined,'',1,1017])assert.equal(auditSucceeded(code),false);
 for(const code of [0,'0'])assert.equal(auditSucceeded(code),true);
});
test('menu, DDS shortcut, safe rendering and deletion confirmation are wired',()=>{
 const read=n=>fs.readFileSync('src/main/resources/'+n,'utf8');
 assert.match(read('templates/fragments/common.html'),/@\{\/db\/audit\}/);assert.match(read('templates/deep-data-security.html'),/@\{\/db\/audit\}/);
 const js=read('static/js/audit-history.mjs');assert.doesNotMatch(js,/innerHTML|localStorage|eval\(/);assert.match(js,/deleteWarning/);assert.match(js,/consent:true/);
 assert.match(read('templates/audit-history.html'),/value="0" data-au-retention/);
});
