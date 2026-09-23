import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {reconcileScope,scopeMatches,validScope} from '../../main/resources/static/js/ontology-scope.mjs';
test('loaded scopes show missing names and never expand to all',()=>{
  assert.deepEqual(reconcileScope(['B','MISSING','B'],['A','B','C']),{tables:['B'],missing:['MISSING']});
  assert.deepEqual(reconcileScope([],['A','B']),{tables:[],missing:[]});
});
test('search and selected-only visibility do not alter the actual selected set',()=>{
  const selected=new Set(['A','C']),tables=['A','B','C'].map(name=>({name}));
  assert.deepEqual(scopeMatches(tables,'b',selected),[{name:'B'}]);assert.deepEqual(scopeMatches(tables,'',selected,true),[{name:'A'},{name:'C'}]);assert.deepEqual([...selected],['A','C']);
});
test('scope size must be explicitly between two and five hundred for discovery',()=>{
  assert.equal(validScope(new Set()),false);assert.equal(validScope(new Set(['A'])),false);assert.equal(validScope(new Set(['A','B'])),true);
  assert.equal(validScope(new Set(Array.from({length:501},(_,i)=>i))),false);
});
test('scope writes are explicit; no persistent browser data, HTML injection, or automatic all scope',()=>{
  const ui=readFileSync('src/main/resources/static/js/ontology-scope.mjs','utf8'),pipeline=readFileSync('src/main/resources/static/js/ontology-pipeline.mjs','utf8');
  assert.doesNotMatch(ui,/innerHTML|localStorage|sessionStorage|eval\(/);
  assert.match(ui,/if\(!consent.checked\)return/);assert.match(ui,/missing.length>0\|\|outside.length>0/);
  assert.match(pipeline,/post\('\/pipeline\/preview',\{schema,tables\}\)/);assert.match(pipeline,/if\(budget.reason\)/);
});
test('analysis set labels have four translations and are installed in every bundle',()=>{
  const rows=JSON.parse(readFileSync('tools/i18n/feature-ontology-scope.json','utf8'));
  for(const [key,values]of Object.entries(rows)){assert.equal(values.length,4);for(const [i,v]of values.entries()){
    assert.ok(v.trim());assert.deepEqual(v.match(/\{\d+\}/g),values[0].match(/\{\d+\}/g));
    assert.ok(readFileSync('src/main/resources/i18n/messages'+['_ko','_en','_zh_CN','_ja'][i]+'.properties','utf8').includes(key+'='+v));
  }}
});
