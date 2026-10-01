import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {ImportQueue,importPlan,importable} from '../../main/resources/static/js/ontology-import.mjs';

test('default filters exclude known Oracle and app storage but preserve arbitrary DBC tables',()=>{
  const names=['ORDERS','DBC_APP_RECORD','DR$EXAMPLE$I','VECTOR$IDX$123_456_0$IVF_FLAT_CENTROIDS','EXAMPLE_FEEDBACK_VECINDEX$VECTAB','DBC_EXPERIMENT','ORDERS'];
  const reasons={'DBC_APP_RECORD':'APP_STORE','DR$EXAMPLE$I':'TEXT_NAME','VECTOR$IDX$123_456_0$IVF_FLAT_CENTROIDS':'VECTOR_NAME','EXAMPLE_FEEDBACK_VECINDEX$VECTAB':'FEEDBACK_NAME'};
  const plan=importPlan(names,reasons);assert.deepEqual(plan.selected,['ORDERS','DBC_EXPERIMENT']);assert.equal(plan.excluded.length,4);
  assert.equal(importPlan(names,reasons,{excludeApp:false}).selected.length,3);
  assert.equal(importPlan(names,reasons,{excludeOracle:false}).selected.length,5);
  assert.equal(importPlan(names,reasons,{excludeOracle:false,excludeApp:false}).selected.length,6);
  assert.equal(names.length,7);assert.equal(Object.keys(reasons).length,4);
});
test('manual exclusions persist across filter changes and unknown reasons never hide business objects',()=>{
  const names=['BUSINESS_VIEW','DBC_EXPERIMENT','DBC_APP_RECORD'],reasons={BUSINESS_VIEW:'UNKNOWN',DBC_APP_RECORD:'APP_STORE'};
  const omitted=new Set(['DBC_EXPERIMENT']);let plan=importPlan(names,reasons,{omitted});
  assert.deepEqual(plan.selected,['BUSINESS_VIEW']);assert.deepEqual(plan.available,['BUSINESS_VIEW','DBC_EXPERIMENT']);
  assert.ok(plan.excluded.some(x=>x.table==='DBC_EXPERIMENT'&&x.reason==='MANUAL'));
  plan=importPlan(names,reasons,{omitted,excludeApp:false});assert.deepEqual(plan.selected,['BUSINESS_VIEW','DBC_APP_RECORD']);
});
test('filtered empty plan cannot issue a request and reason lookup does not use prototypes',()=>{
  const names=['DBC_APP_RECORD'],reasons={DBC_APP_RECORD:'APP_STORE'};assert.equal(new ImportQueue(importPlan(names,reasons).selected).next(),null);
  assert.deepEqual(importPlan(['toString','__proto__'],Object.create({toString:'APP_STORE'})).selected,['toString','__proto__']);
});
test('filters lock during requests and single-object import bypasses bulk defaults',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-import.mjs','utf8');
  assert.ok(code.includes("get('filters').disabled=running"));
  assert.ok(code.includes("excludeOracle:bulk&&get('exclude-oracle').checked"));
  assert.ok(code.includes("names=[...new Set(candidateNames)]"));
  const main=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');assert.ok(main.includes('catalog.importExclusions??{},table===null'));
  const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-import-filter.json','utf8'));
  for(const [key,values] of Object.entries(labels)){assert.equal(values.length,4,key);for(const v of values){assert.ok(v.trim());assert.deepEqual(v.match(/\{\d+\}/g)??[],values[0].match(/\{\d+\}/g)??[]);}}
});

test('queue freezes and deduplicates reviewed names; only one request can be active',()=>{
  const names=['A','A','b'],q=new ImportQueue(names);names.push('NEW');
  assert.deepEqual(q.items.map(i=>i.table),['A','b']);
  assert.equal(q.next(),'A');assert.equal(q.next(),null);
  assert.equal(q.items[0].outcome,'RUNNING');
  q.accept({table:'A',outcome:'IMPORTED',revision:1,triples:25});
  assert.equal(q.next(),'b');
  q.accept({table:'b',outcome:'SKIPPED',revision:3,triples:40});
  assert.equal(q.complete,true);assert.equal(q.next(),null);
  assert.equal(q.items[1].revision,3);
});
test('stop allows in-flight response but never starts another table',()=>{
  const q=new ImportQueue(['A','B']);q.next();q.stop();
  q.accept({table:'A',outcome:'IMPORTED',revision:1,triples:10});
  assert.equal(q.next(),null);assert.equal(q.complete,false);
  assert.equal(q.items[0].outcome,'IMPORTED');assert.equal(q.items[1].outcome,'PENDING');
});
test('response loss is unconfirmed, not failed or retried; successes are retained',()=>{
  const q=new ImportQueue(['A','B','C']);q.next();q.accept({table:'A',outcome:'IMPORTED',revision:1,triples:10});
  q.next();q.fail();assert.equal(q.next(),null);assert.equal(q.index,1);
  assert.deepEqual(q.items.map(i=>i.outcome),['IMPORTED','UNKNOWN','PENDING']);
});
test('empty plan cannot issue a request',()=>{const q=new ImportQueue([]);assert.equal(q.next(),null);assert.equal(q.complete,true);});
test('mismatched, duplicate and malformed responses are never counted as success',()=>{
  const q=new ImportQueue(['A']);q.next();
  for(const r of [null,{}, {table:'B',outcome:'IMPORTED',revision:1,triples:20},
    {table:'A',outcome:'FAIL',revision:1,triples:20},{table:'A',outcome:'IMPORTED',revision:0,triples:20},
    {table:'A',outcome:'IMPORTED',revision:1,triples:0}])assert.throws(()=>q.accept(r));
  assert.equal(q.index,0);q.accept({table:'A',outcome:'IMPORTED',revision:1,triples:20});
  assert.throws(()=>q.accept({table:'A',outcome:'IMPORTED',revision:1,triples:20}));
});
test('import modal uses explicit start, serial POST, safe text and no native confirmation',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-import.mjs','utf8');
  assert.ok(code.includes("get('start').addEventListener('click',async"));
  assert.ok(code.includes("await post(save?'/import/apply':'/import/preview'"));
  assert.ok(code.includes('token:previews.get(table).token,confirmed:true'));
  assert.ok(code.includes('await completed()'));assert.ok(code.includes('dialog.showModal()'));
  assert.ok(code.includes('queue.fail()'));assert.ok(code.includes('if(running)e.preventDefault()'));
  assert.ok(!/window.confirm|innerHTML|setInterval|localStorage|sessionStorage|Promise.all/.test(code));
  const main=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');
  assert.ok(main.includes("if(dirty){message(label('import.saveFirst')"));
  assert.ok(main.includes('await load(true);'));assert.ok(main.includes("!get('import-dialog').contains(e)"));
});
test('preview queue requires valid comparison results and does not write',()=>{
  const q=new ImportQueue(['A','B','C'],'preview');q.next();
  assert.throws(()=>q.accept({table:'A',outcome:'CHANGED',revision:1,differences:[]}));
  q.accept({table:'A',outcome:'CHANGED',revision:1,differences:[],token:'checked'});
  q.next();assert.throws(()=>q.accept({table:'B',outcome:'UNCHANGED',revision:4,differences:[],token:''}));
  q.accept({table:'B',outcome:'UNCHANGED',revision:4,differences:[],token:'recapture'});
  q.next();q.accept({table:'C',outcome:'REVIEW',revision:2,differences:[],token:'',reason:'removed'});
  assert.equal(q.complete,true);assert.deepEqual(q.items.map(i=>i.outcome),['CHANGED','UNCHANGED','REVIEW']);
});
test('unchanged selected objects are importable; review-only and failed objects are not',()=>{
  assert.deepEqual(['NEW','CHANGED','UNCHANGED','REVIEW','UNKNOWN','PENDING'].filter(importable),['NEW','CHANGED','UNCHANGED']);
  const source=readFileSync('src/main/resources/static/js/ontology-import.mjs','utf8');
  assert.ok(source.includes('filter(i=>importable(i.outcome))'));assert.ok(source.includes('filter(p=>importable(p.outcome))'));
  assert.ok(!source.includes("label('counts'"));
});
test('profile filter rejects stale responses and clears previews whenever scope changes',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-import.mjs','utf8');
  assert.ok(code.includes('if(version!==generation)return'));assert.ok(code.includes('scopedNames=profile?[]:names'));
  assert.ok(code.includes('profileTables(names.map(name=>({name})),schema,data.objectList)'));
  assert.ok(code.includes('previews=new Map();finished=false;'));assert.ok(code.includes("new ImportQueue(selection.selected,'preview')"));
});
test('refresh labels are translated with identical parameters',()=>{
  const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-import-refresh.json','utf8'));
  for(const [key,values] of Object.entries(labels)){assert.equal(values.length,4,key);for(const value of values){assert.ok(value.trim());assert.deepEqual(value.match(/\{\d+\}/g)??[],values[0].match(/\{\d+\}/g)??[]);}}
});
test('bulk import labels are present in all four languages with matching parameters',()=>{
  const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-import.json','utf8'));
  for(const [key,values] of Object.entries(labels)){
    assert.equal(values.length,4,key);
    const params=v=>v.match(/\{\d+\}/g)??[];
    for(const value of values){assert.ok(value.trim(),key);assert.deepEqual(params(value),params(values[0]),key);}
  }
});
