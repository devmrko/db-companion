import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {evidenceKey,evidenceReady,evidenceMatches} from '../../main/resources/static/js/select-ai-evidence.mjs';
test('enrichment requires explicit choice for the same question',()=>{
  const value={hash:'hash',question:'질문'};
  assert.equal(evidenceReady(false,null,'질문'),true);
  assert.equal(evidenceReady(true,null,'질문'),false);
  assert.equal(evidenceReady(true,value,'질문'),true);
  assert.equal(evidenceReady(true,value,'other'),false);
  assert.equal(evidenceReady(true,{question:'질문'},'질문'),false);
});
test('old SQL or prompt cannot be used across evidence variants',()=>{
  const a={hash:'a',question:'q'},b={hash:'b',question:'q'};
  assert.equal(evidenceKey(null),'');
  assert.equal(evidenceMatches(a,true,a,'q'),true);
  assert.equal(evidenceMatches(a,true,b,'q'),false);
  assert.equal(evidenceMatches(a,false,a,'q'),false);
  assert.equal(evidenceMatches(null,true,a,'q'),false);
  assert.equal(evidenceMatches(null,false,a,'q'),true);
  assert.equal(evidenceMatches(a,true,a,'changed'),false);
});
test('only explicit choice is posted, raw evidence uses safe text and no provider calls',()=>{
  const source=fs.readFileSync('src/main/resources/static/js/select-ai-evidence.mjs','utf8');
  assert.ok(source.includes("post(analysisEndpoint('evidence/search',analysis.language())"));assert.match(source,/post\('evidence\/choose',\{searchId:search.id,route:get\('routes'\).value\}\)/);
  assert.match(source,/pre.textContent=value.source/);assert.match(source,/cell.textContent=text/);
  assert.doesNotMatch(source,/innerHTML|localStorage|sessionStorage|setInterval|setTimeout|post\('generate'|routes\[0\]/);
  assert.match(source,/useOntology:enabled\(\),evidenceHash:/);
  assert.match(source,/if\(!refresh&&loadedSchema===schema\)return/);
});
test('main workflow uses one context and blocks stale review and execution',()=>{
  const source=fs.readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8');
  assert.match(source,/post\('preview',\{action,question:get\('question'\).value,...evidence.request\(\),...glossary.request\(\)\}\)/);
  assert.match(source,/evidence.matches\(snapshot.evidence\)/);assert.match(source,/evidence.matches\(latest.evidence\)/);
  assert.match(source,/renderEvidence\(get\('request-evidence'\),outcome.evidence\)/);
  assert.match(source,/renderEvidence\(get\('prompt-evidence'\),value.evidence\)/);
});
test('all ontology UI labels have four translations and start unchecked',()=>{
  const messages=JSON.parse(fs.readFileSync('tools/i18n/feature-select-ai-ontology.json','utf8'));
  for(const values of Object.values(messages)){assert.equal(values.length,4);assert.ok(values.every(v=>v.length>0));}
  const html=fs.readFileSync('src/main/resources/templates/ai-test.html','utf8');
  assert.match(html,/data-test-evidence-enabled/);assert.doesNotMatch(html,/<input[^>]*data-test-evidence-enabled[^>]*checked/);
  const css=fs.readFileSync('src/main/resources/static/css/common.css','utf8');assert.match(css,/\[data-ai-test\] \[hidden\] \{ display: none !important; \}/);
});
