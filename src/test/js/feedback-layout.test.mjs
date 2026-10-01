import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

test('Feedback filters have wrap bases and bounded controls, not a fixed profile width', () => {
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(css,/\.app-feedback-filters \.app-feedback-profile-filter \{ flex: 1 1 330px;/);
  assert.match(css,/\.app-feedback-filters \.app-execution-question-filter \{ flex: 2 1 240px; min-width: 0;/);
  assert.match(css,/\.app-feedback-filters :is\(\.form-select, \.form-control\) \{ width: 100%; min-width: 0; max-width: 100%;/);
  assert.doesNotMatch(css,/\.app-feedback-filters select\[name="profile"\].*width: 330px/);
  assert.match(css,/\.app-feedback-page \.app-page-heading \{ flex-wrap: wrap;/);
  assert.match(css,/\.app-feedback-page .*\.app-preview-header \{ flex-wrap: wrap;/);
});

test('Feedback layout preserves query, edit, history and local table-scrolling hooks', () => {
  const html=readFileSync('src/main/resources/templates/ai-feedback.html','utf8');
  assert.match(html,/<body class="app-shell app-feedback-page">/);
  assert.match(html,/method="get"[^>]+app-feedback-filters/);
  for(const name of ['schema','profile','search','type'])assert.match(html,new RegExp(`name="${name}"`));
  assert.match(html,/app-feedback-profile-filter/);
  assert.match(html,/app-feedback-type-filter/);
  assert.match(html,/class="table-responsive"><table/);
  assert.match(html,/fragments\/feedback-tracking :: tracking/);
  assert.match(html,/data-feedback-edit/);
  assert.match(html,/fragments\/feedback-editor :: dialog/);
});
