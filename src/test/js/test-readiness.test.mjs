import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {preparationBlockers} from '../../main/resources/static/js/select-ai-test.mjs';
import {mountEvidence} from '../../main/resources/static/js/select-ai-evidence.mjs';

test('all blocking reasons are visible; optional evidence can be disabled independently',()=>{
  const blocked=preparationBlockers('', 'question', false, false, false);
  assert.equal(blocked.length,3);assert.match(blocked[0],/A\/B/);
  assert.deepEqual(preparationBlockers('P','question',false,true,true),[]);
  assert.equal(preparationBlockers('P','question',false,false,true).length,1);
  assert.equal(preparationBlockers('P','question',false,true,false).length,1);
  assert.equal(preparationBlockers('P','question',true,true,true).length,1);
  assert.equal(preparationBlockers('P','',false,true,true).length,1);
});

test('RDF evidence uses shared selection, invalidates edits and restores only same-schema RDF snapshots',()=>{
  const source=readFileSync('src/main/resources/static/js/select-ai-rdf-evidence.mjs','utf8');
  assert.match(source,/renderRdfWorkflow/);assert.ok(source.includes("clearSelected,label('rdfApply'"));
  assert.ok(source.includes("data.evidence?.route?.startsWith('RDF_')"));
  assert.ok(source.includes('data.evidence.question===question()&&data.evidence.schema===data.evidenceSchema'));
  assert.ok(source.includes("get('schema').disabled=true"));
});
test('new guidance exists in each supported locale and uses accessible bounded layout',()=>{
  const keys=['readyProfile','readyQuestion','readyGlossary','readyOntology','evDefinitionsTitle','evDefinitionsHelp','evDefinitionsApply','evNoPathDefinitions'];
  for(const suffix of ['','_ko','_en','_ja','_zh_CN']){const source=readFileSync(`src/main/resources/i18n/messages${suffix}.properties`,'utf8');for(const key of keys)assert.ok(source.includes('aitest.'+key+'='));}
  const html=readFileSync('src/main/resources/templates/ai-test.html','utf8');assert.match(html,/aria-live="polite" data-test-readiness/);assert.match(html,/data-test-evidence-definitions-apply disabled/);
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');assert.match(css,/app-evidence-definition-list[^\n]+max-height: 280px/);
});
