import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {historySetupView,historySetupInstructions} from '../../main/resources/static/js/metadata-history.mjs';

test('known missing privileges have a direct administrator heading and package next step',()=>{
  const view=historySetupView({canManage:false,healthy:true,installed:false,setup:{access:'REQUIRED',nextStep:'AUDIT_UPDATE'}});
  assert.equal(view.hidden,false);assert.equal(view.administrator,true);
  assert.match(view.title,/관리자/);assert.match(view.next,/공통 패키지/);
});
test('an authorized administrator is not told to log in again for a code update',()=>{
  const view=historySetupView({canManage:false,healthy:true,setup:{access:'ALLOWED',nextStep:'AUDIT_UPDATE'}});
  assert.equal(view.administrator,false);assert.match(view.title,/공통 패키지/);
});
test('unconfirmed checks and unknown older responses never claim permissions are present',()=>{
  const view=historySetupView({setup:{access:'UNCONFIRMED',nextStep:'INSPECT'}});
  assert.match(view.title,/확인 필요/);assert.equal(view.hidden,false);
  assert.equal(historySetupView({}).hidden,false);
});
test('collecting with no management rights is not described as collection failure',()=>{
  const view=historySetupView({installed:true,enabled:true,healthy:true,canManage:false,setup:{access:'REQUIRED',nextStep:'NONE'}});
  assert.match(view.next,/켜져 있습니다/);
  assert.equal(view.hidden,true);
  assert.equal(historySetupView({installed:true,healthy:true,canManage:true}).hidden,true);
});
test('copied instructions include the exact selected target but no automatic login or grants',()=>{
  const copy=historySetupInstructions('DEMO_APP','DEMO_VIEW');
  assert.match(copy,/DEMO_APP\.DEMO_VIEW/);assert.match(copy,/DB\/Wallet/);assert.match(copy,/스키마당 한 번/);
  assert.doesNotMatch(copy,/https?:|password|GRANT /i);
});
test('guide is accessible, localized and uses the existing read-only inspector',()=>{
  const html=fs.readFileSync('src/main/resources/templates/fragments/metadata-history.html','utf8');
  assert.match(html,/data-history-setup hidden aria-labelledby/);
  assert.match(html,/data-history-setup-open/);assert.match(html,/readonly hidden rows="6" data-history-copy-value/);
  const messages=JSON.parse(fs.readFileSync('tools/i18n/feature-history-setup-guide.json','utf8'));
  for(const [key,values]of Object.entries(messages)){
    assert.equal(values.length,4);
    for(const [i,value]of values.entries()){
      assert.ok(value.trim());if(i)assert.doesNotMatch(value,/[가-힣]/u);
      assert.deepEqual(value.match(/\{\d+\}/g)||[],values[0].match(/\{\d+\}/g)||[]);
      const bundle=['messages_ko','messages_en','messages_zh_CN','messages_ja'][i];
      assert.ok(fs.readFileSync('src/main/resources/i18n/'+bundle+'.properties','utf8').includes(key+'='+value));
    }
  }
});
