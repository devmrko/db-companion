import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

test('standalone and Team-linked Tasks share scoped detail layout',()=>{
  const standalone=readFileSync('src/main/resources/templates/ai-agent-object.html','utf8');
  const linked=readFileSync('src/main/resources/templates/ai-agent-task.html','utf8');
  assert.match(standalone,/objectKind == 'TASK'\} \? 'app-task-detail-page'/);
  assert.match(linked,/<body class="app-shell app-task-detail-page"/);
  assert.match(linked,/taskPage.tools/);
  assert.match(linked,/taskPage.task/);
  assert.match(linked,/object-editor :: editor/);
  assert.match(linked,/object-history :: history/);
  for(const file of ['ai-agent-team.html','ai-agent-objects.html'])assert.doesNotMatch(readFileSync('src/main/resources/templates/'+file,'utf8'),/app-task-detail-page/);
});

test('Task metadata scrolls locally and long headings and dialog content wrap',()=>{
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(css,/\.app-task-detail-page \.app-page-heading h1 \{[^}]*min-width: 0;[^}]*overflow-wrap: anywhere;/);
  assert.match(css,/\.app-task-detail-page \.table-responsive \{ position: relative;/);
  assert.match(css,/\.app-task-detail-page \.app-table \{ min-width: 640px;/);
  assert.match(css,/\.app-task-detail-page \.app-detail-list > div \{ grid-template-columns: 100px minmax\(0, 1fr\);/);
  assert.match(css,/\.app-task-detail-page \.app-preview-header \{ flex-wrap: wrap;/);
  assert.match(css,/\.app-task-detail-page \.app-history-entry-heading \{[^}]*flex-wrap: wrap;/);
  assert.match(css,/\.app-task-detail-page textarea \{ resize: vertical;/);
  assert.match(css,/\.app-task-detail-page \.app-back-link \{[^}]*overflow-wrap: anywhere;/);
  assert.doesNotMatch(css,/\.app-task-detail-page[^{}]*\{[^}]*overflow(?:-x)?:\s*(?:hidden|clip)/);
});
