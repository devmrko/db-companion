import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
test('durable discovery has translated consent, saved-run recovery, and text-only receipts',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-pipeline.mjs','utf8');
  for(const path of ['saved','restore','calls','call','recover'])assert.ok(code.includes(`post('/pipeline/${path}'`));
  assert.ok(code.includes('confirmed:true'));assert.ok(code.includes('all:all.input.checked'));assert.ok(!/innerHTML|eval\(/.test(code));
  const messages=JSON.parse(readFileSync('tools/i18n/feature-ontology-discovery-storage.json','utf8'));
  for(const [key,values]of Object.entries(messages)){assert.equal(values.length,4,key);for(const value of values){assert.ok(value.trim());assert.deepEqual(value.match(/\{\d+\}/g),values[0].match(/\{\d+\}/g),key);}}
});
