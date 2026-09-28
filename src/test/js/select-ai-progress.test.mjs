import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createProgressPoller,elapsedAt,terminal,stageLabel,seconds} from '../../main/resources/static/js/select-ai-progress.mjs';

test('running clocks advance monotonically; completed and failed times freeze',()=>{
  assert.equal(elapsedAt({elapsedMillis:1500,status:'RUNNING'},100,650),2050);
  assert.equal(elapsedAt({elapsedMillis:1500,status:'RUNNING'},100,50),1500);
  for(const status of ['COMPLETE','FAILED'])assert.equal(elapsedAt({elapsedMillis:1500,status},100,9000),1500);
  assert.equal(terminal({status:'RUNNING'}),false);assert.equal(terminal({status:'FAILED'}),true);
  assert.match(seconds(1234),/1\.2/);assert.notEqual(stageLabel('QUERY'),stageLabel('FETCH'));
});
test('progress polling is single flight and disposal prevents stale rendering',async()=>{
  let calls=0,resolve;const pending=new Promise(r=>resolve=r),updates=[],timers=[];
  const poller=createProgressPoller({fetchStatus:()=>{calls++;return pending;},changed:value=>updates.push(value),failed:()=>assert.fail('unexpected'),schedule:fn=>{timers.push(fn);return 1;},cancel:()=>{}});
  poller.watch(true);const one=poller.refresh(),two=poller.refresh();assert.equal(one,two);
  await Promise.resolve();assert.equal(calls,1);poller.dispose();resolve([]);await one;
  assert.deepEqual(updates,[]);await poller.refresh();assert.equal(calls,1);
});
test('a failed progress read reports uncertainty and never repeats execution',async()=>{
  let failures=0,reads=0,scheduled=0;
  const poller=createProgressPoller({fetchStatus:async()=>{reads++;throw Error('offline');},changed:()=>assert.fail('unexpected'),failed:()=>failures++,schedule:()=>++scheduled,cancel:()=>{}});
  await poller.refresh();assert.equal(reads,1);assert.equal(failures,1);assert.equal(scheduled,0);poller.dispose();
});
test('progress markup uses readable scoped layout, accessible status and reduced motion',()=>{
  const html=fs.readFileSync('src/main/resources/templates/ai-test.html','utf8'),css=fs.readFileSync('src/main/resources/static/css/common.css','utf8'),js=fs.readFileSync('src/main/resources/static/js/select-ai-progress.mjs','utf8'),main=fs.readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8');
  for(const key of ['data-test-progress','data-progress-live','data-progress-history','data-progress-live-time'])assert.ok(html.includes(key));
  assert.match(css,/prefers-reduced-motion: reduce/);assert.match(css,/app-test-spinner/);assert.match(css,/minmax\(0, 1fr\)/);
  assert.match(html,/aria-live="off" data-progress-live-time/);assert.doesNotMatch(js,/innerHTML|localStorage|sessionStorage/);
  assert.match(main,/X-AI-Progress-Id/);assert.match(main,/\/ai-test\/progress/);assert.match(main,/AbortSignal.timeout\(5000\)/);
  assert.match(main,/result.code==='ORA-01013'/);assert.match(main,/result.elapsedMillis\/1000/);
  assert.match(main,/root.dataset.executionTimeoutSeconds/);
  assert.doesNotMatch(main,/SQL 실행 제한은 120초/);
  assert.match(html,/data-execution-timeout-seconds=\$\{executionTimeoutSeconds\}/);
  assert.match(html,/aitest.executionTimeout\(\$\{executionTimeoutSeconds\}\)/);
});
