import test from 'node:test';
import assert from 'node:assert/strict';
import {renderRdfWorkflow} from '../../main/resources/static/js/ontology-rdf-workflow.mjs';
const element=tag=>({tag,children:[],listeners:{},dataset:{},append(...v){for(const n of v){if(n.parent)n.parent.children=n.parent.children.filter(c=>c!==n);n.parent=this;this.children.push(n);}},replaceChildren(...v){this.children=[];this.append(...v);},setAttribute(){},addEventListener(k,f){this.listeners[k]=f;}});
const all=n=>[n,...n.children.flatMap(all)];
test('dictionary sources preselected, stale relationship cannot enter SQL, edits invalidate draft',()=>{
 const old=globalThis.document;globalThis.document={createElement:element};
 try{let selected,edits=0;const host=element('section');
  renderRdfWorkflow(host,{sources:[{name:'A',state:'DRAFT',concept:'standard',description:'meaning',revision:1,recommended:true,columns:[]},{name:'B',state:'DRAFT',concept:'business',description:'meaning',revision:1,recommended:true,columns:[]},{name:'C',state:'DRAFT',revision:1,columns:[]}],rules:[{term:'metric',definition:'count distinct',criteria:'COUNT(DISTINCT CODE)'}],relations:[{id:'R',source:'A',target:'B',from:['CODE'],to:['CODE'],status:'STALE',label:'candidate',condition:'uncertain',usable:false}]},v=>selected=v,()=>edits++);
  const inputs=all(host).filter(n=>n.tag==='input');assert.deepEqual(inputs.map(n=>n.checked),[true,true,undefined,false].map(v=>!!v));assert.equal(inputs[3].disabled,true);
  all(host).find(n=>n.tag==='button').listeners.click();assert.deepEqual(selected,{mode:'INDEPENDENT',tables:['A','B'],relations:[]});
  inputs[0].checked=false;inputs[0].listeners.change();assert.equal(edits,1);
  assert.ok(all(host).some(n=>n.textContent==='COUNT(DISTINCT CODE)'));
  const evidence=host.children.find(n=>n.tag==='details');assert.ok(evidence);assert.ok(!evidence.open);
  assert.ok(all(evidence).some(n=>n.textContent==='COUNT(DISTINCT CODE)'));
  assert.ok(!host.children.some(n=>n.textContent==='meaning'));
  assert.ok(host.children.some(n=>n.textContent==='singleSummary'));
 }finally{globalThis.document=old;}
});
test('terms are concise; unrelated candidates stay folded; relation summary follows mode and selection',()=>{
 const old=globalThis.document;globalThis.document={createElement:element};
 try{let selected;const host=element('section');
  renderRdfWorkflow(host,{sources:['A','B','C'].map(name=>({name,state:'APPROVED',revision:1,recommended:name!=='C',columns:[]})),rules:[{term:'Activity',definition:'Daily users. Full conditions remain in evidence.',criteria:'COUNT(*)'}],relations:[{id:'AB',source:'A',target:'B',from:['ID'],to:['ID'],status:'CONFIRMED',label:'verified link',usable:true},{id:'AC',source:'A',target:'C',from:['ID'],to:['ID'],status:'STALE',label:'unrelated',usable:false}]},v=>selected=v);
  assert.equal(host.children[1].children[1].textContent,'Daily users.');
  const evidence=host.children.find(n=>n.tag==='details'),others=all(evidence).find(n=>n.tag==='details'&&n.children[0]?.textContent==='otherRelations');
  assert.ok(all(others).some(n=>n.textContent?.includes('unrelated')));assert.ok(!others.open);
  const mode=all(host).find(n=>n.tag==='select'),submit=all(host).find(n=>n.tag==='button');mode.value='JOIN';mode.listeners.change();assert.equal(submit.disabled,true);
  const relation=all(host).find(n=>n.tag==='input'&&n.value==='AB');relation.checked=true;relation.listeners.change();assert.equal(submit.disabled,false);
  assert.ok(host.children.some(n=>n.textContent?.includes('joinSummary A → B')));submit.listeners.click();assert.deepEqual(selected.relations,['AB']);
  mode.value='INDEPENDENT';mode.listeners.change();submit.listeners.click();assert.deepEqual(selected.relations,[]);assert.ok(host.children.some(n=>n.textContent==='businessSummary'));
 }finally{globalThis.document=old;}
});
