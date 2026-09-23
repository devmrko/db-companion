import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {entriesPage,editable,captureChoices,savePayload} from '../../main/resources/static/js/ontology.mjs';
test('catalog contains search and ten-row pagination use saved definitions',()=>{const rows=Array.from({length:23},(_,i)=>({name:'T'+i,state:i%2?'APPROVED':'DRAFT',actor:'OWNER'}));assert.equal(entriesPage(rows,'',2).items.length,10);assert.equal(entriesPage(rows,'',3).items.length,3);assert.equal(entriesPage(rows,'approved',1).total,11);assert.equal(entriesPage(rows,'owner',1).total,23);});
test('already captured tables are not silently recaptured',()=>{assert.deepEqual(captureChoices([{name:'A'},{name:'B'}],[{name:'B'}]),[{name:'A'}]);});
test('old versions cannot be edited and save request carries only meaning',()=>{const e={revision:3,document:{source:{table:'T'}}},meaning={concept:'x'};assert.equal(editable(e,4),false);assert.equal(editable(e,3),true);assert.deepEqual(savePayload('APP',e,meaning,'DRAFT'),{schema:'APP',table:'T',revision:3,meaning,state:'DRAFT'});});
test('client keeps explicit creation and in-app import confirmation and renders text safely',()=>{const source=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');assert.ok(source.includes("window.confirm(label('installConfirm')"));assert.ok(!source.includes("window.confirm(label('captureConfirm')"));assert.ok(source.includes('importer.open(names'));assert.ok(source.includes('assistantPost'));assert.ok(source.includes('aiSpent=true'));assert.ok(source.includes("get('ai-source').textContent"));assert.ok(!/innerHTML|eval\(|localStorage|sessionStorage|setInterval/.test(source));assert.ok(source.includes("post('/ai/apply'"));assert.ok(source.includes("work(()=>load())"));});
test('all ontology labels have four reviewed languages',()=>{const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology.json','utf8'));for(const [key,values] of Object.entries(labels)){assert.equal(values.length,4,key);assert.ok(values.every(v=>typeof v==='string'&&v.trim()),key);}assert.ok(labels['ontology.help'][0].includes('별도 동의'));});
test('only explicit refresh reloads table comments; saves invalidate views without discarding metadata',()=>{
  const source=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');
  assert.equal((source.match(/load\(true\)/g)||[]).length,1);
  assert.match(source,/async function refresh\(\)[^\n]+await load\(true\)/);
  assert.ok(source.includes('async function reloadSaved(){erd.invalidate();relationships.invalidate();await load();}'));
  assert.ok(source.includes('completed:reloadSaved'));
});
