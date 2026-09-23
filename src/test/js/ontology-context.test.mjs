import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {selectedEdits,reviewRows,responseDiagnostic,prettyPayload} from '../../main/resources/static/js/ontology-context.mjs';
import {assistantError} from '../../main/resources/static/js/ai-assistant.mjs';
import {meaningChanges} from '../../main/resources/static/js/ontology-rdf.mjs';
test('relationship changes keep exact constraint identity and are never adopted by default',()=>{
  const before={concept:'Orders',description:'Old',columns:{ID:{description:'Identifier'}},relations:{FK_A:'Old relationship',FK_B:'Keep'}};
  const after={...before,description:'New',relations:{FK_A:'New relationship',FK_B:'Keep'}};
  const changes=meaningChanges(before,after);
  assert.deepEqual(changes,[{field:'description',before:'Old',after:'New'},{field:'relation',name:'FK_A',before:'Old relationship',after:'New relationship'}]);
  const recommendations=changes.map(c=>({field:c.field,name:c.name??'',value:c.after,reason:'FK',uncertainty:'Confirm'}));
  const rows=reviewRows(changes,recommendations);assert.equal(rows.length,2);assert.deepEqual(selectedEdits(rows),[]);
  rows[1].selected=true;rows[1].value='Edited relationship';
  assert.deepEqual(selectedEdits(rows),[{field:'relation',name:'FK_A',value:'Edited relationship'}]);
  assert.equal(before.relations.FK_A,'Old relationship');assert.deepEqual(reviewRows(changes,[]),[]);
});
test('review is text-only, shows evidence and cleans up session tokens on close',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-context.mjs','utf8');
  assert.ok(code.includes('textContent'));assert.ok(code.includes('check.checked=false'));assert.ok(code.includes("label('uncertainty')"));
  assert.ok(!/innerHTML|eval\(|fetch\(|localStorage|sessionStorage/.test(code));
  const main=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');
  for(const text of ["post('/ai/cancel'","edits:selectedEdits(contextRows)","contextSummary(get('ai-context'),preview.context)","evidenceView(entry.document.analysis)"])assert.ok(main.includes(text),text);
});
test('all new UI labels are available in four languages',()=>{
  const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-context.json','utf8'));
  for(const [key,values] of Object.entries(labels)){assert.equal(values.length,4,key);assert.ok(values.every(v=>typeof v==='string'&&v.trim()),key);}
  const code=readFileSync('src/main/resources/static/js/ontology-context.mjs','utf8');
  for(const match of code.matchAll(/label\('([^']+)'\)/g))assert.ok(labels['ontology.context.'+match[1]],match[1]);
});
test('422 diagnostics survive the API error path without altering untrusted output',()=>{
  const diagnostic={code:'SCOPE',path:'$.suggestions[0].field/name',rawResponse:'<img src=x onerror=alert(1)>'};
  const error=assistantError({error:'Outside scope',diagnostic},422);assert.equal(error.message,'Outside scope');assert.deepEqual(responseDiagnostic(error),diagnostic);
  assert.equal(responseDiagnostic(assistantError({error:'Server failure',diagnostic},503)),null);
  assert.equal(responseDiagnostic(new Error('Ordinary failure')),null);
  for(const invalid of [{...diagnostic,code:'UNKNOWN'},{...diagnostic,path:3},{...diagnostic,rawResponse:'x'.repeat(200001)}])assert.equal(responseDiagnostic({diagnostic:invalid}),null);
});
test('preview JSON is formatted without changing the transmitted data',()=>{
  const source='{"graphs":[{"rdf":"line one\\nline two"}]}';assert.deepEqual(JSON.parse(prettyPayload(source)),JSON.parse(source));assert.ok(prettyPayload(source).includes('\n  "graphs"'));
  assert.equal(prettyPayload('not-json'),'not-json');
});
test('rejected raw output is text only and cleared on close and new preview',()=>{
  const main=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');
  assert.ok(main.includes("get('ai-raw').textContent=d?.rawResponse??''"));assert.ok(main.includes("aiRunning=true;aiDiagnostic();"));assert.ok(main.includes("contextRows=[];aiDiagnostic();"));
  assert.ok(!/innerHTML|localStorage|sessionStorage/.test(main));
  assert.ok(main.includes("proposal=null;contextRows=[];get('apply').hidden=true"));
  const html=readFileSync('src/main/resources/templates/ontology.html','utf8');assert.ok(html.includes('data-on-ai-diagnostic hidden'));assert.ok(html.includes('data-on-ai-raw'));
});
