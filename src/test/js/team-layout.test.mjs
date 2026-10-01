import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

test('Team layout is scoped and keeps full content with local table scrolling',()=>{
  const html=readFileSync('src/main/resources/templates/ai-agent-team.html','utf8');
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(html,/class="app-shell app-team-detail-page"/);
  assert.match(html,/class="app-history-toolbar app-team-heading-actions"/);
  assert.match(html,/class="app-card table-responsive"/);
  assert.match(css,/\.app-team-detail-page \.app-page-heading \{ flex-wrap: wrap;/);
  assert.match(css,/\.app-team-detail-page \.app-card-heading h2 \{[^}]*min-width: 0;[^}]*overflow-wrap: anywhere;/);
  assert.match(css,/\.app-team-detail-page \.table-responsive \{ position: relative;/);
  assert.match(css,/\.app-team-detail-page \.app-table \{ min-width: 640px;/);
  assert.match(css,/\.app-team-detail-page \.app-table td \{ overflow-wrap: anywhere;/);
  assert.match(css,/\.app-team-detail-page \.app-detail-list > div \{ grid-template-columns: 100px minmax\(0, 1fr\);/);
  assert.doesNotMatch(css,/\.app-team-detail-page[^{}]*\{[^}]*overflow(?:-x)?:\s*(?:hidden|clip)/);
  for(const file of ['ai-agent-object.html','ai-agent-task.html','ai-agents.html'])
    assert.doesNotMatch(readFileSync('src/main/resources/templates/'+file,'utf8'),/app-team-detail-page/);
});

test('Team editor and history headings, JSON and actions remain bounded on mobile',()=>{
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(css,/\.app-team-detail-page \.app-preview-header \{ flex-wrap: wrap;/);
  assert.match(css,/\.app-team-detail-page \.app-history-entry-heading \{[^}]*flex-wrap: wrap;/);
  assert.match(css,/\.app-team-detail-page \.app-history-json \{ white-space: pre-wrap; overflow-wrap: anywhere;/);
  assert.match(css,/\.app-team-detail-page \.app-editor-value \{ resize: vertical;/);
  assert.match(css,/\.app-team-detail-page :is\(\[data-team-editor\], \[data-object-editor\]\) \.app-preview-header h2 \{ flex-basis: 100%;/);
});
