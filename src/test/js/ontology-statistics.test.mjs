import test from 'node:test';
import assert from 'node:assert/strict';
import {renderStatistics,statisticsEditor} from '../../main/resources/static/js/ontology-statistics.mjs';
const element=tag=>({tag,children:[],dataset:{},listeners:{},value:'',append(...v){this.children.push(...v);},replaceChildren(...v){this.children=v;},add(n){this.children.push(n);if(!this.value)this.value=n.value;},setAttribute(){},addEventListener(k,f){this.listeners[k]=f;}});
const all=n=>[n,...n.children.flatMap(all)];
test('statistics require separate consent, reset on column change, and never save mappings',async()=>{
 const old=globalThis.document,opt=globalThis.Option;globalThis.document={createElement:element};globalThis.Option=function(text,value){return {textContent:text,value,children:[]};};
 try{let request;const host=element('div'),entry={revision:3,document:{source:{schema:'APP',table:'T',columns:[{name:'FLAG',dataType:'VARCHAR2'},{name:'DAY',dataType:'DATE'},{name:'SECRET',dataType:'VARCHAR2'}]}}},meaning={columns:{SECRET:{sensitivity:'SENSITIVE'}},valueMappings:[]};
  statisticsEditor(host,{entry,meaning,run:f=>f(),statistics:async r=>{request=r;return {checkedAt:'now',columns:[]};}});
  const select=all(host).find(n=>n.tag==='select'),check=all(host).find(n=>n.tag==='input'),button=all(host).find(n=>n.tag==='button');
  assert.deepEqual(select.children.map(c=>c.value),['FLAG','DAY']);assert.equal(button.disabled,true);await button.listeners.click();assert.equal(request,undefined);
  check.checked=true;check.listeners.change();await button.listeners.click();assert.deepEqual(request,{schema:'APP',table:'T',revision:3,column:'FLAG',confirmed:true});assert.deepEqual(meaning.valueMappings,[]);
  select.value='DAY';select.listeners.change();assert.equal(check.checked,false);assert.equal(button.disabled,true);
 }finally{globalThis.document=old;globalThis.Option=opt;}
});
test('renderer shows observed denominator, hidden count and quoted values safely',()=>{
 const old=globalThis.document;globalThis.document={createElement:element};try{const host=element('div');renderStatistics(host,{checkedAt:'now',columns:[{name:'CODE',dataType:'VARCHAR2',observed:4,nulls:1,withheld:1,distinctObserved:1,topValues:[{value:' <b> ',count:2}]}]});
  assert.ok(all(host).some(n=>n.textContent==='25.00%'));assert.ok(all(host).some(n=>n.tag==='code'&&n.textContent==='" <b> "'));assert.ok(all(host).some(n=>n.textContent==='bounded'));
 }finally{globalThis.document=old;}
});
