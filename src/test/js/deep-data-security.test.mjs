import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {rowsPage,rowKey,selection,stateMessage,fieldLabel} from '../../main/resources/static/js/deep-data-security.mjs';

test('DDS contains search uses every value and ten rows without changing source',()=>{
  const rows=Array.from({length:23},(_,i)=>({DATA_ROLE:`R${i}`,MAPPED_TO:i===20?'IAM=한글':null}));
  assert.equal(rowsPage(rows).rows.length,10);assert.equal(rowsPage(rows,'',2).from,11);assert.equal(rowsPage(rows,'',999).rows.length,3);
  assert.equal(rowsPage(rows,' iam=한글 ',9).page,1);assert.equal(rowsPage(rows,'IAM=한글').rows[0].DATA_ROLE,'R20');
  assert.equal(rowsPage(rows,'none').total,0);assert.equal(rows.length,23);
});
test('selection distinguishes grant owner and does not interpolate SQL identifiers',()=>{
  assert.deepEqual(selection('grants',{OWNER:'A" B',GRANT_NAME:'R.<script>'}),{kind:'grants',name:'R.<script>',owner:'A" B'});
  assert.deepEqual(selection('roles',{DATA_ROLE:'role'}),{kind:'roles',name:'role'});
  assert.deepEqual(selection('applications',{APPLICATION_NAME:'app'}),{kind:'applications',name:'app'});
  assert.equal(selection('assignments',{}),null);
  assert.notEqual(rowKey({OWNER:'A',GRANT_NAME:'G'}),rowKey({OWNER:'B',GRANT_NAME:'G'}));
});
test('access errors and result limits never become an empty-success message',()=>{
  assert.equal(stateMessage({status:'AVAILABLE'}),'');for(const status of ['ACCESS_REQUIRED','LIMIT','ERROR'])assert.ok(stateMessage({status}));
  assert.equal(fieldLabel('FUTURE_COLUMN'),'FUTURE_COLUMN');assert.equal(fieldLabel('PREDICATE'),'조건식');
});
test('DDS messages cover four languages and preserve interpolation',()=>{
  const messages=JSON.parse(fs.readFileSync('tools/i18n/feature-deep-data-security.json','utf8'));
  for(const [key,values] of Object.entries(messages)){
    assert.equal(values.length,4,key);const args=value=>[...(value.match(/\{\d+\}/g)||[])].sort();
    values.forEach((value,i)=>{assert.ok(value.trim(),key);assert.deepEqual(args(value),args(values[0]),key);if(i)assert.doesNotMatch(value,/[가-힣]/u,key);});
  }
});
test('DDS help explains shared grants and single external mappings in every language',()=>{
  const help=JSON.parse(fs.readFileSync('tools/i18n/feature-deep-data-security.json','utf8'))['dds.help'];
  help.forEach(text=>{assert.ok(text.includes('TO ROLE_A, ROLE_B'));assert.ok(text.includes('→ ROLE_A ↔'));assert.ok(text.includes('→ ROLE_B ↔'));assert.ok(text.includes('\n\n'));});
  assert.match(help[0],/최대 1개/);assert.match(help[1],/at most one/);assert.match(help[1],/cross-table grants/);
});
test('DDS renders raw values as text and loads tabs and details lazily',()=>{
  const code=fs.readFileSync('src/main/resources/static/js/deep-data-security.mjs','utf8');
  assert.doesNotMatch(code,/innerHTML|insertAdjacentHTML|eval\(/);assert.match(code,/e\.textContent=text/);assert.match(code,/cache\.get\(listKind\)/);
  assert.match(code,/listPending\?\.abort\(\)/);assert.match(code,/detailPending\?\.abort\(\)/);assert.doesNotMatch(code,/method:\s*['"]POST/);
});
