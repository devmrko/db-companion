import test from 'node:test';
import assert from 'node:assert/strict';
import {canApply,jobDraft} from '../../main/resources/static/js/scheduler-management.mjs';
test('job writes require exact confirmation and consent',()=>{
  const p={confirmation:'RUN APP.JOB'};
  assert.equal(canApply(p,p.confirmation,true,false),true);
  for(const args of [[null,p.confirmation,true,false],[p,'other',true,false],[p,p.confirmation,false,false],[p,p.confirmation,true,true]])assert.equal(canApply(...args),false);
});
test('previous settings preserve source text and never invent unsupported schedule',()=>{
  const r={JOB_NAME:'JOB',JOB_ACTION:"BEGIN DBMS_OUTPUT.PUT_LINE('한국어 🧪'); END;",COMMENTS:"quote '",REPEAT_INTERVAL:'FREQ=MINUTELY;INTERVAL=120'};
  assert.deepEqual(jobDraft(r,'APP'),{schema:'APP',action:'UPDATE',name:'JOB',code:r.JOB_ACTION,comments:r.COMMENTS,minutes:120});
  assert.equal(jobDraft({...r,REPEAT_INTERVAL:'FREQ=DAILY'},'APP').minutes,0);
});
