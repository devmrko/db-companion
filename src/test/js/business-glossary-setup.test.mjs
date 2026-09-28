import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {textSetupPresentation,renderTextSetup} from '../../main/resources/static/js/business-glossary-setup.mjs';

test('an existing table does not imply Oracle Text is configured',()=>{
  const missing=textSetupPresentation({table:'READY',text:'MISSING'});
  assert.equal(missing.showSetup,true);assert.match(missing.text,/미구성/);assert.match(missing.text,/정확·별칭 검색은 사용 가능/);
  const ready=textSetupPresentation({table:'READY',text:'READY'});
  assert.equal(ready.showSetup,false);assert.match(ready.text,/구성 완료/);
});
test('loading, missing table, partial setup and errors never offer unsafe reinstallation',()=>{
  for(const status of [null,{table:'MISSING',text:'UNAVAILABLE'},{table:'MISMATCH',text:'READY'},{table:'READY',text:'CHECK_REQUIRED'},{table:'READY',text:'UNAVAILABLE'}]){
    const view=textSetupPresentation(status);assert.equal(view.showSetup,false);assert.doesNotMatch(view.text,/구성 완료/);
  }
});
test('refresh transitions clear stale readiness and restore setup only for confirmed missing objects',()=>{
  const nodes={'[data-glossary-text-status]':{textContent:''},'[data-glossary-text-setup]':{hidden:false}};
  const root={querySelector:key=>nodes[key]};
  renderTextSetup(root,{table:'READY',text:'READY'});
  assert.equal(nodes['[data-glossary-text-setup]'].hidden,true);assert.match(nodes['[data-glossary-text-status]'].textContent,/구성 완료/);
  renderTextSetup(root,null);assert.doesNotMatch(nodes['[data-glossary-text-status]'].textContent,/구성 완료/);
  renderTextSetup(root,{table:'READY',text:'MISSING'});assert.equal(nodes['[data-glossary-text-setup]'].hidden,false);
  renderTextSetup(root,{table:'READY',text:'CHECK_REQUIRED'});assert.equal(nodes['[data-glossary-text-setup]'].hidden,true);
});
test('manager refresh resets readiness and setup remains a separate explicit preview',()=>{
  const manager=readFileSync('src/main/resources/static/js/business-glossary.mjs','utf8');
  assert.match(manager,/status=null;renderTextSetup\(root,status\);lock\(true\)/);
  assert.match(manager,/get\('text-setup'\)\.addEventListener\('click',\(\)=>prepare\('TEXT'\)\)/);
  assert.match(manager,/renderTextSetup\(root,status\)/);
  const html=readFileSync('src/main/resources/templates/business-glossary.html','utf8');
  assert.match(html,/data-glossary-text-setup hidden disabled/);
  assert.match(html,/data-glossary-text-status[^>]*role="status"[^>]*aria-live="polite"/);
  for(const locale of ['','_ko','_en','_ja','_zh_CN']){
    const messages=readFileSync('src/main/resources/i18n/messages'+locale+'.properties','utf8');
    for(const key of ['checking','tableRequired','ready','missing','unavailable'])assert.equal((messages.match(new RegExp('^businessGlossary\\.textState\\.'+key+'=.+$','gm'))||[]).length,1);
  }
});
