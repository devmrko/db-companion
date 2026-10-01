import test from 'node:test';
import assert from 'node:assert/strict';
import {ontologyPipeline} from '../../main/resources/static/js/ontology-pipeline.mjs';

// Exercise the real dialog handlers with a minimal DOM and a fake server; no paid calls.
class Element {
  constructor(tag,text=''){this.tagName=tag;this.ownText=text;this.children=[];this.dataset={};this.events={};this.value='';this.checked=false;this.disabled=false;this.hidden=false;}
  set textContent(v){this.ownText=String(v);this.children=[];} get textContent(){return this.ownText+this.children.map(c=>c.textContent??String(c)).join('');}
  append(...values){this.children.push(...values);} replaceChildren(...values){this.ownText='';this.children=values;}
  setAttribute(k,v){this[k]=v;} addEventListener(k,v){this.events[k]=v;}
  get options(){return this.children.filter(c=>c.tagName==='option');}
  querySelectorAll(tags){return walk(this).filter(e=>tags.split(',').includes(e.tagName));}
  showModal(){this.open=true;} close(){this.open=false;} scrollIntoView(){}
  async fire(type){return this.events[type]?.({preventDefault(){}});}
}
const walk=node=>[node,...node.children.flatMap(c=>c instanceof Element?walk(c):[])];
async function setup(t,{total=55,failAt=-1,diagnostic=null,recordedFailures=[]}={}){
  globalThis.document={createElement:tag=>new Element(tag),createTextNode:text=>new Element('#text',text)};
  globalThis.Option=class extends Element{constructor(text,value){super('option',text);this.value=value;}};
  t.after(()=>{delete globalThis.document;delete globalThis.Option;});
  const dialog=new Element('dialog'),elements=Object.fromEntries(['status','close','stop','title','body','diagnostic','diagnostic-path','raw'].map(k=>[k,new Element(k==='close'||k==='stop'?'button':'div')]));
  dialog.append(...Object.values(elements));dialog.querySelector=selector=>elements[selector.match(/data-pipeline-(.*)\]/)[1]];
  const server={token:'t',schema:'APP',profile:{selection:{owner:'APP',name:'P'},provider:'oci',model:'m'},tables:2,groupSize:12,
    budget:{originalCharacters:500,compactCharacters:400,blocks:11,calls:total,reason:'',sizes:[{table:'A',originalCharacters:250,compactCharacters:200},{table:'B',originalCharacters:250,compactCharacters:200}],transmissionCharacters:5000,maxBatchCharacters:600},
    progress:{completed:0,total,candidates:0,done:false,nextIndex:0,authorizedUntil:0,running:false,paused:true,failed:[],resultLimit:false}};
  const calls=[];let statusFails=false,updates=0,onCall=null;
  const post=async(path,data)=>{calls.push({path,data});const p=server.progress;switch(path){
    case '/pipeline/status':if(statusFails)throw Error('offline');return {plan:structuredClone(server)};
    case '/pipeline/saved':return {rows:[],next:''};
    case '/pipeline/scope/options':return {tables:[{name:'A',revision:1,state:'DRAFT'},{name:'B',revision:1,state:'DRAFT'}],schemaTables:2,storage:'MISSING',canSave:false,installSql:''};
    case '/pipeline/resume':assert.equal(data.index,p.nextIndex);assert.equal(data.consent,true);p.authorizedUntil=data.all?total:Math.min(total,p.nextIndex+12);p.paused=false;return structuredClone(server);
    case '/pipeline/generate':
      assert.equal(data.index,p.nextIndex);assert.ok(data.index<p.authorizedUntil);p.nextIndex++;if(onCall)await onCall();
      if(data.index===failAt){p.failed.push(data.index+1);p.authorizedUntil=p.nextIndex;p.paused=true;throw Object.assign(Error('unknown response'),{diagnostic});}
      if(recordedFailures.includes(data.index))p.failed.push(data.index+1);else p.completed++;p.candidates=1;p.done=p.nextIndex===total;p.paused=p.nextIndex>=p.authorizedUntil;return structuredClone(p);
    case '/pipeline/stop':p.authorizedUntil=p.nextIndex;p.paused=true;return {stopped:true};
    case '/pipeline/payload':return {tables:['A','B'],source:'ONE_CALL_ONLY'};
    default:throw Error('Unexpected: '+path);
  }};
  const ui=ontologyPipeline(dialog,{schema:'APP',post,profiles:async()=>[],updated:async()=>{updates++;},canOpen:()=>true});await ui.discover();
  const button=label=>walk(dialog).find(e=>e.tagName==='button'&&e.textContent===label);
  const consent=()=>walk(dialog).find(e=>e.className==='app-pipeline-consent'&&e.textContent==='discovery.consent').children[0];
  const run=async()=>{consent().checked=true;await consent().fire('change');const start=button(server.progress.nextIndex?'discovery.continue':'discovery.start');assert.equal(start.disabled,false);await start.fire('click');};
  return {ui,dialog,elements,calls,server,button,run,consent,updates:()=>updates,setStatusFailure:v=>{statusFails=v;},onCall:fn=>{onCall=fn;}};
}
test('55 calls run as five consented groups and payload inspection loads only one call',async t=>{
  const s=await setup(t);assert.equal(s.calls.filter(c=>c.path.includes('generate')).length,0);
  assert.equal(s.button('discovery.start').disabled,true);await s.button('discovery.loadPayload').fire('click');
  assert.equal(s.calls.filter(c=>c.path.endsWith('/payload')).length,1);assert.ok(s.dialog.textContent.includes('ONE_CALL_ONLY'));
  for(const count of [12,24,36,48,55]){await s.run();assert.equal(s.server.progress.completed,count);assert.equal(s.consent().checked,false);}
  assert.equal(s.calls.filter(c=>c.path.endsWith('/resume')).length,5);assert.equal(s.button('discovery.continue').disabled,true);
  assert.deepEqual(s.calls.filter(c=>c.path.endsWith('/generate')).map(c=>c.data.index),Array.from({length:55},(_,i)=>i));
});
test('closing and reopening restores progress without a new preview or replay',async t=>{
  const s=await setup(t);await s.run();await s.elements.close.fire('click');assert.equal(s.updates(),1);
  await s.ui.discover();assert.equal(s.server.progress.nextIndex,12);await s.run();assert.equal(s.server.progress.completed,24);
  assert.equal(s.calls.filter(c=>c.path.endsWith('/preview')).length,0);
});
test('stop after an in-flight call preserves it and resumes without repeating it',async t=>{
  const s=await setup(t);s.onCall(async()=>{await s.elements.stop.fire('click');});await s.run();assert.equal(s.server.progress.completed,1);
  s.onCall(null);await s.run();assert.equal(s.server.progress.completed,13);
  assert.deepEqual(s.calls.filter(c=>c.path.endsWith('/generate')).map(c=>c.data.index),Array.from({length:13},(_,i)=>i));
});
test('an unknown response stops the group, exposes failure and resumes only unattempted calls',async t=>{
  const s=await setup(t,{failAt:3});await s.run();assert.equal(s.server.progress.completed,3);assert.deepEqual(s.server.progress.failed,[4]);
  assert.equal(s.calls.filter(c=>c.path.endsWith('/generate')).length,4);assert.ok(s.dialog.textContent.includes('discovery.failed'));
  await s.run();assert.equal(s.server.progress.nextIndex,16);assert.equal(s.calls.filter(c=>c.path.endsWith('/generate')&&c.data.index===3).length,1);
});
test('unknown server status locks resume until explicit successful progress check',async t=>{
  const s=await setup(t,{failAt:0});s.setStatusFailure(true);await s.run();assert.equal(s.button('discovery.start').disabled,true);
  assert.ok(s.dialog.textContent.includes('discovery.unknown'));s.setStatusFailure(false);await s.button('discovery.checkProgress').fire('click');
  await s.run();assert.equal(s.server.progress.nextIndex,13);
});
test('parse diagnostics keep earlier candidates and clear raw data on close without retry',async t=>{
  const diagnostic={code:'COLUMN',path:'$.relations[1].sourceColumns[0]',rawResponse:'<script>untrusted</script>'};
  const s=await setup(t,{failAt:1,diagnostic});await s.run();
  assert.equal(s.server.progress.completed,1);assert.equal(s.server.progress.candidates,1);
  assert.ok(s.dialog.textContent.includes('discovery.existingPlan'));assert.ok(!s.dialog.textContent.includes('scope.plan'));
  assert.equal(s.elements.diagnostic.hidden,false);assert.equal(s.elements.raw.textContent,diagnostic.rawResponse);
  assert.equal(s.elements['diagnostic-path'].textContent,'COLUMN · '+diagnostic.path);
  await s.elements.close.fire('click');assert.equal(s.elements.raw.textContent,'');assert.equal(s.elements.diagnostic.hidden,true);
  await s.ui.discover();assert.equal(s.elements.raw.textContent,'');assert.equal(s.server.progress.nextIndex,2);
  assert.deepEqual(s.calls.filter(c=>c.path.endsWith('/generate')).map(c=>c.data.index),[0,1]);
});
test('all 66 calls continue through recorded failures 1 and 6 with a single explicit authorization',async t=>{
  const s=await setup(t,{total:66,recordedFailures:[0,5]});
  const all=walk(s.dialog).find(e=>e.className==='app-pipeline-consent'&&e.textContent==='discovery.all').children[0];
  all.checked=true;await all.fire('change');assert.equal(s.consent().checked,false);await s.run();
  assert.equal(s.server.progress.completed,64);assert.deepEqual(s.server.progress.failed,[1,6]);assert.equal(s.server.progress.nextIndex,66);
  assert.equal(s.calls.filter(c=>c.path==='/pipeline/resume').length,1);
  assert.deepEqual(s.calls.filter(c=>c.path==='/pipeline/generate').map(c=>c.data.index),Array.from({length:66},(_,i)=>i));
  assert.equal(s.elements.status.textContent,'discovery.incomplete');
});
