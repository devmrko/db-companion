import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {ImportQueue} from '../../main/resources/static/js/ontology-import.mjs';

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
  assert.ok(code.includes("await post('/capture/missing',{schema,table,confirmed:true})"));
  assert.ok(code.includes('await completed()'));assert.ok(code.includes('dialog.showModal()'));
  assert.ok(code.includes('queue.fail()'));assert.ok(code.includes('if(running)e.preventDefault()'));
  assert.ok(!/window.confirm|innerHTML|setInterval|localStorage|sessionStorage|Promise.all/.test(code));
  const main=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');
  assert.ok(main.includes("if(dirty){message(label('import.saveFirst')"));
  assert.ok(main.includes('await load(true);'));assert.ok(main.includes("!get('import-dialog').contains(e)"));
});
test('bulk import labels are present in all four languages with matching parameters',()=>{
  const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-import.json','utf8'));
  for(const [key,values] of Object.entries(labels)){
    assert.equal(values.length,4,key);
    const params=v=>v.match(/\{\d+\}/g)??[];
    for(const value of values){assert.ok(value.trim(),key);assert.deepEqual(params(value),params(values[0]),key);}
  }
});
