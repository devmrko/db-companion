import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {schedulerPage,jobStates,schedulerMessage,codeSections,historyState} from '../../main/resources/static/js/scheduler.mjs';
import {sourceLines} from '../../main/resources/static/js/source-viewer.mjs';

test('jobs use ten-row pages, contains search and explicit state filtering',()=>{
  const items=Array.from({length:23},(_,i)=>({JOB_NAME:`ETL_${i}`,COMMENTS:'한글 warehouse',STATE:i%2?'SCHEDULED':'DISABLED',JOB_TYPE:'PLSQL_BLOCK',ENABLED:i%2?'TRUE':'FALSE'}));
  assert.equal(schedulerPage(items,'','',1).items.length,10);assert.equal(schedulerPage(items,'','',2).from,11);assert.equal(schedulerPage(items,'','',99).items.length,3);
  assert.equal(schedulerPage(items,'WAREHOUSE','',1).total,23);assert.equal(schedulerPage(items,'한글','DISABLED',1).total,12);
  assert.equal(schedulerPage(items,'not found','',1).total,0);assert.equal(schedulerPage(items,'plsql_block','',1).total,23);
  assert.deepEqual(jobStates(items),['DISABLED','SCHEDULED']);
});
test('history cursors preserve large IDs without numeric conversion and support backtracking',()=>{
  const state=historyState();assert.equal(state.current(),'');assert.equal(state.canBack(),false);
  state.back();state.next('999999999999999999999999999999');assert.equal(state.current(),'999999999999999999999999999999');assert.equal(state.number(),2);
  state.next('30');state.back();assert.equal(state.number(),2);state.next('20');assert.equal(state.current(),'20');state.next('');assert.equal(state.number(),3);
});
test('PLSQL source is preserved; chain and procedure names are not invented as blocks',()=>{
  const text="begin\n  null; -- 한글 <script>\nend;\n";const sections=codeSections({action:{type:'PLSQL_BLOCK',text}});
  assert.equal(sections[0].text,text);assert.equal(sourceLines(text).flat().map(part=>part.text).join(''),text.replaceAll('\n',''));
  assert.deepEqual(codeSections({action:{type:'CHAIN',text:'CHAIN_NAME'}}),[]);assert.deepEqual(codeSections({action:{type:'STORED_PROCEDURE',text:'PKG.RUN'}}),[]);
});
test('query errors retain view and Oracle code without claiming empty success',()=>{
  assert.match(schedulerMessage({status:'ACCESS_REQUIRED',source:'SYS.ALL_SCHEDULER_JOBS',error:'ORA-00942'}),/ACCESS_REQUIRED.*SYS.ALL_SCHEDULER_JOBS.*ORA-00942/);
  assert.equal(schedulerMessage({status:'AVAILABLE',source:'SYS.USER_SCHEDULER_JOBS'}),'SYS.USER_SCHEDULER_JOBS');
});
test('screen loads code and logs only on selection, invalidates stale requests and has no execution API',()=>{
  const source=readFileSync('src/main/resources/static/js/scheduler.mjs','utf8');
  assert.match(source,/tab==='settings'\?'\/detail':tab==='code'\?'\/code':'\/runs'/);
  assert.match(source,/if\(!request.current\(\)\)return/);assert.match(source,/tabGate.cancel\(\);logGate.cancel\(\)/);
  assert.match(source,/await api\('\/list',\{refresh\}/);assert.match(source,/if\(data.status==='AVAILABLE'&&items.some/);
  assert.match(source,/mountSourceViewer/);assert.match(source,/history.current\(\)/);
  assert.doesNotMatch(source,/innerHTML|insertAdjacentHTML|method:\s*['"]POST|localStorage|sessionStorage|setInterval|RUN_JOB|STOP_JOB/);
});
