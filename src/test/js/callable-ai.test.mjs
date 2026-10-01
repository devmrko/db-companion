import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {requestOf,canInstall} from '../../main/resources/static/js/callable-ai.mjs';
test('options stay independent and disabled ontology sends no stale table names',()=>{
  assert.deepEqual(requestOf({question:'q',profile:'p',glossary:false,ontology:true,tables:['T'],mode:'CONTEXT',maxRows:'200'}),{question:'q',profile:'p',glossary:false,ontology:true,tables:['T'],mode:'CONTEXT',maxRows:200});
  assert.deepEqual(requestOf({ontology:false,tables:['T']}).tables,[]);
});
test('only absent or recognized partial packages are installable',()=>{
  for(const state of ['MISSING','INCOMPLETE'])assert.equal(canInstall({state}),true);
  for(const state of ['READY','CONFLICT','INVALID',null])assert.equal(canInstall({state}),false);
  assert.equal(canInstall(null),false);
});
test('screen exposes SQL, no-AI mode, separate ontology switch and progress',()=>{
  const html=readFileSync('src/main/resources/templates/callable-ai.html','utf8');
  for(const hook of ['mode','glossary','ontology','tables','spinner','elapsed','consent','payload'])assert.match(html,new RegExp('data-ca-'+hook));
  assert.match(html,/value="CONTEXT"/);assert.match(html,/p_use_ontology/);assert.match(html,/DBC_AI_QUERY.ASK/);
  const js=readFileSync('src/main/resources/static/js/callable-ai.mjs','utf8');assert.doesNotMatch(js,/innerHTML|eval\(/);assert.match(js,/preview=null/);
});
