import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {preparationBlockers} from '../../main/resources/static/js/select-ai-test.mjs';
import {mountEvidence} from '../../main/resources/static/js/select-ai-evidence.mjs';

test('all blocking reasons are visible; optional evidence can be disabled independently',()=>{
  const blocked=preparationBlockers('', 'question', false, false, false);
  assert.equal(blocked.length,3);assert.match(blocked[0],/A\/B/);
  assert.deepEqual(preparationBlockers('P','question',false,true,true),[]);
  assert.equal(preparationBlockers('P','question',false,false,true).length,1);
  assert.equal(preparationBlockers('P','question',false,true,false).length,1);
  assert.equal(preparationBlockers('P','question',true,true,true).length,1);
  assert.equal(preparationBlockers('P','',false,true,true).length,1);
});

// Actual mount function, synthetic DOM and HTTP. Not an interactive-browser test.
class Node {
  constructor(tag='div'){this.tag=tag;this.children=[];this.handlers={};this.value='';this.checked=false;this.textContent='';}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.children=nodes;}
  addEventListener(name,fn){this.handlers[name]=fn;}
  async fire(name){if(!this.disabled)await this.handlers[name]?.();}
  querySelectorAll(tag){return this.children.flatMap(n=>[...(n.tag===tag?[n]:[]),...n.querySelectorAll(tag)]);}
  set innerHTML(value){throw Error('Unsafe HTML');}
}
test('two independent definitions are applied explicitly and edits invalidate readiness',async()=>{
  const saved={document:globalThis.document,Option:globalThis.Option,fetch:globalThis.fetch};
  globalThis.document={createElement:tag=>new Node(tag)};
  globalThis.Option=class extends Node {constructor(text,value){super('option');this.textContent=text;this.value=value;}};
  const rows=[{name:'A',concept:'First',state:'APPROVED'},{name:'B',concept:'Second',state:'APPROVED'},{name:'C',concept:'Draft',state:'DRAFT'}];
  globalThis.fetch=async()=>new Response(JSON.stringify({tables:rows,checkedAt:'2028-01-01'}),{headers:{'Content-Type':'application/json'}});
  const nodes=new Map(),get=name=>{if(!nodes.has(name))nodes.set(name,new Node());return nodes.get(name);};
  get('enabled').checked=true;get('schema').value='APP';let busy=false,component;const calls=[];
  try{
    component=mountEvidence({querySelector:s=>s==='[data-question-analysis]'?null:get(s.match(/data-test-evidence-([^\]]+)/)[1])},{
      isBusy:()=>busy,setBusy:v=>{busy=v;component.controls(v);},changed:()=>component?.controls(busy),message:()=>{},question:()=> 'q',
      post:async(path,value)=>{calls.push({path,value});return {schema:'APP',question:'q',route:'DEFINITIONS',hash:'h',references:value.tables.map(table=>({table,revision:1})),source:'{"evidence":[]}'};}
    });
    await component.restore({schemas:['APP'],evidenceSchema:'APP'});component.controls(false);
    const checks=get('definitions').querySelectorAll('input');assert.equal(checks.length,2);assert.equal(component.ready(),false);
    for(const input of checks){input.checked=true;await input.fire('change');}
    assert.equal(calls.length,0);assert.equal(get('definitions-apply').disabled,false);
    await get('definitions-apply').fire('click');assert.equal(component.ready(),true);
    assert.deepEqual(calls,[{path:'evidence/definitions',value:{schema:'APP',question:'q',tables:['A','B']}}]);
    checks[0].checked=false;await checks[0].fire('change');assert.equal(component.ready(),false);
    component.invalidate();assert.equal(get('definitions-apply').disabled,true);
    get('enabled').checked=false;assert.equal(component.ready(),true);
  }finally{for(const [key,value] of Object.entries(saved)){if(value===undefined)delete globalThis[key];else globalThis[key]=value;}}
});
test('new guidance exists in each supported locale and uses accessible bounded layout',()=>{
  const keys=['readyProfile','readyQuestion','readyGlossary','readyOntology','evDefinitionsTitle','evDefinitionsHelp','evDefinitionsApply','evNoPathDefinitions'];
  for(const suffix of ['','_ko','_en','_ja','_zh_CN']){const source=readFileSync(`src/main/resources/i18n/messages${suffix}.properties`,'utf8');for(const key of keys)assert.ok(source.includes('aitest.'+key+'='));}
  const html=readFileSync('src/main/resources/templates/ai-test.html','utf8');assert.match(html,/aria-live="polite" data-test-readiness/);assert.match(html,/data-test-evidence-definitions-apply disabled/);
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');assert.match(css,/app-evidence-definition-list[^\n]+max-height: 280px/);
});
