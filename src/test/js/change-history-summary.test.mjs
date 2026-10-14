import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {changeHistoryFields,changeHistorySummary} from '../../main/resources/static/js/change-history-summary.mjs';

test('change provenance is ordered and explicitly labelled without guessing a user',()=>{
  assert.equal(changeHistorySummary({actor:'EDITOR',at:'2026-01-02 12:30:00',content:'COMMENT'}),
    '변경자: EDITOR · 변경 시각: 2026-01-02 12:30:00 (DB) · 변경 내용: COMMENT');
  assert.equal(changeHistorySummary({}), '변경자: 미확인 · 변경 시각: 미확인 · 변경 내용: 미확인');
});
test('snapshots and unverified requests never claim a confirmed change',()=>{
  for(const [role,labels] of [['record',['기록자','기록 시각','기록 내용']],['request',['요청자','요청 시각','요청 내용']]]){
    assert.deepEqual(changeHistoryFields({role}).map(x=>x.label),labels);
    assert.doesNotMatch(changeHistorySummary({role,actor:'READER'}),/변경자/);
  }
});
test('timestamps preserve UTC provenance and unparseable original values',()=>{
  assert.match(changeHistoryFields({at:'2026-01-02T03:04:05Z'})[1].value,/UTC 2026-01-02T03:04:05.000Z/);
  assert.equal(changeHistoryFields({at:'invalidZ'})[1].value,'invalidZ');
});
test('stored values stay text and every history renderer uses the shared formatter',()=>{
  assert.equal(changeHistoryFields({actor:'<img onerror="x">',content:'<script>x</script>'})[0].value,'<img onerror="x">');
  for(const name of ['team-history','profile-history','table-row-history','feedback-tracking','business-glossary-history','metadata-history','history-compare','ontology']){
    const source=readFileSync(`src/main/resources/static/js/${name}.mjs`,'utf8');
    assert.match(source,/from '.\/change-history-summary.mjs'/);
    assert.doesNotMatch(source,/innerHTML/);
  }
});
test('all locales provide common provenance labels',()=>{
  for(const locale of ['','_ko','_en','_ja','_zh_CN']){
    const bundle=readFileSync(`src/main/resources/i18n/messages${locale}.properties`,'utf8');
    for(const key of ['actor','at','content','recorder','recordedAt','recordedContent','requester','requestedAt','requestedContent','unknown'])assert.ok(bundle.includes(`changeHistory.${key}=`));
  }
});
