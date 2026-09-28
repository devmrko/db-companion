import test from 'node:test';
import assert from 'node:assert/strict';
import {mountBusinessGlossary} from '../../main/resources/static/js/business-glossary.mjs';
import {mountEvidence} from '../../main/resources/static/js/select-ai-evidence.mjs';

// Executes the actual UI modules with synthetic DOM and HTTP; not a browser screenshot test.
class Node {
  constructor(tag='div'){this.tag=tag;this.children=[];this.handlers={};this.value='';this.checked=false;this.textContent='';this.dataset={};this.fields=new Map();}
  get childNodes(){return this.children;}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.children=nodes;}
  addEventListener(name,fn){this.handlers[name]=fn;}
  async fire(name){if(!this.disabled)await this.handlers[name]?.();}
  querySelectorAll(tag){return this.children.flatMap(n=>[...(n.tag===tag?[n]:[]),...n.querySelectorAll(tag)]);}
  querySelector(selector){if(selector==='[data-question-analysis]')return null;if(!this.fields.has(selector))this.fields.set(selector,new Node());return this.fields.get(selector);}
  set innerHTML(value){throw Error('Unsafe HTML');}
}
const texts=n=>[n.textContent,...n.children.flatMap(texts)].join('\n');
async function dom(work){
  const saved={document:globalThis.document,Option:globalThis.Option,fetch:globalThis.fetch};
  globalThis.document={createElement:tag=>new Node(tag)};
  globalThis.Option=class extends Node {constructor(text,value){super('option');this.textContent=text;this.value=value;}};
  try{await work();}finally{for(const [key,value] of Object.entries(saved)){if(value===undefined)delete globalThis[key];else globalThis[key]=value;}}
}
const response=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
const term=i=>({term:{id:String(i),revision:1,term:'Metric '+i,aliases:[],definition:'<untrusted>',criteria:'',enabled:true},kind:'TEXT',expressions:[]});

test('glossary toggle searches and attaches all hits without per-definition selection; zero, limit and error differ',async()=>dom(async()=>{
  const root=new Node(),host=root.querySelector('[data-business-glossary]'),get=n=>host.querySelector(`[data-glossary-${n}]`);
  let q='Show metrics for August',p='P',busy=false,component,count=2,fail=false;const calls=[];
  globalThis.fetch=async(url,options)=>{
    if(url.endsWith('/status'))return response({table:'READY',text:'READY'});
    calls.push({url,data:JSON.parse(options.body)});
    if(fail)return response({error:'Text search unavailable'},500);
    return response({id:'s',question:q,profile:p,mode:'ORACLE_TEXT',targets:[],tokens:['SHOW','METRICS','FOR','AUGUST'].map(token=>({token})),hits:Array.from({length:count},(_,i)=>term(i)),expires:'2099-01-01',more:false});
  };
  component=mountBusinessGlossary(root,{csrf:{dataset:{csrfHeader:'X-CSRF'},value:'synthetic'},question:()=>q,profile:()=>p,busy:()=>busy,setBusy:v=>{busy=v;component.controls();},changed:()=>component?.controls(),message:()=>{}});
  await component.load();get('enabled').checked=true;await get('enabled').fire('change');
  assert.equal(calls.length,1);assert.equal(component.ready(),true);
  assert.deepEqual(component.request().glossary.termIds,['0','1']);assert.equal(get('results').querySelectorAll('input').length,0);
  assert.match(texts(get('results')),/SHOW/);assert.match(texts(get('results')),/FOR/);assert.match(texts(get('results')),/AUGUST/);assert.match(texts(get('results')),/<untrusted>/);
  p='OTHER';component.invalidate();assert.equal(component.ready(),false);assert.equal(get('results').children.length,0);
  count=0;await get('find').fire('click');assert.equal(component.ready(),true);assert.deepEqual(component.request().glossary.termIds,[]);assert.match(get('note').textContent,/없이/);
  for(const size of [11,30]){
    count=size;await get('find').fire('click');assert.equal(component.ready(),true);
    assert.deepEqual(component.request().glossary.termIds,Array.from({length:size},(_,i)=>String(i)));
    assert.equal(get('count').textContent,`자동 첨부 정의 ${size} / 30개`);
    assert.equal(get('results').querySelectorAll('input').length,0);
  }
  count=31;await get('find').fire('click');assert.equal(component.ready(),false);assert.match(get('note').textContent,/한도/);
  fail=true;await get('find').fire('click');assert.equal(component.ready(),false);assert.match(get('note').textContent,/unavailable/);
  get('enabled').checked=false;await get('enabled').fire('change');assert.equal(component.ready(),true);
  assert.ok(calls.every(c=>c.url==='/business-glossary/search'));
}));

test('late glossary response cannot revive definitions for a changed question',async()=>dom(async()=>{
  const root=new Node(),host=root.querySelector('[data-business-glossary]'),get=n=>host.querySelector(`[data-glossary-${n}]`);
  let q='old',busy=false,component,release;
  globalThis.fetch=async url=>url.endsWith('/status')?response({table:'READY',text:'READY'}):await new Promise(resolve=>{release=()=>resolve(response({id:'s',question:'old',profile:'P',mode:'ORACLE_TEXT',targets:[],tokens:[],hits:[term(0)],expires:'2099-01-01'}));});
  component=mountBusinessGlossary(root,{csrf:{dataset:{csrfHeader:'X-CSRF'},value:'synthetic'},question:()=>q,profile:()=> 'P',busy:()=>busy,setBusy:v=>{busy=v;component.controls();},changed:()=>component?.controls(),message:()=>{}});
  await component.load();get('enabled').checked=true;const pending=get('enabled').fire('change');q='new';component.invalidate();release();await pending;
  assert.equal(component.ready(),false);assert.equal(get('results').children.length,0);
}));

test('ontology search displays native tokens and auto-attaches matched approved definitions without choosing a route',async()=>dom(async()=>{
  const root=new Node(),get=n=>root.querySelector(`[data-test-evidence-${n}]`);let busy=false,component,empty=false,limited=false;
  const rows=['A','B'].map(name=>({name,state:'APPROVED',concept:name}));rows.push({name:'C',state:'DRAFT',concept:'C'});
  globalThis.fetch=async()=>response({tables:rows,checkedAt:'2028-01-01'});const calls=[];
  get('schema').value='APP';get('enabled').checked=true;
  component=mountEvidence(root,{isBusy:()=>busy,setBusy:v=>{busy=v;component.controls(v);},changed:()=>component?.controls(busy),message:()=>{},question:()=> 'q',post:async(path,value)=>{
    calls.push({path,value});
    if(path==='evidence/search?language=ko')return {id:'s',routes:[],limited,analysis:{mode:'ORACLE_TEXT',tokens:[{token:'SHOW'},{token:'AUGUST'}]},concepts:empty?[]:[{term:'metric',targets:rows.map(r=>({table:r.name}))}]};
    return {schema:'APP',question:'q',hash:'hash',route:'DEFINITIONS',references:value.tables.map(table=>({table,revision:1})),source:'{"evidence":[]}'};
  }});
  await component.restore({schemas:['APP'],evidenceSchema:'APP'});await get('find').fire('click');
  assert.equal(component.ready(),true);assert.deepEqual(calls.at(-1),{path:'evidence/definitions',value:{schema:'APP',question:'q',tables:['A','B']}});
  assert.match(texts(get('tokens')),/SHOW/);assert.match(texts(get('tokens')),/AUGUST/);
  assert.ok(calls.every(c=>c.path!=='evidence/choose'));assert.equal(get('definitions').querySelectorAll('input').filter(n=>n.checked).length,2);
  empty=true;await get('find').fire('click');assert.equal(component.ready(),true);assert.deepEqual(calls.at(-1).value.tables,[]);
  limited=true;await get('find').fire('click');assert.equal(component.ready(),false);assert.equal(calls.at(-1).path,'evidence/search?language=ko');
}));
