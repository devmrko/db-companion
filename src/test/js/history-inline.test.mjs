import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {historyToggleView,historyStateDetails} from '../../main/resources/static/js/metadata-history.mjs';

const missing={installed:false,enabled:false,healthy:true,canManage:false,message:'트리거 설치 필요',
  managementMessage:'ADMINISTER DATABASE TRIGGER',setup:{access:'REQUIRED',nextStep:'INSTALL'}};
test('missing installation and privilege use a single concise status without losing the reason',()=>{
  assert.deepEqual(historyToggleView(missing),{checked:false,disabled:true,text:'이력 수집 미설정 · 설치 권한 필요'});
  assert.match(historyStateDetails(missing),/ADMINISTER DATABASE TRIGGER/);
});
test('authorized installation is not labelled as requiring more privileges',()=>{
  const view=historyToggleView({...missing,canManage:true,setup:{access:'ALLOWED',nextStep:'INSTALL'}});
  assert.equal(view.text,'이력 수집 미설정');assert.equal(view.disabled,false);
});
test('update and enable states retain their own action rather than claiming not installed',()=>{
  for(const [step,word]of [['AUDIT_UPDATE','패키지'],['TRIGGER_UPDATE','트리거'],['ENABLE','꺼져']]){
    const view=historyToggleView({...missing,setup:{access:'REQUIRED',nextStep:step}});
    assert.ok(view.text.includes(word));assert.match(view.text,/관리 권한 필요/);assert.doesNotMatch(view.text,/미설정/);
  }
});
test('unconfirmed or unhealthy results keep actual error text visible',()=>{
  for(const change of [{healthy:false},{setup:{access:'UNCONFIRMED',nextStep:'INSPECT'}}, {setup:null}]){
    const view=historyToggleView({...missing,...change,message:'ORA-00942'});
    assert.match(view.text,/ORA-00942/);assert.match(view.text,/ADMINISTER DATABASE TRIGGER/);
  }
});
test('a recorded installer with insufficient visibility is not described as a fresh missing installation',()=>{
  const view=historyToggleView({...missing,triggerOwner:'INSTALLER'});
  assert.match(view.text,/트리거 설치 상태 확인 불가/);assert.doesNotMatch(view.text,/이력 수집 미설정/);
});
test('setup is a native closed disclosure and never auto-expands for missing privileges',()=>{
  const html=fs.readFileSync('src/main/resources/templates/fragments/metadata-history.html','utf8');
  assert.match(html,/<details class="app-history-setup"[^>]*data-history-setup hidden/);
  assert.doesNotMatch(html,/<details class="app-history-setup"[^>]*\sopen\b/);
  assert.match(html,/<summary th:text="#\{history.inline.details\}">/);
  assert.match(html,/data-history-setup-reason/);
  const js=fs.readFileSync('src/main/resources/static/js/metadata-history.mjs','utf8');
  assert.doesNotMatch(js,/\.open\s*=\s*guide.administrator/);
  assert.match(js,/\[data-history-setup-reason\]'\)\.textContent=historyStateDetails\(data\)/);
});
test('compact setup labels are translated in four locales and the default bundle',()=>{
  const messages=JSON.parse(fs.readFileSync('tools/i18n/feature-history-inline.json','utf8'));
  for(const [key,values]of Object.entries(messages)){
    assert.equal(values.length,4);
    for(const [i,value]of values.entries()){
      assert.ok(value.trim());if(i)assert.doesNotMatch(value,/[가-힣]/u);
      assert.ok(fs.readFileSync('src/main/resources/i18n/'+['messages_ko','messages_en','messages_zh_CN','messages_ja'][i]+'.properties','utf8').includes(key+'='+value));
    }
    assert.ok(fs.readFileSync('src/main/resources/i18n/messages.properties','utf8').includes(key+'='+values[0]));
  }
});
