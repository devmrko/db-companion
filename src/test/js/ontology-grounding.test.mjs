import test from 'node:test';
import assert from 'node:assert/strict';
import {renderDictionaryChoices,renderGroundedResult,automaticTermIds} from '../../main/resources/static/js/ontology-grounding.mjs';
test('no matches and unambiguous matches proceed without an AI gate',()=>{
  assert.deepEqual(automaticTermIds({targets:[],hits:[],more:false}),[]);
  assert.deepEqual(automaticTermIds({targets:[{termIds:['a']},{termIds:['a']}],hits:[{term:{id:'a'}}]}),['a']);
  assert.equal(automaticTermIds({targets:[{termIds:['a','b']}],hits:[]}),null);
  assert.equal(automaticTermIds({targets:[],hits:[],more:true}),null);
  assert.equal(automaticTermIds({targets:[{termIds:['missing']}],hits:[]}),null);
});
function element(tag){return {tag,children:[],listeners:{},value:'',textContent:'',append(...v){this.children.push(...v);},replaceChildren(...v){this.children=v;},setAttribute(){},addEventListener(k,v){this.listeners[k]=v;},focus(){this.focused=true;}};}
const all=n=>[n,...n.children.flatMap(all)];
test('RDF results display draft source, revision and triples without demanding a relationship selection',()=>{
  const old=globalThis.document;globalThis.document={createElement:element};
  try{const host=element('section');renderGroundedResult(host,{rdf:{terms:['activity'],hits:[{table:'USERS',state:'DRAFT',revision:2,versionIri:'urn:test/revision/2',terms:['activity'],triples:[{subject:'urn:test/table',predicate:'urn:businessConcept',object:{value:'<script>activity</script>'}}]}],limited:false}});
    assert.ok(all(host).some(n=>n.textContent==='USERS · DRAFT · v2'));
    assert.ok(all(host).some(n=>n.textContent.includes('<script>activity</script>')));
    assert.ok(all(host).every(n=>n.tag!=='script'&&n.tag!=='select'));
  }finally{globalThis.document=old;}
});
test('ambiguous dictionary meanings require selection before continuing',()=>{
  const old=globalThis.document;globalThis.document={createElement:element};
  try{const host=element('section');let selected;
    renderDictionaryChoices(host,{question:'AU',targets:[{expression:'AU',termIds:['a','b']}],hits:[{term:{id:'a',term:'Active',definition:'active',criteria:''}},{term:{id:'b',term:'Authorized',definition:'authorized',criteria:''}}]},ids=>{selected=ids;});
    const select=all(host).find(n=>n.tag==='select'),button=all(host).find(n=>n.tag==='button');button.listeners.click();assert.equal(selected,undefined);assert.equal(select.focused,true);
    select.value='b';button.listeners.click();assert.deepEqual(selected,['b']);
  }finally{globalThis.document=old;}
});
test('interpretation and graph SQL render as text, without executing embedded markup',()=>{
  const old=globalThis.document;globalThis.document={createElement:element};
  try{const host=element('section');renderGroundedResult(host,{interpretation:{interpreted:'<script>bad()</script>'},proposals:[],graph:{name:'G',rows:[],sql:'SELECT ? FROM dual',parameters:['T']}});
    assert.ok(all(host).some(n=>n.textContent==='<script>bad()</script>'));assert.ok(all(host).some(n=>n.textContent==='SELECT ? FROM dual'));assert.ok(all(host).every(n=>n.tag!=='script'));
  }finally{globalThis.document=old;}
});
