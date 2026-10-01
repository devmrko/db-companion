import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {redactDiagnostic,diagnosticMarkdown,problemStorageView} from '../../main/resources/static/js/problem-questions.mjs';

test('storage states distinguish absent, incompatible, unavailable, and ready',()=>{
  assert.deepEqual([problemStorageView('READY').ready,problemStorageView('READY').canSetup],[true,false]);
  const missing=problemStorageView('MISSING');assert.equal(missing.ready,false);assert.equal(missing.canSetup,true);assert.match(missing.message,/DBC_APP_RECORD/);assert.doesNotMatch(missing.message,/RDF/);
  for(const state of ['MISMATCH','UNAVAILABLE','UNKNOWN',undefined]){const view=problemStorageView(state);assert.equal(view.ready,false);assert.equal(view.canSetup,false);assert.doesNotMatch(view.message,/아직 없습니다/);}
});
test('storage messages use separate localized keys instead of the optional error label',()=>{
  globalThis.DB_COMPANION_MESSAGES={'problemQuestion.storageUnavailable':'Storage unavailable','problemQuestion.error':'Error (optional)'};
  try{assert.equal(problemStorageView('UNAVAILABLE').message,'Storage unavailable');}finally{delete globalThis.DB_COMPANION_MESSAGES;}
});
test('initial list waits for ready storage and save remains disabled until readiness is confirmed',()=>{
  const source=fs.readFileSync('src/main/resources/static/js/problem-questions.mjs','utf8'),html=fs.readFileSync('src/main/resources/templates/ai-problems.html','utf8');
  assert.match(source,/if\(storageReady\)await list\(\)/);assert.doesNotMatch(source,/status\(\);list\(\)/);
  assert.match(source,/if\(!storageReady\)return/);assert.match(html,/data-problem-save disabled/);assert.match(html,/data-problem-storage-setup hidden/);assert.match(html,/data-problem-storage-refresh/);
});

test('diagnostic export redacts credentials and contains only selected attempt fields',()=>{
  const result=diagnosticMarkdown({parent:{status:'RECEIVED',createdAt:'2026-09-23T00:00:00Z',question:'q',description:'password=hidden',expected:'e',expectedSql:''},attempts:[{capturedAt:'2026-09-23T00:00:01Z',availability:'CAPTURED',input:'i',sql:'select 1',error:'',metadata:'wallet: value'}]});
  assert.match(result,/password[=:]\[REDACTED\]/i);assert.match(result,/wallet: \[REDACTED\]/i);assert.doesNotMatch(result,/hidden|value/);
});
test('diagnostic export fails closed for sensitive snapshot lines',()=>{
  const value=redactDiagnostic('credential = long secret\nprivate key: block\nordinary: retained');
  assert.doesNotMatch(value,/long secret|block/);assert.match(value,/ordinary: retained/);assert.match(value,/\[REDACTED\]/);
});
test('diagnostic export removes multiline synthetic PEM and Authorization/Cookie payloads',()=>{
  const secret='SYNTHETIC_SECRET',begin='-----BEGIN '+'PRIVATE KEY-----',end='-----END '+'PRIVATE KEY-----',input=`${begin}\n${secret}\n${end}\nAuthorization: Bearer ${secret}\nCookie: JSESSIONID=${secret}`;
  const value=redactDiagnostic(input);assert.doesNotMatch(value,new RegExp(secret));assert.match(value,/\[REDACTED PEM\]/);assert.match(value,/Authorization: \[REDACTED\]/);assert.match(value,/Cookie: \[REDACTED\]/);
});
test('diagnostic export handles indented and JSON Authorization/Cookie strings',()=>{
  const secret='SYNTHETIC_SECRET',value=redactDiagnostic(` Authorization: Bearer ${secret}\n {"Authorization":"Bearer ${secret}"}\n {"Cookie":"JSESSIONID=${secret}"}\n Cookie: JSESSIONID=${secret}`);
  assert.doesNotMatch(value,new RegExp(secret));assert.match(value,/Authorization: \[REDACTED\]/);assert.match(value,/"Authorization":"\[REDACTED\]"/);assert.match(value,/"Cookie":"\[REDACTED\]"/);assert.match(value,/Cookie: \[REDACTED\]/);
});
test('diagnostic export includes only selected snapshot categories and re-masks preview edits',()=>{
  const detail={parent:{status:'RECEIVED',createdAt:'t',question:'q',description:'d',expected:'e',expectedSql:'expected_sql_must_follow_sql_choice'},attempts:[{capturedAt:'t',snapshotKind:'USER_SELECTED',availability:'CAPTURED',input:'input',sql:'select 1',error:'error',optionSnapshot:{value:'opt',availability:'CAPTURED',checkedAt:'t'},promptSnapshot:{value:'prompt',availability:'RECONSTRUCTED_SHOWPROMPT',checkedAt:'t'},metadataSnapshot:{value:'meta',availability:'CAPTURED',checkedAt:'t'},feedbackSnapshot:{value:'feedback',availability:'CAPTURED',checkedAt:'t'}}]};
  const value=diagnosticMarkdown(detail,{question:false,sql:true,error:false,options:false,prompt:true,metadata:false,feedback:false});assert.match(value,/select 1|prompt/);assert.doesNotMatch(value,/input|error|opt|meta|feedback/);assert.doesNotMatch(redactDiagnostic(`${value}\n Cookie: JSESSIONID=SYNTHETIC_SECRET`),/SYNTHETIC_SECRET/);
  const withoutSql=diagnosticMarkdown(detail,{question:true,sql:false,error:false,options:false,prompt:false,metadata:false,feedback:false});assert.doesNotMatch(withoutSql,/expected_sql_must_follow_sql_choice|select 1/);const sqlWithoutQuestion=diagnosticMarkdown(detail,{question:false,sql:true,error:false,options:false,prompt:false,metadata:false,feedback:false});assert.match(sqlWithoutQuestion,/expected_sql_must_follow_sql_choice/);
});
test('diagnostic export removes uppercase Oracle wallet environment values',()=>{const secret='SYNTHETIC_SECRET';assert.doesNotMatch(redactDiagnostic(`ORACLE_WALLET_PATH=/private/${secret}`),new RegExp(secret));});
test('diagnostic export shares all backend synthetic credential cases',()=>{const secret='SYNTHETIC_SECRET',cases=[`{"password":"${secret}"}`,`{"Authorization":"Bearer ${secret}"}`,`{"Cookie":"JSESSIONID=${secret}"}`,`ORACLE_PASSWORD=${secret}`,`ORACLE_WALLET_PATH=/private/${secret}`,`password='prefix ${secret}'`,`{"api_key":"${secret}"}`];for(const value of cases)assert.doesNotMatch(redactDiagnostic(value),new RegExp(secret));});
test('problem page uses CSRF, text rendering, explicit save and confirmed deletion',()=>{
  const source=fs.readFileSync('src/main/resources/static/js/problem-questions.mjs','utf8'),html=fs.readFileSync('src/main/resources/templates/ai-problems.html','utf8');
  assert.match(source,/assistantPost\(csrf,data\)/);assert.match(source,/textContent/);assert.match(source,/delete-preview/);assert.match(source,/confirmed:true/);assert.doesNotMatch(source,/innerHTML/);assert.match(html,/data-problem-create/);assert.match(html,/data-problem-attempt/);
  for(const name of ['export-dialog','export-preview','export-download','export-question','export-sql','export-error','export-options','export-prompt','export-metadata','export-feedback'])assert.match(html,new RegExp(`data-problem-${name}`));assert.match(source,/redactDiagnostic\(get\('export-preview'\)\.value\)/);
});
test('test result can be explicitly registered without another AI or SQL request',()=>{
  const source=fs.readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8'),html=fs.readFileSync('src/main/resources/templates/ai-test.html','utf8');
  assert.match(source,/problem\/save-preview/);assert.match(source,/selectedSavePayload/);assert.match(source,/includeSnapshots/);assert.match(html,/data-test-save-problem/);assert.match(html,/data-test-problem-dialog/);
  assert.doesNotMatch(source,/problem[^\n]*generate/);
});
