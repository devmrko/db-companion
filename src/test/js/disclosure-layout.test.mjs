import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,readdirSync} from 'node:fs';
import {join} from 'node:path';
const root='src/main/resources/';
const css=readFileSync(root+'static/css/common.css','utf8');
const read=path=>readFileSync(root+path,'utf8');
const walk=dir=>readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(join(dir,e.name)):[join(dir,e.name)]);
const templates=walk(root+'templates').filter(p=>p.endsWith('.html'));

test('every static icon help is a details with an immediate question-mark summary and one body',()=>{
  let count=0;
  for(const file of templates) {
    const html=readFileSync(file,'utf8');
    for(const m of html.matchAll(/<(\w+)\b[^>]*class="[^"]*\bapp-dds-help\b[^"]*"[^>]*>/g))
      assert.equal(m[1],'details',file);
    for(const m of html.matchAll(/<details\b[^>]*class="[^"]*\bapp-dds-help\b[^"]*"[^>]*>([\s\S]*?)<\/details>/g)) {
      assert.match(m[1],/^\s*<summary\b[^>]*>\?<\/summary>\s*<div\b/);
      assert.match(m[1],/<\/div>\s*$/);count++;
    }
  }
  assert.ok(count>=12,'all screen help fragments were inspected');
});
test('icon help CSS cannot constrain nested or text disclosure summaries to a circle',()=>{
  assert.doesNotMatch(css,/\.app-dds-help\s+summary\s*\{/);
  assert.match(css,/details\.app-dds-help > summary\s*\{[^}]*width: 26px/);
  assert.match(css,/\.app-disclosure > summary\s*\{[^}]*width: auto; height: auto/);
  assert.match(css,/details\.app-dds-help > div\[data-help-positioned\][^}]*position: fixed/);
});
test('the shared setup guide stays in normal flow with bounded, keyboard-readable SQL blocks',()=>{
  const guide=read('templates/fragments/select-ai-setup.html');
  assert.match(guide,/class="app-card app-setup-guide"/);assert.match(guide,/class="app-disclosure"/);
  assert.doesNotMatch(guide,/app-dds-help/);
  assert.equal((guide.match(/class="app-preview-source" tabindex="0"/g)||[]).length,3);
  for(const screen of ['credentials','ai-profiles','ai-test'])assert.match(read('templates/'+screen+'.html'),/fragments\/select-ai-setup :: guide/);
  assert.match(css,/\.app-disclosure-body h3[^}]*font-size: 14px/);
  assert.match(css,/\.app-disclosure-body \.app-preview-source[^}]*white-space: pre-wrap/);
});
test('dynamic evidence and business-definition disclosures do not use icon-help styling',()=>{
  assert.match(read('static/js/ontology-context.mjs'),/details=el\('details',undefined,'app-disclosure'\)/);
  assert.match(read('static/js/ontology.mjs'),/help=el\('details',undefined,'app-disclosure'\)/);
  for(const path of ['ontology-values.mjs','ontology-terms.mjs'])assert.match(read('static/js/'+path),/help\.append\(summary,helpBody\)/);
});
test('all shared dialogs have titles and keep their native closed state',()=>{
  let count=0;
  for(const file of templates)for(const m of readFileSync(file,'utf8').matchAll(/<dialog\b[^>]*>/g)){
    assert.match(m[0],/class="[^"]*app-preview-dialog/);assert.match(m[0],/aria-labelledby="[^"]+"/);
    assert.doesNotMatch(m[0],/\sopen(?:\s|>|=)/);count++;
  }
  assert.ok(count>=38);assert.doesNotMatch(css,/\.app-preview-dialog\s*\{[^}]*display:/);
  assert.match(css,/\.app-preview-header > \.app-btn[^}]*flex: 0 0 auto/);
  assert.match(css,/\.app-preview-dialog :is\(h3, legend\)[^}]*font-size: 14px/);
});
test('comparison/export checkboxes use responsive groups and toolbars wrap before controls collapse',()=>{
  for(const screen of ['ai-test','ai-problems'])assert.match(read('templates/'+screen+'.html'),/class="app-choice-grid"/);
  assert.match(css,/\.app-choice-grid[^}]*repeat\(auto-fit, minmax\(min\(100%, 180px\), 1fr\)\)/);
  assert.match(css,/\.app-dds-toolbar\s*\{[^}]*flex-wrap: wrap/);
  assert.match(css,/\.app-editor-actions\s*\{[^}]*flex-wrap: wrap/);
  assert.match(css,/\.app-help-tooltip\s*\{[^}]*max-height:[^}]*overflow: auto/);
});
