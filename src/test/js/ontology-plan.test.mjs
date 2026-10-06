import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {renderPlan,blockedSources} from '../../main/resources/static/js/ontology-plan.mjs';
function element(tag){return {tag,children:[],listeners:{},dataset:{},value:'',append(...items){this.children.push(...items);},replaceChildren(...items){this.children=items;},setAttribute(){},addEventListener(name,fn){(this.listeners[name]??=[]).push(fn);},fire(name){for(const fn of this.listeners[name]??[])fn();}};}
const all=n=>[n,...n.children.flatMap(all)];
test('AI preselects sources and reference candidates, user may add or remove without approval mutation',()=>{
  const old=globalThis.document;globalThis.document={createElement:element};
  try{
    const host=element('section');let submitted,edits=0;
    const plan={mode:'INDEPENDENT',routeId:'',tables:['A','B'],candidates:[{id:'C1',use:'REFERENCE',reason:'reference only',selected:true}],reason:'separate',questions:[]};
    const candidates=[{id:'C1',source:'A',target:'B',status:'STALE',label:'candidate'}];
    renderPlan(host,plan,{routes:[]},candidates,[{name:'A',state:'APPROVED'},{name:'B',state:'APPROVED'},{name:'C',state:'APPROVED'}],v=>{submitted=v;},()=>edits++);
    const inputs=all(host).filter(e=>e.tag==='input');assert.deepEqual(inputs.map(e=>e.checked),[true,true,false,true]);
    inputs[1].checked=false;inputs[1].fire('change');inputs[2].checked=true;inputs[2].fire('change');inputs[3].checked=false;inputs[3].fire('change');
    all(host).find(e=>e.tag==='button').fire('click');assert.deepEqual(submitted,{mode:'INDEPENDENT',route:'',tables:['A','C'],candidates:[]});assert.equal(edits,3);assert.equal(candidates[0].status,'STALE');
  }finally{globalThis.document=old;}
});
test('automatic recommendation only opens consent preview, generation and execution stay explicit',()=>{
  const source=readFileSync('src/main/resources/static/js/ontology-query.mjs','utf8');
  assert.match(source,/if\(automatic.checked\)\{showPreview\(await post\(analysisEndpoint\('ai-interpret-preview'/);
  assert.doesNotMatch(source,/post\('ai-recommend-preview'/);
  assert.match(source,/candidates:value.plan.candidates.filter\(c=>c.selected\)/);
  assert.match(source,/consent:get\('consent'\).checked/);
  assert.match(source,/routeId='';invalidate\(\)/);
  assert.doesNotMatch(readFileSync('src/main/resources/static/js/ontology-plan.mjs','utf8'),/innerHTML|localStorage|\/execute/);
});
test('draft recommendation is visible and preselected but cannot apply or silently approve',()=>{
  const old=globalThis.document;globalThis.document={createElement:element};
  try{
    const host=element('section');let calls=0;
    const plan={mode:'INDEPENDENT',routeId:'',tables:['A','B'],candidates:[],reason:'aggregate separately',questions:[]};
    const sources=[{name:'A',state:'DRAFT'},{name:'B',state:'DRAFT'},{name:'C',state:'APPROVED'}];
    assert.deepEqual(blockedSources(plan,sources),['A','B']);
    renderPlan(host,plan,{routes:[]},[],sources,()=>calls++);
    assert.equal(host.hidden,false);const submit=all(host).find(e=>e.tag==='button');assert.equal(submit.disabled,true);submit.fire('click');assert.equal(calls,0);
    const inputs=all(host).filter(e=>e.tag==='input');assert.deepEqual(inputs.map(e=>e.checked),[true,true,false]);
    for(const input of inputs){input.checked=input.value==='C';input.fire('change');}
    assert.equal(submit.disabled,false);submit.fire('click');assert.equal(calls,1);assert.equal(sources[0].state,'DRAFT');
  }finally{globalThis.document=old;}
});
