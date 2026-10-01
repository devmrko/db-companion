import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {problemStatusLabel,problemStatusClass} from '../../main/resources/static/js/problem-questions.mjs';

test('problem statuses are localized and badge classes use a fixed allowlist',()=>{
  globalThis.DB_COMPANION_MESSAGES={'problemQuestion.status.underReview':'Under review'};
  try{
    assert.equal(problemStatusLabel('UNDER_REVIEW'),'Under review');
    assert.equal(problemStatusClass('UNDER_REVIEW'),'is-under-review');
    assert.equal(problemStatusClass('unexpected injected-class'),'');
    assert.equal(problemStatusLabel('NEW_STATE'),'NEW_STATE');
  }finally{delete globalThis.DB_COMPANION_MESSAGES;}
});
test('workbench form and list keep existing hooks and use scoped responsive styles',()=>{
  const html=readFileSync('src/main/resources/templates/ai-problems.html','utf8');
  assert.match(html,/management-workbench\.css/);
  assert.match(html,/class="workbench-form" data-problem-create/);
  assert.match(html,/workbench-wide[\s\S]*data-problem-question/);
  assert.match(html,/data-batch-selection-summary/);
  assert.match(html,/href="#problem-batch"/);
  assert.match(html,/id="problem-batch" data-batch-panel/);
  assert.match(html,/data-problem-export-dialog/);
  const css=readFileSync('src/main/resources/static/css/management-workbench.css','utf8');
  assert.match(css,/\.app-management-workbench \[hidden\].*none !important/);
  assert.match(css,/@media \(max-width: 760px\)/);
});
