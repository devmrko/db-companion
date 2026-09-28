import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const bundles=['messages.properties','messages_ko.properties','messages_en.properties','messages_ja.properties','messages_zh_CN.properties'];
const keys=['aitest.condition.open','aitest.condition.period','aitest.condition.freeItem','aitest.condition.transmission','aitest.comparison.note','aitest.comparison.saveResult','aitest.batch.plan','aitest.batch.saved','problemQuestion.saved',
  'aitest.comparison.evidenceUnsupported','aitest.comparison.differencesOnly','aitest.comparison.inspection','aitest.comparison.profile','aitest.comparison.sql','aitest.comparison.error','aitest.comparison.time','aitest.comparison.options','aitest.comparison.metadata','aitest.comparison.feedback','aitest.comparison.prompt','aitest.comparison.empty','aitest.comparison.same','aitest.comparison.different','aitest.comparison.observed','aitest.comparison.unconfirmed','aitest.comparison.noData',
  'aitest.batch.profileUnknown','aitest.batch.consentRequired','aitest.batch.planInvalid','aitest.batch.responseMismatch','aitest.batch.planChanged','aitest.batch.selection','aitest.batch.noPlan','aitest.batch.pickSave','aitest.batch.maximum','aitest.batch.profileChanged','aitest.batch.profilesLoaded','aitest.batch.prepareReady','aitest.batch.restored','aitest.batch.noSessionPlan','aitest.batch.stopped','aitest.batch.finished','aitest.batch.noRetryCheck','aitest.batch.cancelUnknown','aitest.batch.cancelled','aitest.batch.screenStopped','aitest.batch.saveUnknown','problemQuestion.new','problemQuestion.more'];
const text=file=>readFileSync(`src/main/resources/i18n/${file}`,'utf8');

test('AI workbench messages are supplied by every supported locale bundle',()=>{
  for(const bundle of bundles){const value=text(bundle);for(const key of keys)assert.match(value,new RegExp(`^${key.replaceAll('.','\\.') }=.+$`,'m'),`${bundle}: ${key}`);}
});

test('condition field identity is stable while its display label is localized',()=>{
  const html=readFileSync('src/main/resources/templates/ai-test.html','utf8');
  for(const key of ['period','aggregation','scope','ordering']){
    assert.match(html,new RegExp(`data-test-condition-choice value="${key}"`));
    assert.match(html,new RegExp(`data-test-condition-value="${key}"`));
  }
  assert.doesNotMatch(html,/value="기간 또는 기준 날짜"/);
  assert.match(html,/<span th:text="#\{aitest\.condition\.period\}">/);
  assert.match(html,/<textarea class="form-control" maxlength="1000" data-test-condition-question><\/textarea>/);
});
