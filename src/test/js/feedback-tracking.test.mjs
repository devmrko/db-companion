import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {trackingActions,trackingRequest,feedbackChanges} from '../../main/resources/static/js/feedback-tracking.mjs';
import {historyInstallView,historyToggleView} from '../../main/resources/static/js/metadata-history.mjs';

test('tracking never enables unknown missing denied or conflicting actions',()=>{
  for(const state of [null,undefined,{},{code:'NO_SOURCE'},{code:'CONFLICT'},{code:'NOT_INSTALLED',archive:'UPGRADE'}])
    assert.ok(Object.values(trackingActions(state)).every(action=>!action.enabled));
  assert.deepEqual(trackingActions({code:'NOT_INSTALLED',archive:'READY',canInstall:true}).install,{visible:true,enabled:true});
  assert.equal(trackingActions({code:'INVALID',canDisable:true}).disable.enabled,true);
});
test('write request binds both current profile and table identities',()=>{
  const state={code:'NOT_INSTALLED',canInstall:true,profileId:'303',tableId:123};
  assert.deepEqual(trackingRequest('S','P',state,'install'),{schema:'S',profile:'P',profileId:'303',tableId:123,action:'install'});
  assert.throws(()=>trackingRequest('S','P',null,'install'));assert.throws(()=>trackingRequest('S','P',state,'disable'));
});
test('I U D show full changed values including long Unicode and absence',()=>{
  const before=JSON.stringify({content:'Q',attributes:{response:'한글😀\n'.repeat(4000),feedback_content:'before'}});
  const after=JSON.stringify({content:'Q',attributes:{response:'한글😀\n'.repeat(4000)+'...',feedback_content:'after'}});
  assert.equal(feedbackChanges(before,after).length,2);
  assert.equal(feedbackChanges(null,before).length,3);assert.equal(feedbackChanges(before,null).length,3);
  assert.deepEqual(feedbackChanges(before,before),[]);assert.throws(()=>feedbackChanges('{',after));
});
test('metadata install requires explicit permission and only missing installation exposes it',()=>{
  assert.deepEqual(historyInstallView({installed:false,canManage:false}),{hidden:false,disabled:true});
  assert.deepEqual(historyInstallView({installed:false,canManage:true}),{hidden:false,disabled:false});
  assert.equal(historyInstallView({installed:true,canManage:true}).hidden,true);
});
test('metadata initial load uses session cache and only button opts into refresh',()=>{
  const code=readFileSync('src/main/resources/static/js/metadata-history.mjs','utf8');
  assert.ok(code.includes('const loadState = async (force = false)'));
  assert.ok(code.includes("if(force)address.searchParams.set('refresh','true')"));
  assert.ok(code.includes("refresh.addEventListener('click', () => loadState(true))"));
  assert.ok(code.includes('loadState();'));
});
test('recorded installer without management visibility is not labelled missing or offered install',()=>{
  const state={installed:false,enabled:false,canManage:false,triggerOwner:'ADMIN',message:'트리거 설치 필요',managementMessage:'ORA-00942: SYS.DBA_SYS_PRIVS'};
  assert.deepEqual(historyInstallView(state),{hidden:true,disabled:true});
  assert.ok(historyToggleView(state).text.startsWith('트리거 설치 상태 확인 불가'));
  assert.ok(historyToggleView(state).text.includes('ORA-00942'));
  assert.equal(historyInstallView({...state,canManage:true}).hidden,false);
});
test('feedback controls are text-only, CSRF protected and do not automatically retry failed writes',()=>{
  const code=readFileSync('src/main/resources/static/js/feedback-tracking.mjs','utf8');
  assert.ok(code.includes('[csrf.dataset.csrfHeader]:csrf.value'));assert.ok(code.includes('if(!confirm(prompt))return'));
  assert.match(code,/state=null;find\('state'\)\.textContent=t\('ui\.a8d53a72e8a4', "작업 결과 확인 필요"\)/);
  assert.ok(!code.includes('innerHTML'));assert.ok(!code.includes('setInterval'));
});
