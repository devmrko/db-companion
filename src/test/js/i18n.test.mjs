import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {t} from '../../main/resources/static/js/i18n.mjs';

test('translation inserts arguments once and never changes DB values or interprets markup',()=>{
  globalThis.DB_COMPANION_MESSAGES={sample:'Value: {0} / {1}'};
  assert.equal(t('sample','fallback','한국어 ${model} {1} <script>','日本語'), 'Value: 한국어 ${model} {1} <script> / 日本語');
  assert.equal(t('missing','원문 {0}','x'),'원문 x');
  delete globalThis.DB_COMPANION_MESSAGES;
});
test('all reviewed translations preserve placeholders and have four-language coverage',()=>{
  const rows=JSON.parse(fs.readFileSync('tools/i18n/catalog.json','utf8'));
  const translations=Object.assign({},...fs.readdirSync('tools/i18n').filter(f=>/^\d.*\.json$/.test(f)).map(f=>JSON.parse(fs.readFileSync('tools/i18n/'+f,'utf8'))));
  const placeholders=s=>[...new Set(s.match(/\{(?:\d+|query)\}/g)||[])].sort();
  const missing=[];
  rows.forEach((row,i)=>{
    const variants=translations[i];
    if(!variants){missing.push(i);return;}
    assert.equal(variants.length,3,'languages for '+i);
    variants.forEach(value=>{assert.ok(value.trim(),'nonblank '+i);assert.deepEqual(placeholders(value),placeholders(row.ko),'placeholders '+i);assert.doesNotMatch(value,/[가-힣]/u,'untranslated '+i);});
  });
  assert.deepEqual(missing,[],'Untranslated UI message indexes');
});
test('OCI catalogue messages have four complete languages and matching arguments',()=>{
  const messages=JSON.parse(fs.readFileSync('tools/i18n/vector-oci.json','utf8'));
  for(const [key,values]of Object.entries(messages)){
    assert.equal(values.length,4,key);
    const args=s=>[...new Set(s.match(/\{\d+\}/g)||[])].sort();
    for(const [index,value]of values.entries()){
      assert.ok(value.trim(),key);assert.deepEqual(args(value),args(values[0]),key);
      if(index)assert.doesNotMatch(value,/[가-힣]/u,key);
    }
  }
});
