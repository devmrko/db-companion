import test from 'node:test';
import assert from 'node:assert/strict';
import {historyUpgradeRequest, historyUpgradeView} from '../../main/resources/static/js/metadata-history.mjs';

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

test('shared audit update requires an explicit complete preflight, never the old trigger-only flag', () => {
  assert.deepEqual(historyUpgradeView({auditUpgradeRequired: true, triggerUpgradeAllowed: true}), {hidden: false, disabled: true});
  assert.deepEqual(historyUpgradeView({auditUpgradeRequired: true, codeUpgradeAllowed: false}), {hidden: false, disabled: true});
  assert.deepEqual(historyUpgradeView({auditUpgradeRequired: true, codeUpgradeAllowed: true, trackingEnabled: false}), {hidden: false, disabled: false});
});
