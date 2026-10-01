import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

test('profile detail uses bounded text/grid sizing without hiding page overflow',()=>{
  const html=readFileSync('src/main/resources/templates/ai-profiles.html','utf8');
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(html,/th:classappend="\$\{profileName != null\} \? 'app-profile-detail-page'"/);
  assert.match(html,/class="app-profile-heading-actions"/);
  assert.match(html,/app-card table-responsive mt-3/);
  assert.match(css,/\.app-profile-detail-page \.app-page-heading h1 \{[^}]*min-width: 0;[^}]*overflow-wrap: anywhere;/);
  assert.match(css,/\.app-profile-detail-page \.app-detail-list > div \{ grid-template-columns: 100px minmax\(0, 1fr\);/);
  assert.match(css,/\.app-profile-detail-page \.app-detail-list :is\(dt, dd\) \{ min-width: 0;/);
  assert.match(css,/\.app-profile-detail-page \.table-responsive \{ position: relative;/);
  assert.doesNotMatch(css,/\.app-profile-detail-page[^{}]*\{[^}]*overflow(?:-x)?:\s*(?:hidden|clip)/);
});

test('profile dialogs wrap their headings and object picker layout is language independent',()=>{
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(css,/\.app-profile-detail-page :is\(\[data-profile-editor\], \[data-ph-dialog\]\) \.app-preview-header \{ flex-wrap: wrap;/);
  assert.match(css,/\.app-profile-detail-page \.app-object-picker > select:last-of-type \{ grid-column: 1 \/ 3;/);
  assert.match(css,/\.app-profile-detail-page \.app-object-picker > select:first-child \{ grid-column: 1 \/ -1;/);
  assert.match(css,/\.app-profile-detail-page \.app-object-picker > select:last-of-type \{ grid-column: 1;/);
});
