import test from 'node:test';
import assert from 'node:assert/strict';
import {embeddingOptions,searchInput,modelExamples} from '../../main/resources/static/js/vector-search.mjs';
import fs from 'node:fs';
import {pageOf} from '../../main/resources/static/js/table-list.mjs';
const selection={schema:'APP',table:'ANY_TABLE',vector:'V',content:'TEXT'};
const values={text:'질문',provider:'database',modelOwner:'APP',model:'MODEL',credential:'',region:'',inputType:'',metric:'COSINE',k:10,externalConsent:false};
test('embedding method toggles only its relevant fields',()=>{
  assert.deepEqual(embeddingOptions('database'),{database:true,remote:false,oci:false,input:false});
  assert.deepEqual(embeddingOptions('ocigenai'),{database:false,remote:true,oci:true,input:true});
  assert.equal(embeddingOptions('openai').input,false);assert.equal(embeddingOptions('').remote,false);
});
test('generic selection and search text are preserved without invented model defaults',()=>{
  assert.deepEqual(searchInput(selection,values),{selection,...values});
  assert.throws(()=>searchInput(selection,{...values,provider:''}),/임베딩 방식/);
  assert.throws(()=>searchInput(selection,{...values,model:''}),/임베딩 모델/);
});
test('remote search needs credential consent and OCI region',()=>{
  const remote={...values,provider:'ocigenai',model:'cohere.embed-multilingual-v3.0',credential:'C',region:'us-chicago-1',inputType:'search_query',externalConsent:true};
  assert.equal(searchInput(selection,remote).inputType,'search_query');
  assert.throws(()=>searchInput(selection,{...remote,externalConsent:false}),/전송/);
  assert.throws(()=>searchInput(selection,{...remote,region:'example.com/path'}),/리전/);
  assert.equal(searchInput(selection,{...remote,provider:'openai'}).inputType,'');
});
test('K and UTF-8 input length are bounded before submitting',()=>{
  for(const k of [0,101,NaN,1.5])assert.throws(()=>searchInput(selection,{...values,k}),/K/);
  assert.throws(()=>searchInput(selection,{...values,text:'가'.repeat(1334)}),/4,000/);
  assert.throws(()=>searchInput(selection,{...values,text:' '}),/검색어/);
});
test('table column-name filter and ten-row pages reuse common behavior',()=>{
  const rows=Array.from({length:21},(_,i)=>({name:`T${i}`,description:'VEC_COL'}));
  assert.equal(pageOf(rows,'vec',2).items.length,10);assert.equal(pageOf(rows,'vec',3).items.length,1);
  assert.equal(pageOf(rows,'missing',1).total,0);
});
test('public picker has no live catalogue form or OCI list requests',()=>{
  const template=fs.readFileSync('src/main/resources/templates/vector-search.html','utf8');
  const code=fs.readFileSync('src/main/resources/static/js/vector-search.mjs','utf8');
  assert.doesNotMatch(template,/app-oci-catalog|data-oci-(credential|compartment|load|refresh|model|more)|Compartment/);
  assert.match(template,/data-credential/);assert.match(template,/data-public-model/);assert.match(template,/data-region-select/);
  assert.doesNotMatch(code,/ociCatalogueQuery|mergeOciModels|loadOci|resetOci|ociSelected|\/oci-models|innerHTML/);
});
test('cached legacy template is removed before option initialization without ending the session',()=>{
  const code=fs.readFileSync('src/main/resources/static/js/vector-search.mjs','utf8');
  const cleanup="root.querySelector('.app-oci-catalog')?.remove();";
  assert.ok(code.includes(cleanup));assert.ok(code.indexOf(cleanup)<code.indexOf('const find='));
  const css=fs.readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(css,/\.app-oci-catalog\s*\{\s*display:\s*none;\s*\}/);
  assert.doesNotMatch(code,/logout|sessionStorage|localStorage/);
});
test('OCI model syntax examples are available without calling a catalogue or selecting a value',()=>{
  const examples=modelExamples('ocigenai');
  assert.deepEqual(examples,['cohere.embed-v4.0','cohere.embed-multilingual-v3.0','cohere.embed-english-v3.0','cohere.embed-multilingual-light-v3.0','cohere.embed-english-light-v3.0']);
  examples.push('changed');assert.equal(modelExamples('ocigenai').length,5);
  assert.deepEqual(modelExamples('database'),[]);assert.deepEqual(modelExamples('unknown'),[]);
  const code=fs.readFileSync('src/main/resources/static/js/vector-search.mjs','utf8');
  assert.match(code,/find\('model'\)\.placeholder=state.oci\?t\('vector.public.modelName'/);
  assert.doesNotMatch(code,/find\('model'\)\.value=suggestions\[0\]/);
});
test('input type help uses the native popover without submitting a search',()=>{
  const template=fs.readFileSync('src/main/resources/templates/vector-search.html','utf8');
  assert.match(template,/<button type="button"[^>]*popovertarget="embedding-input-type-help"[^>]*data-input-type-help/);
  const code=fs.readFileSync('src/main/resources/static/js/vector-search.mjs','utf8');
  const handler=code.slice(code.indexOf('const positionHelp='),code.indexOf('let metadata='));
  assert.match(handler,/aria-expanded/);assert.match(handler,/aria-describedby/);
  assert.match(handler,/getBoundingClientRect/);
  assert.doesNotMatch(handler,/api\(|fetch\(|submit\(|searchInput\(/);
});
