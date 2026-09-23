import {test} from 'node:test';
import assert from 'node:assert/strict';
import {byteLength} from '../../main/resources/static/js/metadata-editor.mjs';
import {formatHistoryJson, historyToggleView} from '../../main/resources/static/js/metadata-history.mjs';
test('editor counts UTF-8 bytes including Korean and emoji', () => {
  assert.equal(byteLength('abc'), 3);
  assert.equal(byteLength('국가'), 6);
  assert.equal(byteLength('🌏'), 4);
  assert.equal(byteLength(''), 0);
});
test('history preserves JSON presence, null values and untrusted text', () => {
  const value = '{"exists":true,"value":"<script>국가</script>"}';
  assert.deepEqual(JSON.parse(formatHistoryJson(value)), JSON.parse(value));
  assert.equal(formatHistoryJson('not json'), 'not json');
  assert.equal(formatHistoryJson(null), 'null');
});
test('history preserves ON state for read-only users and never enables an unconfirmed control', () => {
  const view = historyToggleView({enabled: true, canManage: false, message: '켜짐', triggerOwner: 'INSTALLER', managementMessage: '권한 없음'});
  assert.equal(view.checked, true);
  assert.equal(view.disabled, true);
  assert.equal(view.text, '켜짐 · 관리 계정: INSTALLER · 권한 없음');
  assert.equal(historyToggleView({enabled: false}).disabled, true);
  assert.equal(historyToggleView({enabled: false, canManage: true}).disabled, false);
});
