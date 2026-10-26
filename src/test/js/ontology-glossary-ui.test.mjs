import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

test('ontology no longer mounts a second glossary search, administration or AI handoff',()=>{
  const html=readFileSync('src/main/resources/templates/ontology.html','utf8');
  const source=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');
  assert.doesNotMatch(html,/data-on-glossary|data-on-text-(guide|install|sync)|ontology-terms/);
  assert.doesNotMatch(source,/glossary|wireDefinitionReview|textActivation|ontology-definition/);
  for(const item of ['data-on-list','data-on-filter','data-on-save','data-on-approve','data-on-tab="history"','data-on-tab="columns"'])assert.ok(html.includes(item),item);
  assert.match(source,/ontologyRelationships/);assert.match(source,/ontologyWizard/);assert.ok(source.includes('work(()=>load())'));
});
test('independent glossary and Select AI ontology evidence remain available',()=>{
  const testPage=readFileSync('src/main/resources/templates/ai-test.html','utf8');
  const source=readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8');
  assert.ok(testPage.includes('fragments/business-glossary'));assert.match(source,/mountBusinessGlossary/);
  assert.match(source,/select-ai-evidence/);
  const service=readFileSync('src/main/java/com/dbcompanion/service/SelectAiTestService.java','utf8');
  assert.match(service,/evidenceSearch/);assert.ok(service.includes('questionAnalysis).analyze'));
  assert.ok(!service.includes('glossary).analyze'));
  const inquiry=readFileSync('src/main/java/com/dbcompanion/service/OntologyQueryService.java','utf8');
  assert.match(inquiry,/QuestionAnalysisService/);assert.match(inquiry,/BusinessGlossaryService/);
  assert.match(inquiry,/glossary\.interpret/);assert.match(inquiry,/glossary\.verifyTerms/);
  assert.match(inquiry,/state\.grounding\(grounding\)/);
  const evidence=readFileSync('src/main/resources/static/js/select-ai-evidence.mjs','utf8');
  assert.match(evidence,/mountRdfEvidence as mountEvidence/);
});
