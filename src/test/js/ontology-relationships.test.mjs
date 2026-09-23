import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {relationPage,relationElements,reviewPayload,revealAnalysis,unconnectedTables} from '../../main/resources/static/js/ontology-relationships.mjs';
const tables=[{name:'ORDERS',documentId:'source',revision:2,state:'DRAFT',concept:'주문'},{name:'CUSTOMER',documentId:'target',revision:1,state:'DRAFT',concept:'고객'},{name:'UNRELATED',revision:1,state:'DRAFT'}];
const relation=(status='CANDIDATE',id='candidate')=>({id,source:'ORDERS',targetSchema:'APP',target:'CUSTOMER',sourceColumns:['BUYER_NO'],targetColumns:['CUSTOMER_ID'],status,origin:'RULE',label:'고객을 참조한다'});
const data={schema:'APP',tables,relations:[relation()]};
test('isolated tables use serialized string statuses and preserve cross-schema boundaries',()=>{
  assert.deepEqual(unconnectedTables(data,'APP'),['ORDERS','CUSTOMER','UNRELATED']);
  assert.deepEqual(unconnectedTables({...data,relations:[relation('APPROVED')]},'APP'),['UNRELATED']);
  assert.deepEqual(unconnectedTables({...data,relations:[{...relation('FK'),targetSchema:'OTHER'}]},'APP'),['CUSTOMER','UNRELATED']);
});
test('different names and original intermediate tables are preserved in graph nodes and edges',()=>{
  const elements=relationElements(data);assert.equal(elements.length,4);assert.equal(elements.filter(v=>v.data.source).length,1);
  assert.ok(elements[0].data.label.includes('주문'));assert.ok(elements[0].data.label.includes('ORDERS'));
  assert.equal(elements[3].classes,'candidate');assert.equal(elements[3].data.source,JSON.stringify(['APP','ORDERS']));
});
test('foreign keys, approved, rejected, stale and boundary relationships have distinct rendering',()=>{
  const fk={...relation('FK','fk'),origin:'FK',key:{name:'FK1',status:'DISABLED',validated:'VALIDATED'}};
  const elements=relationElements({...data,relations:[fk,relation('APPROVED','a'),relation('REJECTED','r'),relation('STALE','s'),{...relation('CANDIDATE','b'),targetSchema:'OTHER'}]});
  const edges=elements.filter(v=>v.data.source);assert.equal(edges.length,4);assert.ok(!edges.some(e=>e.data.id==='r'));
  assert.equal(edges[0].classes,'fk unchecked');assert.equal(edges[1].classes,'approved');assert.equal(edges[2].classes,'stale');assert.equal(elements.filter(e=>e.classes==='boundary').length,1);
});
test('search, ten-row pagination and review status do not lose rejected entries',()=>{
  const many={...data,relations:Array.from({length:23},(_,i)=>relation(i%2?'REJECTED':'CANDIDATE',String(i)))};
  assert.equal(relationPage(many,'buyer_no','',2).items.length,10);assert.equal(relationPage(many,'customer_id','',99).items.length,3);
  assert.equal(relationPage(many,'','REJECTED').total,11);assert.equal(relationPage(many,'missing').total,0);assert.equal(relationPage(many,'missing').page,1);
});
test('review payload uses exact selected versions and pairs, never a client actor or arbitrary SQL',()=>{
  const request=reviewPayload(data,relation(),{label:'references',condition:'memo only',status:'APPROVED'});
  assert.equal(request.sourceRevision,2);assert.equal(request.targetRevision,1);assert.deepEqual(request.sourceColumns,['BUYER_NO']);assert.deepEqual(request.targetColumns,['CUSTOMER_ID']);
  assert.ok(!('actor' in request));assert.ok(!('evidence' in request));assert.ok(!('sql' in request));
  assert.throws(()=>reviewPayload(data,{...relation(),targetSchema:'OTHER'},{}));
});
test('new workflow is explicit, local-text-only and invalidated after writes and refresh',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-relationships.mjs','utf8'),parent=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');
  assert.ok(!/innerHTML|eval\(|localStorage|sessionStorage|ai\/generate/.test(code));assert.ok(code.includes('textContent'));
  assert.ok(code.includes("post('/relationships/review'"));assert.ok(code.includes('candidateId:relation.id'));
  assert.ok(parent.includes("get('analyze').addEventListener('click'"));assert.ok(parent.includes('relationships.invalidate()'));assert.ok(code.includes('version!==epoch'));
  const html=readFileSync('src/main/resources/templates/fragments/ontology-relationships.html','utf8');assert.ok(html.includes('aria-live="polite"'));assert.ok(html.includes('data-rel-rows'));
});
test('four-language labels and placeholder counts are complete',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-relationships.mjs','utf8');const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-relationships.json','utf8'));
  for(const match of code.matchAll(/label\('([^']+)'/g))if(match[1]!=='evidence.')assert.ok(labels['ontology.relationships.'+match[1]],match[1]);
  for(const [key,values] of Object.entries(labels)){assert.equal(values.length,4,key);assert.ok(values.every(v=>v.trim()));for(const value of values)assert.deepEqual(value.match(/\{\d+\}/g),values[0].match(/\{\d+\}/g));}
});
test('reopen keeps cached data and unsaved edits while restoring focus and reporting reuse',async()=>{
  const events=[],editor={label:'unsaved input'};let cached=data;
  await revealAnalysis({hasData:()=>cached!==null,resetFilters:()=>events.push('reset'),show:async()=>events.push('show'),focus:()=>events.push('focus100'),announce:reused=>events.push(reused)});
  assert.deepEqual(events,['reset','show','focus100',true]);assert.equal(cached,data);assert.equal(editor.label,'unsaved input');
});
test('first load waits for results before announcing and a failed load never reports completion',async()=>{
  let ready=false;const events=[];
  await revealAnalysis({hasData:()=>ready,resetFilters:()=>events.push('reset'),show:async()=>{events.push('load');ready=true;},focus:()=>events.push('focus'),announce:reuse=>events.push(reuse)});
  assert.deepEqual(events,['reset','load','focus',false]);
  let announced=false;
  await assert.rejects(revealAnalysis({hasData:()=>false,resetFilters:()=>{},show:async()=>{throw new Error('read error');},focus:()=>{throw new Error('unexpected focus');},announce:()=>announced=true}),/read error/);
  assert.equal(announced,false);
});
test('action is separate from navigation and explicit results do not re-fetch or discard editors',()=>{
  const html=readFileSync('src/main/resources/templates/ontology.html','utf8');
  assert.match(html,/data-on-views hidden>[\s\S]*?<div class="app-dds-tabs">(?:(?!<\/div>)[\s\S])*?<\/div>\s*<button[^>]+data-on-analyze/);
  assert.ok(html.includes('aria-controls="ontology-relationships"'));
  const fragment=readFileSync('src/main/resources/templates/fragments/ontology-relationships.html','utf8');
  assert.ok(fragment.includes('tabindex="-1"'));assert.ok(fragment.includes('data-rel-result role="status" aria-live="polite"'));
  const code=readFileSync('src/main/resources/static/js/ontology-relationships.mjs','utf8');
  const action=code.slice(code.indexOf('async function all()'));
  assert.ok(action.includes('scrollIntoView'));assert.ok(action.includes('root.focus({preventScroll:true})'));assert.ok(action.includes('data.checkedAt'));
  assert.ok(!/fetch\(|post\(|invalidate\(|dirty=false|draw\(/.test(action));
  assert.ok(code.includes('userZoomingEnabled:false'));assert.ok(code.includes("get('in').addEventListener"));assert.ok(code.includes("get('out').addEventListener"));
});
