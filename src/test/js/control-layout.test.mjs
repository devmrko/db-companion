import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const css=readFileSync('src/main/resources/static/css/common.css','utf8');
const form=readFileSync('src/main/resources/templates/fragments/local-object-creation.html','utf8');
test('dashboard uses the shared card heading and section spacing',()=>{
  const dashboard=readFileSync('src/main/resources/templates/dashboard.html','utf8');
  assert.match(dashboard,/app-card app-section"><div class="app-card-heading"><h2 th:text="#\{auditFixes.build.title\}/);
  assert.match(css,/\.app-section \{ margin-top: 20px/);
});
test('shared empty, checkbox, action-cell and graph styles have explicit layout',()=>{
  for(const name of ['app-empty','app-card-note','app-field','app-check-field','app-graph-canvas'])assert.ok(css.includes('.'+name+' {'));
  assert.match(css,/\.app-table \.app-btn \{ white-space: nowrap/);
  assert.match(css,/\.app-graph-canvas \{ position: relative/);
  assert.match(readFileSync('src/main/resources/static/js/ai-creation.mjs','utf8'),/\?'app-check-field':'app-field'/);
});
test('workbench fields do not restore the old oversized controls',()=>{
  const workbench=readFileSync('src/main/resources/static/css/management-workbench.css','utf8');
  assert.match(workbench,/padding-right: 36px; min-height: var\(--app-control-height\)/);
  assert.doesNotMatch(workbench,/min-height: 42px/);
});
test('single-line controls share a minimum height without fixing textarea height',()=>{
  assert.match(css,/--app-control-height: 38px/);
  assert.match(css,/\.app-btn \{ min-height: var\(--app-control-height\)/);
  assert.match(css,/select\.form-select:not\(\[multiple\]\):not\(\[size\]\)/);
  assert.match(css,/textarea\.form-control \{ resize: vertical/);
});
test('embedded creation uses the shared disclosure and only one padding layer',()=>{
  assert.match(form,/details class="app-disclosure" data-lc-panel/);
  assert.match(css,/\[data-local-create\] \.app-editor-form \{ padding: 0/);
  assert.match(css,/\[data-local-create\] \[hidden\] \{ display: none !important/);
  assert.match(css,/\.app-creation-grid > label \{[^}]*min-width:0;margin:0/);
});
test('danger actions and text links have explicit shared styles',()=>{
  assert.match(css,/\.app-btn-danger \{/);
  assert.match(css,/\.app-link:hover \{ text-decoration: underline/);
});
test('query cards and glossary grid labels avoid flush edges and doubled spacing',()=>{
  assert.match(css,/\[data-ontology-query\] > \.app-card \{[^}]*padding: 20px/);
  assert.match(css,/\.app-preview-body\.app-glossary-form > label,[^}]*margin: 0/);
  assert.match(css,/\.app-source-toolbar input \{[^}]*height: 32px; min-height: 32px/);
});
