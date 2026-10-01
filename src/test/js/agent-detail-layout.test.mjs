import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

test('standalone Agent detail scopes layout without affecting Task, Tool or Team',()=>{
  const html=readFileSync('src/main/resources/templates/ai-agent-object.html','utf8');
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(html,/th:classappend="\$\{objectKind == 'AGENT'\} \? 'app-agent-detail-page' :/);
  assert.match(css,/\.app-agent-detail-page \.app-page-heading h1 \{[^}]*min-width: 0;[^}]*overflow-wrap: anywhere;/);
  assert.match(css,/\.app-agent-detail-page \.table-responsive \{ position: relative;/);
  assert.match(css,/\.app-agent-detail-page \.app-table \{ min-width: 640px;/);
  assert.match(css,/\.app-agent-detail-page \.app-detail-list > div \{ grid-template-columns: 100px minmax\(0, 1fr\);/);
  assert.doesNotMatch(css,/\.app-agent-detail-page[^{}]*\{[^}]*overflow(?:-x)?:\s*(?:hidden|clip)/);
  for(const file of ['ai-agent-team.html','ai-agent-task.html','ai-agent-objects.html'])assert.doesNotMatch(readFileSync('src/main/resources/templates/'+file,'utf8'),/app-agent-detail-page/);
});

test('Agent edit, history and clone dialogs keep bounded headings and consistent spacing',()=>{
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(css,/\.app-agent-detail-page \.app-preview-header \{ flex-wrap: wrap;/);
  assert.match(css,/\.app-agent-detail-page \.app-history-entry-heading \{[^}]*flex-wrap: wrap;/);
  assert.match(css,/\.app-agent-detail-page textarea \{ resize: vertical;/);
  assert.match(css,/\.app-agent-detail-page \[data-ai-creation\] \[data-ac-message\]:empty \{ display: none;/);
  assert.match(css,/\.app-agent-detail-page :is\(\[data-object-editor\], \[data-ai-creation\]\) \.app-preview-header h2 \{ flex-basis: 100%;/);
});
