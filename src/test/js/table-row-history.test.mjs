import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {rowHistoryControls,rowHistoryRequest,rowChanges} from '../../main/resources/static/js/table-row-history.mjs';
test('history switches are disabled until a valid owner state is known',()=>{
  assert.equal(rowHistoryControls(null).disabled,true);assert.equal(rowHistoryControls(null).toggleVisible,false);
  assert.equal(rowHistoryControls({code:'OFF',canEnable:true}).disabled,false);
  assert.equal(rowHistoryControls({code:'ON',canDisable:true}).checked,true);
  assert.equal(rowHistoryControls({code:'ON',canDisable:false}).disabled,true);
  assert.equal(rowHistoryControls({code:'OFF',canEnable:true},true).disabled,true);
});
test('history requests carry exact table identity and cannot issue an unavailable action',()=>{
  const state={code:'OFF',canEnable:true,tableId:123,signature:'abc'};
  assert.deepEqual(rowHistoryRequest('APP','VECTORS',state,'enable'),{schema:'APP',table:'VECTORS',tableId:123,signature:'abc',action:'enable'});
  assert.throws(()=>rowHistoryRequest('APP','VECTORS',state,'disable'));assert.throws(()=>rowHistoryRequest('APP','VECTORS',state,'drop'));
});
test('whole column changes use existing diff and preserve long strings and numeric identifiers',()=>{
  const before={ID:'90071992547409931234',QUESTION:'질문\n'.repeat(1000),ANSWER_SQL:'select 1 from dual'};
  const after={...before,ANSWER_SQL:'select 2 from dual'};
  const changes=rowChanges(JSON.stringify(before),JSON.stringify(after));assert.equal(changes.length,1);assert.equal(changes[0].name,'ANSWER_SQL');
  assert.ok(changes[0].rows.some(r=>r.kind==='removed'));assert.ok(changes[0].rows.some(r=>r.kind==='added'));
  assert.equal(rowChanges(null,JSON.stringify(before)).length,3);assert.equal(rowChanges(JSON.stringify(before),null).length,3);
  assert.deepEqual(rowChanges(JSON.stringify(before),JSON.stringify(before)),[]);
});
test('tracking is explicit, refreshed only on request, and full JSON is rendered as text',()=>{
  const code=fs.readFileSync('src/main/resources/static/js/table-row-history.mjs','utf8');
  assert.match(code,/async function refresh\(force=false\)/);assert.match(code,/api\('\/state',\{refresh:force\}\)/);
  assert.match(code,/\(\)=>refresh\(true\)/);assert.match(code,/if\(!confirm\(prompt\)\)/);assert.match(code,/textContent=text/);
  assert.doesNotMatch(code,/innerHTML|localStorage|sessionStorage|\/search|\/oci-models|VECTOR_SERIALIZE/);
  assert.match(code,/raw\?\?t\('ui.f6a454f6c46d'/);
});
test('JSON column text keeps large numbers exact and column names are not treated as profile metadata',()=>{
  const before={attributes:'{"id":90071992547409931234}',profile:'{"id":1}'};
  const after={...before,attributes:'{"id":90071992547409931235}'};
  const changes=rowChanges(JSON.stringify(before),JSON.stringify(after));assert.equal(changes.length,1);
  assert.equal(changes[0].name,'attributes');assert.equal(changes[0].before,before.attributes);assert.equal(changes[0].after,after.attributes);
});
test('history layout keeps its title and accessible toggle label with scoped responsive styles',()=>{
  const code=fs.readFileSync('src/main/resources/static/js/table-row-history.mjs','utf8');
  const css=fs.readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(code,/toolbar\.prepend\(title\)/);assert.match(code,/title\.after\(find\('help'\)\)/);
  assert.match(code,/find\('toggle-label'\)\.querySelector\('span'\)\.classList\.add\('visually-hidden'\)/);
  assert.match(code,/toolbar\.append\(actions\)/);assert.match(code,/dataset\.state=code/);
  assert.match(css,/\[data-row-history\] \{ margin: 0; padding: 12px 24px/);
  assert.match(css,/\.app-vector-explorer > \[data-status\]:empty \{ display: none; \}/);
  assert.match(css,/\.app-row-history-actions \{ width: 100%; margin-left: 0; \}/);
  assert.match(css,/\.form-check-input:focus-visible/);assert.match(css,/\.form-check-input:checked/);
});
