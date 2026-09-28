import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {snapshotFields,historyStoragePresentation} from '../../main/resources/static/js/business-glossary-history.mjs';
test('ready history clearly reports app recording and hides table creation',()=>{
  const ready=historyStoragePresentation('READY','READY');
  assert.match(ready.text,/앱 변경 이력 기록 중/);
  assert.equal(ready.showSetup,false);assert.equal(ready.showGuide,true);
  const noGlossary=historyStoragePresentation('READY','MISSING');
  assert.doesNotMatch(noGlossary.text,/기록 중/);assert.match(noGlossary.text,/용어 사전 준비 후/);
  assert.equal(noGlossary.showSetup,false);
});
test('only a missing history store offers creation, not loading or errors',()=>{
  const missing=historyStoragePresentation('MISSING','READY');
  assert.match(missing.text,/이력 저장 테이블을 생성/);
  assert.equal(missing.showSetup,true);assert.equal(missing.showGuide,false);
  for(const state of [null,undefined,'INCOMPATIBLE','UNAVAILABLE']){
    const view=historyStoragePresentation(state,'READY');
    assert.equal(view.showSetup,false);assert.equal(view.showGuide,false);
    assert.doesNotMatch(view.text,/기록 중|준비 완료/);
  }
});
test('history snapshots preserve the whole term and SQL without executing or interpreting it',()=>{
  assert.deepEqual(snapshotFields(null),[]);
  const values=snapshotFields({term:'<script>name</script>',aliases:['a','b'],definition:'definition',criteria:'SELECT 1 FROM DUAL',enabled:false}).map(x=>x[1]);
  assert.deepEqual(values,['<script>name</script>','a\nb','definition','SELECT 1 FROM DUAL','비활성']);
});
test('history UI has owner scoped reads, explicit storage preparation and safe text rendering',()=>{
  const base='src/main/resources/',js=fs.readFileSync(base+'static/js/business-glossary-history.mjs','utf8'),manager=fs.readFileSync(base+'static/js/business-glossary.mjs','utf8'),html=fs.readFileSync(base+'templates/business-glossary.html','utf8');
  assert.doesNotMatch(js,/innerHTML|eval\(|localStorage|sessionStorage/);
  assert.match(js,/textContent/);assert.match(manager,/historyState!=='READY'/);
  assert.match(manager,/\/business-glossary\/history\?/);assert.match(manager,/prepare\('HISTORY'\)/);
  for(const key of ['history-dialog','history-entries','history-close','history-more','history-error','history-empty'])assert.ok(html.includes('data-glossary-'+key));
  assert.match(manager,/get\('history-setup'\)\.hidden=!view\.showSetup/);
  assert.match(manager,/get\('history-guide'\)\.hidden=!view\.showGuide/);
  assert.match(html,/data-glossary-history-setup hidden disabled/);
  for(const key of ['recording','ready','missing','checking','unavailable','guide']){
    for(const locale of ['','_ko','_en','_ja','_zh_CN'])assert.ok(fs.readFileSync(base+'i18n/messages'+locale+'.properties','utf8').includes('businessGlossary.history.'+key+'='));
  }
  for(const locale of ['','_ko','_en','_ja','_zh_CN'])assert.match(fs.readFileSync(base+'i18n/messages'+locale+'.properties','utf8'),/^businessGlossary\.history\.scope=.+$/m);
  assert.match(fs.readFileSync(base+'static/css/common.css','utf8'),/\[data-glossary-history-error\]:empty,[\s\S]*?display: none/);
});
