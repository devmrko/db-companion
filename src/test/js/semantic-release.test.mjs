import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {selectionItems,editableMembers,versionLabel} from '../../main/resources/static/js/semantic-release.mjs';
test('revision editor preserves parent membership and offers new current items unselected',()=>{
  const rows=editableMembers({terms:[{id:'a',term:'old',revision:2}],entries:[{document:{source:{table:'T'}},revision:3}]},{terms:[{id:'a',term:'latest',revision:7},{id:'b',term:'new',revision:1}],sources:[{table:'T',revision:9},{table:'U',revision:1}]});
  assert.equal(rows.find(r=>r.id==='a').revision,2);assert.equal(rows.find(r=>r.id==='T').revision,3);
  assert.equal(rows.find(r=>r.id==='b').included,false);assert.equal(rows.find(r=>r.id==='U').included,false);
});
test('version selector has deployed default and explicit live development, never implicit HEAD',()=>{
  const versions=[{id:'12345678-9',number:12,name:'candidate'}];
  assert.deepEqual(selectionItems(versions).map(x=>x[0]),['DEFAULT','LIVE','12345678-9']);
  assert.equal(selectionItems(versions)[2][1],'v12 · candidate');
  assert.equal(versionLabel(versions[0]),'v12 · candidate');
});
test('storage creation is explicit, previewed and consented; loading cannot issue DDL',()=>{
  const js=readFileSync('src/main/resources/static/js/semantic-release.mjs','utf8');
  assert.match(js,/post\('\/storage-preview'/);assert.match(js,/post\('\/storage-install',\{token,confirmed:true\}/);
  assert.match(js,/!storagePlan\|\|!get\('storage-consent'\).checked/);
  assert.match(js,/const token=storagePlan.token;storagePlan=null/);
  const selector=readFileSync('src/main/resources/static/js/semantic-selector.mjs','utf8');
  assert.doesNotMatch(selector,/storage-install|storage-preview/);
});
test('query selector cannot initialize, create, revise or deploy versions',()=>{
  for(const file of ['ontology-query','ai-test'])assert.match(readFileSync(`src/main/resources/templates/${file}.html`,'utf8'),/fragments\/semantic-release :: panel/);
  const js=readFileSync('src/main/resources/static/js/semantic-selector.mjs','utf8');
  assert.match(js,/expected:state.preference/);assert.doesNotMatch(js,/['"]\/(deploy|create|initialize|revise)['"]/);
  assert.match(js,/assistantPost/);assert.doesNotMatch(js,/innerHTML|localStorage|sessionStorage/);
  assert.match(js,/location.reload\(\)/);
});
test('manager has row actions and cannot change the test selection',()=>{
  const js=readFileSync('src/main/resources/static/js/semantic-release.mjs','utf8');
  assert.match(js,/expected:state.head/);assert.doesNotMatch(js,/post\('\/select'/);
  for(const key of ['view','editCopy','promote'])assert.ok(js.includes(`action('${key}'`));
  const html=readFileSync('src/main/resources/templates/semantic-versions.html','utf8');
  assert.match(html,/semantic-release :: manager/);assert.doesNotMatch(html,/semantic-release :: panel/);
});
test('semantic labels exist in all supported languages',()=>{
  const keys=Object.keys(JSON.parse(readFileSync('tools/i18n/feature-semantic-release.json','utf8')));
  for(const locale of ['','_ko','_en','_ja','_zh_CN']){
    const bundle=readFileSync(`src/main/resources/i18n/messages${locale}.properties`,'utf8');for(const key of keys)assert.ok(bundle.includes(key+'='));
  }
});
