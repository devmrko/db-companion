import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {historyUpgradeRequest, historyUpgradeView, historyAuditUpgradeView, historyCodeLabel, canRestoreHistory} from '../../main/resources/static/js/metadata-history.mjs';

test('upgrade payload supplies the required primitive boolean without enabling history', () => {
  assert.equal(JSON.stringify(historyUpgradeRequest('DEMO_APP', 'DEMO_COUNTRY')),
    '{"schema":"DEMO_APP","table":"DEMO_COUNTRY","enabled":false}');
});

test('trigger update is only offered for an explicitly allowed OFF legacy installation', () => {
  assert.deepEqual(historyUpgradeView({}), {hidden: true, disabled: true});
  assert.deepEqual(historyUpgradeView({triggerUpgradeRequired: true}), {hidden: false, disabled: true});
  assert.deepEqual(historyUpgradeView({triggerUpgradeRequired: true, codeUpgradeAllowed: true, trackingEnabled: false}),
    {hidden: false, disabled: false});
  assert.deepEqual(historyUpgradeView({triggerUpgradeRequired: true, codeUpgradeAllowed: true, trackingEnabled: true}),
    {hidden: false, disabled: true});
});

test('shared audit update has an independent preflight even with missing table triggers', () => {
  assert.deepEqual(historyAuditUpgradeView({}), {hidden: true, disabled: true});
  assert.deepEqual(historyAuditUpgradeView({sharedAudit:{upgradeRequired:true}}), {hidden:false, disabled:true});
  assert.deepEqual(historyAuditUpgradeView({sharedAudit:{upgradeRequired:true,allowed:true},codeUpgradeAllowed:false,missingAssets:['APP.T TRIGGER']}), {hidden:false, disabled:false});
  assert.deepEqual(historyAuditUpgradeView({sharedAudit:{upgradeRequired:false,allowed:true}}), {hidden:true, disabled:true});
  assert.deepEqual(historyUpgradeView({auditUpgradeRequired:true,codeUpgradeAllowed:true}),{hidden:true,disabled:true});
  assert.deepEqual(historyUpgradeView({auditUpgradeRequired:true,triggerUpgradeRequired:true,codeUpgradeAllowed:true}),{hidden:false,disabled:true});
});
test('all supported and unidentified code versions have distinct guidance',()=>{
  for(const v of ['V1','V2','V3','MISSING','UNKNOWN']) assert.ok(historyCodeLabel(v));
  assert.match(historyCodeLabel('V1'),/v1/);
  assert.match(historyCodeLabel('V2'),/Materialized View/);
  assert.match(historyCodeLabel('V2','trigger'),/컬럼/);
  assert.match(historyCodeLabel('V3'),/View/);
  assert.equal(historyCodeLabel('UNSUPPORTED'),historyCodeLabel('UNKNOWN'));
});
test('version guidance has four complete translations and distinct explicit actions',()=>{
  const messages=JSON.parse(fs.readFileSync('tools/i18n/feature-history-code-upgrade.json','utf8'));
  for(const [key,values]of Object.entries(messages)){
    assert.equal(values.length,4,key);
    const args=s=>[...new Set(s.match(/\{\d+\}/g)||[])].sort();
    for(const [i,value]of values.entries()){
      assert.ok(value.trim());assert.deepEqual(args(value),args(values[0]));if(i)assert.doesNotMatch(value,/[가-힣]/u,key);
    }
  }
  const template=fs.readFileSync('src/main/resources/templates/fragments/metadata-history.html','utf8');
  assert.match(template,/data-history-audit-upgrade hidden disabled/);
  assert.match(template,/data-history-upgrade hidden disabled/);
});
test('view history restores comments but never table-only annotations', () => {
  assert.equal(canRestoreHistory('COMMENT', 'false'), true);
  assert.equal(canRestoreHistory('ANNOTATION', 'false'), false);
  assert.equal(canRestoreHistory('ANNOTATION', 'true'), true);
  assert.equal(canRestoreHistory('UNKNOWN', 'true'), false);
});
