import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {validQuestion,canPrepare,canSend,modeLabel,canReview,isSqlResponse} from '../../main/resources/static/js/select-ai-test.mjs';
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
