import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {pipelineSummary,runDiscovery,discoveryDiagnostic} from '../../main/resources/static/js/ontology-pipeline.mjs';
test('relationship diagnostics keep bounded raw text and exact paths without interpreting markup',()=>{
  const d={code:'COLUMN',path:'$.relations[1].sourceColumns[0]',rawResponse:'<script>alert(1)</script>'};
  assert.deepEqual(discoveryDiagnostic({diagnostic:d}),d);
  for(const bad of [{...d,code:'UNKNOWN'},{...d,path:'x'.repeat(161)},{...d,rawResponse:'x'.repeat(200001)},{...d,rawResponse:null}])assert.equal(discoveryDiagnostic({diagnostic:bad}),null);
  assert.equal(discoveryDiagnostic(new Error('transport')),null);
});
test('diagnostic is shown on generation failure and cleared when closing or replacing the plan',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-pipeline.mjs','utf8');
  assert.match(code,/diagnostic\(ex\);status\(ex.message/);assert.match(code,/diagnostic\(\);dialog.close\(\)/);
  assert.match(code,/const invalidate=\(\)=>\{diagnostic\(\)/);assert.ok(code.includes("get('raw').textContent=d?.rawResponse??''"));
  const html=readFileSync('src/main/resources/templates/ontology.html','utf8');assert.ok(html.includes('data-pipeline-diagnostic hidden'));assert.ok(html.includes('data-pipeline-raw'));
});
test('relationship diagnostic messages are translated without placeholder drift',()=>{
  const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-discovery-diagnostics.json','utf8'));
  assert.equal(Object.keys(labels).length,14);for(const values of Object.values(labels)){assert.equal(values.length,4);assert.ok(values.every(v=>v.trim()));}
});
test('preview summaries account for exclusions',()=>assert.deepEqual(pipelineSummary({items:[{status:'INCLUDED'},{status:'EXCLUDED'}]}),{included:1,excluded:1}));
test('batch runner is sequential and stops after current call',async()=>{const calls=[];let stopped=false;const count=await runDiscovery({progress:{nextIndex:0,authorizedUntil:3}},{call:async i=>{calls.push(i);stopped=true;return {};},stop:()=>stopped,progress:()=>{}});assert.equal(count,1);assert.deepEqual(calls,[0]);});
test('failed AI calls never retry or advance silently',async()=>{let count=0;await assert.rejects(runDiscovery({progress:{nextIndex:0,authorizedUntil:2}},{call:async()=>{count++;throw new Error('provider error');},stop:()=>false,progress:()=>{}}),/provider error/);assert.equal(count,1);});
test('resumed groups only consume the newly authorized range and preserve earlier calls',async()=>{const calls=[];assert.equal(await runDiscovery({progress:{nextIndex:48,authorizedUntil:55}},{call:async i=>{calls.push(i);return {};},stop:()=>false,progress:()=>{}}),7);assert.deepEqual(calls,[48,49,50,51,52,53,54]);});
test('server pause or result limit prevents extra calls',async()=>{for(const signal of ['paused','resultLimit','done']){const calls=[];await runDiscovery({progress:{nextIndex:3,authorizedUntil:15}},{call:async i=>{calls.push(i);return {[signal]:true};},stop:()=>false,progress:()=>{}});assert.deepEqual(calls,[3]);}});
test('creation is explicit preview token and consent, not client SQL',()=>{const code=readFileSync('src/main/resources/static/js/ontology-pipeline.mjs','utf8');assert.ok(code.includes("post('/pipeline/graph/create',{token,confirmed:true})"));assert.ok(code.includes('preview?.canCreate'));assert.ok(code.includes('consent.input.checked'));assert.ok(!/innerHTML|eval\(|localStorage|sessionStorage/.test(code));});
test('manual editing remains and new dialogs have accessible names',()=>{const html=readFileSync('src/main/resources/templates/ontology.html','utf8'),fragment=readFileSync('src/main/resources/templates/fragments/ontology-relationships.html','utf8');assert.ok(html.includes('aria-labelledby="ontology-pipeline-title"'));for(const name of ['manual','discover','graph'])assert.ok(fragment.includes('data-rel-'+name));});
test('pipeline labels have four languages and matching placeholders',()=>{const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-pipeline.json','utf8'));for(const [key,values]of Object.entries(labels)){assert.equal(values.length,4,key);assert.ok(values.every(v=>v.trim()));for(const value of values)assert.deepEqual(value.match(/\{\d+\}/g),values[0].match(/\{\d+\}/g));}});
