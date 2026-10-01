import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

test('Tool list layout is scoped, wraps full values and bounds its type selector',()=>{
  const html=readFileSync('src/main/resources/templates/ai-agent-objects.html','utf8');
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(html,/th:classappend="\$\{objectKind == 'TOOL'\} \? 'app-tool-list-page'"/);
  assert.match(css,/\.app-tool-list-page \.app-table \{ table-layout: fixed; width: 100%;/);
  assert.match(css,/\.app-tool-list-page \.app-table :is\(th, td\) \{ min-width: 0; overflow-wrap: anywhere;/);
  assert.match(css,/\.app-tool-list-page \.app-content > \.app-history-toolbar \.form-select \{[^}]*min-width: 0; width: 100%; max-width: 100%;/);
  assert.doesNotMatch(css,/\.app-tool-list-page[^{}]*\{[^}]*(?:overflow(?:-x)?:\s*(?:hidden|clip)|text-overflow:|line-clamp:)/);
});

test('Tool list preserves GET navigation, paging and explicit creation boundaries',()=>{
  const html=readFileSync('src/main/resources/templates/ai-agent-objects.html','utf8');
  for(const hook of ['data-paged-list','data-list-row','data-page-prev','data-page-next','data-page-summary','data-page-number'])assert.ok(html.includes(hook));
  assert.match(html,/form method="get" th:action="@\{\/ai-agents\/objects\}"/);
  assert.match(html,/\/ai-agents\/object\(schema=\$\{selectedSchema\},kind=\$\{objectKind\},name=\$\{item.name\}\)/);
  assert.match(html,/th:if="\$\{info.username == selectedSchema\}"/);
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(css,/\.app-tool-list-page \[data-ai-creation\] \.app-preview-header \{ flex-wrap: wrap;/);
  assert.match(css,/\.app-tool-list-page \[data-ai-creation\] \[data-ac-message\]:empty \{ display: none;/);
  assert.match(css,/\.app-tool-list-page \[data-ai-creation\] \.app-creation-grid > label \{ margin: 0;/);
  assert.match(css,/\.app-tool-list-page \[data-ai-creation\] \.app-preview-header h2 \{ flex-basis: 100%;/);
});
