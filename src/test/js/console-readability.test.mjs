import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const css=readFileSync('src/main/resources/static/css/common.css','utf8');
const common=readFileSync('src/main/resources/templates/fragments/common.html','utf8');
const ontology=readFileSync('src/main/resources/templates/ontology.html','utf8');
const help=common.match(/<dialog[^>]*data-sql-help-dialog[\s\S]*?<\/dialog>/)[0];

test('dark topbar button and focus colors are scoped to chrome children, not the help dialog',()=>{
  assert.doesNotMatch(css,/\.app-topbar\s+\.app-btn-quiet/);
  assert.doesNotMatch(css,/\.app-topbar\s+:is\(a, button\):focus-visible/);
  assert.match(css,/\.app-topbar > :is\(\.app-breadcrumb, \.app-topbar-actions\) \.app-btn-quiet/);
  for(const control of ['close','copy'])
    assert.match(help,new RegExp('class="btn app-btn app-btn-secondary" data-sql-help-'+control));
  assert.match(css,/\.app-sql-help-dialog :focus-visible\s*\{[^}]*var\(--app-focus\)/);
});

test('help has an explicit heading scale, padded sections and a keyboard-accessible code surface',()=>{
  assert.match(css,/\.app-sql-help-dialog \.app-preview-header h2\s*\{[^}]*font-size: 18px/);
  assert.match(css,/\.app-sql-help-section h3\s*\{[^}]*font-size: 14px/);
  assert.match(css,/\.app-sql-help-dialog \.app-preview-body\s*\{[^}]*gap: 22px; padding: 24px/);
  assert.match(help,/class="app-preview-source" data-sql-help-sql tabindex="0"/);
  assert.equal((help.match(/class="app-sql-help-section"/g)||[]).length,6);
  assert.match(help,/data-sql-help-status aria-live="polite"/);
  assert.match(css,/\.app-sql-help-dialog \.app-preview-source\s*\{[^}]*overflow-wrap: anywhere/);
});

test('responsive layout wraps action groups and prevents buttons shrinking into single characters',()=>{
  assert.match(css,/\.app-glossary-grid\s*\{[^}]*minmax\(0, 1fr\)/);
  assert.match(css,/\.app-glossary-actions\s*\{[^}]*flex-wrap: wrap/);
  assert.match(css,/\.app-glossary-input-row\s*\{[^}]*flex-wrap: wrap/);
  assert.match(css,/\.app-glossary \.app-btn\s*\{[^}]*flex-shrink: 0;[^}]*white-space: nowrap/);
  assert.match(css,/@media \(max-width: 900px\) \{ \.app-glossary-grid \{ grid-template-columns: minmax\(0, 1fr\)/);
  assert.match(css,/\.app-glossary \.app-btn\s*\{[^}]*word-break: keep-all/);
  assert.match(css,/\.app-glossary \.app-btn:disabled\s*\{[^}]*--bs-btn-disabled-opacity: 1/);
});

test('new glossary guidance is available in every language bundle without duplicate keys',()=>{
  for(const suffix of ['','_ko','_en','_ja','_zh_CN']) {
    const messages=readFileSync(`src/main/resources/i18n/messages${suffix}.properties`,'utf8');
    for(const key of ['scopeHint','profileHint','contextHint','admin','adminHint']) {
      const values=messages.match(new RegExp('^ontology\\.glossary\\.'+key+'=.+$','gm'))||[];
      assert.equal(values.length,1,`${suffix}: ${key}`);
    }
  }
});
