import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {historyInspectionView,historySetupView,historyToggleView} from '../../main/resources/static/js/metadata-history.mjs';

function ready() {
  const triggers=['DBC_MH_B_TEST','DBC_MH_A_TEST'];
  return {schema:'APP',sharedAudit:{version:'V3'},trackingEnabled:true,missingAssets:[],
    objects:['PACKAGE','PACKAGE BODY'].map(type=>({OWNER:'APP',OBJECT_NAME:'DBC_METADATA_AUDIT',OBJECT_TYPE:type,STATUS:'VALID'})),
    assetValidation:[...['PACKAGE','PACKAGE BODY'].map(type=>({owner:'APP',name:'DBC_METADATA_AUDIT',type,compatible:true})),
      ...triggers.map(name=>({owner:'APP',name,type:'TRIGGER',compatible:true}))],
    triggerVersions:triggers.map(name=>({owner:'APP',name,version:'V3'})),
    tableTriggers:triggers.map(name=>({OWNER:'APP',TRIGGER_NAME:name,STATUS:'ENABLED',COMPILE_STATUS:'VALID'}))};
}
test('verified current package and active triggers use brief summaries with no next step',()=>{
  const view=historyInspectionView(ready());
  assert.equal(view.audit,'준비 완료');assert.equal(view.triggers,'이력 수집 중');assert.equal(view.active,true);assert.equal(view.next,'');
  assert.doesNotMatch(JSON.stringify(view),/DBC_MH|v1|v2|v3/);
});
test('completed package does not hide missing table triggers',()=>{
  const data=ready();data.trackingEnabled=false;data.tableTriggers=[];
  data.missingAssets=data.triggerVersions.map(t=>`${t.owner}.${t.name} TRIGGER`);
  data.triggerVersions.forEach(t=>t.version='MISSING');
  const view=historyInspectionView(data);
  assert.equal(view.audit,'준비 완료');assert.equal(view.triggers,'설치 필요');assert.match(view.next,/트리거 설치·켜기/);assert.equal(view.active,false);
});
test('old packages and old triggers retain their own required action',()=>{
  for(const version of ['V1','V2']){
    const data=ready();data.sharedAudit.version=version;data.auditUpgradeRequired=true;
    assert.equal(historyInspectionView(data).audit,'업데이트 필요');assert.match(historyInspectionView(data).next,/공통 패키지/);
  }
  const data=ready();data.triggerUpgradeRequired=true;data.triggerVersions.forEach(t=>t.version='V2');
  assert.equal(historyInspectionView(data).triggers,'업데이트 필요');assert.match(historyInspectionView(data).next,/트리거를 업데이트/);
});
test('code version alone never establishes valid or compatible installation',()=>{
  for(const mutate of [d=>{d.objects=[];},d=>{d.objects[1].STATUS='INVALID';},d=>{d.assetValidation[0].compatible=false;},d=>{delete d.assetValidation;},d=>{d.sharedAudit.version='UNKNOWN';}]){
    const data=ready();mutate(data);assert.equal(historyInspectionView(data).audit,'상태 확인 필요');assert.equal(historyInspectionView(data).active,false);
  }
  assert.equal(historyInspectionView({}).audit,'상태 확인 필요');
});
test('missing source, partial triggers and mismatched enable state require inspection',()=>{
  for(const mutate of [d=>{d.triggerVersions[0].version='MISSING';},d=>{d.tableTriggers.pop();},d=>{d.tableTriggers[0].COMPILE_STATUS='INVALID';},d=>{d.tableTriggers[0].STATUS='DISABLED';},d=>{d.trackingEnabled=false;}]){
    const data=ready();mutate(data);const view=historyInspectionView(data);
    assert.equal(view.triggers,'상태 확인 필요');assert.equal(view.active,false);assert.match(view.next,/검사 상세/);
  }
});
test('verified OFF triggers offer enable guidance without installation details',()=>{
  const data=ready();data.trackingEnabled=false;data.tableTriggers.forEach(t=>t.STATUS='DISABLED');
  const view=historyInspectionView(data);assert.equal(view.triggers,'준비 완료 · 수집 꺼짐');assert.match(view.next,/이력 관리/);assert.equal(view.active,false);
});
test('unhealthy support assets cannot be reported as collecting',()=>{
  for(const mutate of [d=>d.missingAssets.push('APP.DBC_METADATA_HISTORY TABLE'),d=>d.assetValidation.push({compatible:false}),d=>{d.trackingUpgradeRequired=true;}]){
    const data=ready();mutate(data);assert.equal(historyInspectionView(data).active,false);
  }
});
test('healthy collection hides setup even for a read-only account without enabling controls',()=>{
  const data={installed:true,healthy:true,enabled:true,canManage:false,managementMessage:'Cannot change settings',setup:{access:'REQUIRED',nextStep:'NONE'}};
  assert.equal(historySetupView(data).hidden,true);
  assert.deepEqual(historyToggleView(data),{checked:true,disabled:true,text:'이력 수집 중'});
  assert.equal(historySetupView({...data,healthy:false}).hidden,false);
  assert.match(historyToggleView({...data,healthy:false}).text,/Cannot change settings/);
});
test('summary translations exist in all four locales and default bundle',()=>{
  const messages=JSON.parse(fs.readFileSync('tools/i18n/feature-history-summary.json','utf8'));
  for(const [key,values]of Object.entries(messages)){
    assert.equal(values.length,4);
    for(const [i,value]of values.entries()){
      assert.ok(value.trim());if(i)assert.doesNotMatch(value,/[가-힣]/u);
      assert.ok(fs.readFileSync('src/main/resources/i18n/'+['messages_ko','messages_en','messages_zh_CN','messages_ja'][i]+'.properties','utf8').includes(key+'='+value));
    }
    assert.ok(fs.readFileSync('src/main/resources/i18n/messages.properties','utf8').includes(key+'='+values[0]));
  }
});
