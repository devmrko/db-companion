import test from 'node:test';
import assert from 'node:assert/strict';
import {scopePicker} from '../../main/resources/static/js/ontology-scope.mjs';

// Minimal DOM exercises the actual picker and event wiring without a browser or AI calls.
class Element {
  constructor(tag,text=''){this.tagName=tag;this.ownText=text;this.children=[];this.dataset={};this.events={};this.value='';this.checked=false;this.disabled=false;this.hidden=false;}
  set textContent(v){this.ownText=v;this.children=[];} get textContent(){return this.ownText+this.children.map(c=>c.textContent??String(c)).join('');}
  append(...values){this.children.push(...values);} replaceChildren(...values){this.ownText='';this.children=values;}
  setAttribute(k,v){this[k]=v;} addEventListener(k,v){this.events[k]=v;}
  async fire(type){return this.events[type]?.({preventDefault(){}});}
}
const walk=node=>[node,...node.children.flatMap(c=>c instanceof Element?walk(c):[])];
async function setup(t,{storage='MISSING',total=75}={}){
  globalThis.document={createElement:tag=>new Element(tag)};globalThis.Option=class extends Element{constructor(text,value){super('option',text);this.value=value;}};
  t.after(()=>{delete globalThis.document;delete globalThis.Option;});
  const host=new Element('div'),calls=[],plans=[];let busy=false,changes=0;
  const tables=Array.from({length:total},(_,i)=>({name:'T'+String(i).padStart(3,'0'),revision:1,state:'DRAFT'}));
  const options={tables,schemaTables:100,storage,canSave:storage==='READY',installSql:storage==='MISSING'?'CREATE TABLE ...':''};
  const post=async(path,data)=>{calls.push({path,data});switch(path){
    case '/pipeline/scope/options':return options;
    case '/pipeline/scope/profile':return {tables:['T001','T002'],missing:['MISSING'],outside:['OTHER.T'],wholeSchema:false};
    case '/pipeline/scope/saved':return {rows:[{name:'saved',tables:['T001','T002','GONE'],recordedAt:'now'}],next:''};
    case '/pipeline/scope/save':return {id:'saved-id'};
    case '/pipeline/scope/install':return {...options,storage:'READY',canSave:true,installSql:''};
    default:throw new Error('Unexpected request: '+path);
  }};
  const picker=await scopePicker(host,{schema:'APP',post,profiles:async()=>[{name:'PROFILE'}],preview:async tables=>plans.push(tables),changed:()=>changes++,status:()=>{},lock:v=>{busy=v;},isBusy:()=>busy});
  const button=text=>walk(host).find(e=>e.tagName==='button'&&e.textContent===text);
  const input=label=>walk(host).find(e=>e['aria-label']===label);
  const choose=async name=>{const row=walk(host).find(e=>e.className==='app-scope-table'&&e.textContent.startsWith(name));assert.ok(row,name);row.children[0].checked=true;await row.children[0].fire('change');};
  return {host,calls,plans,picker,button,input,choose,changes:()=>changes};
}
test('manual selection spans pages and preview submits only the exact selected names',async t=>{
  const s=await setup(t);assert.equal(s.button('preview').disabled,true);assert.deepEqual(s.picker.selection(),[]);
  await s.choose('T001');await s.button('next').fire('click');await s.choose('T070');
  assert.equal(s.button('preview').disabled,false);await s.button('preview').fire('click');assert.deepEqual(s.plans,[['T001','T070']]);
  assert.equal(s.calls.filter(c=>c.path.includes('install')||c.path.includes('generate')||c.path.endsWith('/save')).length,0);
});
test('profile exclusions require acknowledgment and remain visible before preview',async t=>{
  const s=await setup(t);s.input('sourceProfile').value='PROFILE';await s.input('sourceProfile').fire('change');await s.button('importProfile').fire('click');
  assert.deepEqual(s.picker.selection(),['T001','T002']);assert.equal(s.button('preview').disabled,true);
  await s.button('preview').fire('click');assert.equal(s.plans.length,0);
  s.input('ack').checked=true;await s.input('ack').fire('change');assert.equal(s.button('preview').disabled,false);
  await s.button('preview').fire('click');assert.deepEqual(s.plans,[['T001','T002']]);
});
test('saved scopes are rechecked against available definitions and saved as explicit new copies',async t=>{
  const s=await setup(t,{storage:'READY'});await s.button('load').fire('click');assert.deepEqual(s.picker.selection(),['T001','T002']);
  assert.equal(s.button('preview').disabled,true);assert.equal(s.input('name').value,'saved');
  await s.input('name').fire('input');await s.button('save').fire('click');
  assert.deepEqual(s.calls.find(c=>c.path.endsWith('/save')).data,{schema:'APP',name:'saved',tables:['T001','T002']});
});
test('missing storage permits analysis without writes and install requires its separate consent',async t=>{
  const s=await setup(t);await s.button('install').fire('click');assert.equal(s.calls.filter(c=>c.path.endsWith('/install')).length,0);
  s.input('installConsent').checked=true;await s.input('installConsent').fire('change');await s.button('install').fire('click');
  assert.equal(s.calls.filter(c=>c.path.endsWith('/install')).length,1);assert.equal(s.calls.find(c=>c.path.endsWith('/install')).data.confirmed,true);
  assert.ok(s.button('load'));assert.equal(s.button('install'),undefined);
});
test('select all is explicit and includes off-page saved definitions without database writes',async t=>{
  const s=await setup(t,{total:292});assert.equal(s.picker.selection().length,0);await s.button('selectAll').fire('click');
  assert.equal(s.picker.selection().length,292);await s.button('preview').fire('click');assert.equal(s.plans[0].length,292);
  assert.equal(s.calls.filter(c=>c.path.includes('install')||c.path.includes('generate')||c.path.endsWith('/save')).length,0);
});
