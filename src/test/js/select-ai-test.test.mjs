import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {validQuestion,canPrepare,canSend,modeLabel,canReview,isSqlResponse,selectedSavePayload} from '../../main/resources/static/js/select-ai-test.mjs';
test('question boundaries preserve Unicode without clipping',()=>{
  for(const q of ['',null,undefined,'  ','a\0b','x'.repeat(16001)])assert.equal(validQuestion(q),false);
  for(const q of ['한국어 中文 日本語','<script>','x'.repeat(16000)])assert.equal(validQuestion(q),true);
});
test('preparation requires profile question and idle state',()=>{
  assert.equal(canPrepare('PROFILE','질문',false),true);
  assert.equal(canPrepare('','질문',false),false);assert.equal(canPrepare('PROFILE','',false),false);assert.equal(canPrepare('PROFILE','질문',true),false);
});
test('send requires a token explicit consent and idle state',()=>{
  const p={preview:{token:'token'}};assert.equal(canSend(p,true,false),true);
  for(const consent of [false,null,'true',1])assert.equal(canSend(p,consent,false),false);
  assert.equal(canSend(p,true,true),false);assert.equal(canSend(null,true,false),false);
});
test('both modes and safe output paths are present',()=>{
  assert.notEqual(modeLabel('SQL'),modeLabel('CHAT'));
  const source=fs.readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8');
  assert.match(source,/mountSourceViewer\(host/);assert.match(source,/textContent=outcome.text/);
  assert.doesNotMatch(source,/innerHTML|localStorage|sessionStorage|setInterval|setTimeout/);
  assert.match(source,/prepared=null;busy=true;controls\(\);dialog.close\(\)/);
  assert.match(source,/profile.*addEventListener\('change'/);assert.match(source,/dialog.addEventListener\('cancel'/);
  assert.match(source,/post\('generate',\{token,consent:true\}\)/);
  assert.match(source,/post\('execute',\{token,consent:true\}\)/);
  assert.match(source,/post\('execute\/preview',\{resultId:latest.id\}\)/);
  assert.match(source,/rows.slice\(page\*10,page\*10\+10\)/);
});
test('prompt review requires an exact question and selected profile',()=>{
  const prompt={profile:{selection:{name:'PROFILE'}},question:'질문',error:null};
  assert.equal(canReview(prompt,'PROFILE','질문',false),true);
  assert.equal(canReview(prompt,'OTHER','질문',false),false);
  assert.equal(canReview(prompt,'PROFILE','다른 질문',false),false);
  assert.equal(canReview(prompt,'PROFILE','질문',true),false);
  assert.equal(canReview({...prompt,error:'failed'},'PROFILE','질문',false),false);
  assert.equal(canReview(null,'PROFILE','질문',false),false);
});
test('Oracle refusal containing SQL is not exposed as executable SQL',()=>{
  assert.equal(isSqlResponse('Sorry, a valid SELECT statement could not be generated.\nWITH x AS (...) SELECT * FROM x\nException encountered: ORA-20004'),false);
  for(const value of [null,undefined,'SELECTED','WITHIN','Here is SELECT','DELETE FROM t'])assert.equal(isSqlResponse(value),false);
  for(const value of ['SELECT 1 FROM DUAL','WITH x AS (SELECT 1 FROM DUAL) SELECT * FROM x','```sql\nSELECT 1 FROM DUAL\n```'])assert.equal(isSqlResponse(value),true);
});
test('review consumes only server token and preserves generation separately',()=>{
  const source=fs.readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8');
  assert.match(source,/post\('review\/preview',\{promptId:snapshot.id\}\)/);
  assert.match(source,/post\('review',\{token,consent:true\}\)/);
  assert.match(source,/result.action==='PROMPT'\)renderPrompt\(result\);else render\(result\)/);
  assert.match(source,/review-consent'\).checked=false/);
  assert.match(source,/reviewDialog.addEventListener\('cancel'/);
  assert.match(source,/if\(outcome.text\)/); // Rejected SQL text remains available as evidence.
  assert.notEqual(modeLabel('PROMPT'),modeLabel('SQL'));
});
test('A/B selector contract mounts every comparison control through data-test names',()=>{
  const html=fs.readFileSync('src/main/resources/templates/ai-test.html','utf8'),source=fs.readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8');
  for(const name of ['comparison-left','comparison-right','comparison-preview','comparison-left-inspection','comparison-right-inspection','comparison-showprompt-preview','comparison-result','comparison-dialog','comparison-close','comparison-plan','comparison-source','comparison-consent','comparison-left-run','comparison-right-run','comparison-showprompt-dialog','comparison-showprompt-close','comparison-showprompt-source','comparison-showprompt-consent','comparison-showprompt-left-run','comparison-showprompt-right-run','comparison-ai-dialog','comparison-ai-close','comparison-ai-source','comparison-ai-consent','comparison-ai-run']){assert.match(html,new RegExp(`data-test-${name}`));assert.match(source,new RegExp(`get\\('${name}'\\)`));}
});
test('selected result persistence posts the immutable comparison identity and never a mutable latest result',()=>{
  const source=fs.readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8');
  assert.match(source,/comparison\/problem\/save-preview/);assert.match(source,/comparison\/problem/);
  assert.match(source,/\{generation:comparison\.generation,side,resultId:outcome\.id\}/);
  assert.match(source,/problem\/save-preview/);assert.match(source,/resultId:latest\.id/);
  assert.match(source,/kind:'comparison',generation:comparison\.generation,side,resultId:outcome\.id,saveToken:save\.token/);
});
test('selected result payloads contain only the strict endpoint DTO fields',()=>{
  const fields={description:'d',expected:'e',expectedSql:'s',status:'RECEIVED',includeSnapshots:true};
  assert.deepEqual(selectedSavePayload({kind:'single',resultId:'r',saveToken:'t'},'',fields),{resultId:'r',saveToken:'t',...fields});
  assert.deepEqual(selectedSavePayload({kind:'comparison',generation:'g',side:'left',resultId:'r',saveToken:'t'},{id:'p',updatedAt:'2026-09-24T00:00:00Z'},fields),{generation:'g',side:'left',resultId:'r',saveToken:'t',parentId:'p',parentUpdatedAt:'2026-09-24T00:00:00Z',includeSnapshots:true});
});
test('short condition confirmation requires actual values and ignores obsolete submissions',()=>{
  const source=fs.readFileSync(new URL('../../main/resources/static/js/select-ai-test.mjs',import.meta.url),'utf8');
  const template=fs.readFileSync(new URL('../../main/resources/templates/ai-test.html',import.meta.url),'utf8');
  assert.match(template,/data-test-condition-choice value="period"/);
  assert.match(template,/data-test-condition-value="period"/);
  assert.match(template,/data-test-condition-error/);
  assert.match(source,/if\(busy\|\|remoteRunning\)return;/);
  assert.match(source,/key:`free-\$\{index\+1\}`/);
  assert.match(source,/formFingerprint!==currentFingerprint/);
  assert.match(source,/post\('cancel',\{token:value\.preview\.token\}\)/);
  assert.doesNotMatch(source,/map\(value=>\(\{topic:'자유 입력 조건',value\}\)\)/);
});
