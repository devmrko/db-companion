import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8');
// Evaluate only the real, source-controlled click handler in a synthetic DOM closure.
// No browser, database, generated SQL or external provider is used by these tests.
const start="get('condition-review').addEventListener('click',async()=>{";
const body=source.split(start)[1]?.split('\n  });')[0];
assert.ok(body,'the actual condition-review handler must exist');
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
function fixture(){
  const nodes=new Map(),calls=[],fixed=[],inputValues=new Map();let error='',closed=0,shown=0;
  const node=name=>{if(!nodes.has(name))nodes.set(name,{value:'',textContent:'',checked:false});return nodes.get(name);};
  node('question').value='  original question  ';node('condition-free').value='group by day';
  const context={busy:false,remoteRunning:false,conditionOriginal:node('question').value,selected:'P',prepared:null,
    get:node,root:{querySelectorAll:()=>fixed,querySelector:selector=>inputValues.get(selector.match(/="([^"]+)"/)?.[1])},
    conditionError:value=>error=value,controls:()=>{},message:()=>{},t:(key,fallback,...args)=>fallback.replace(/\{(\d+)\}/g,(all,index)=>args[index]??all),evidence:{request:()=>({useOntology:false,evidenceHash:null})},
    glossary:{request:()=>({glossary:{enabled:false}})},
    conditionDialog:{close:()=>closed++},dialog:{showModal:()=>shown++},
    post:async(path,payload)=>{calls.push({path,payload});return {preview:{token:'token',source:'final reviewed source',profile:{selection:{owner:'APP',name:'P'},provider:'oci',model:'M'}}};}
  };
  vm.createContext(context);const click=vm.runInContext('(async()=>{'+body+'\n})',context);
  return {context,node,calls,fixed,inputValues,click,error:()=>error,closed:()=>closed,shown:()=>shown};
}
test('condition click rejects a chosen topic without an actual value before any request',async()=>{
  const f=fixture();f.fixed.push({value:'period'});f.inputValues.set('period',{value:'  '});await f.click();assert.equal(f.calls.length,0);assert.match(f.error(),/실제 값/);
});
test('conditions use stable keys, translated labels, and preserve the original question',async()=>{
  const f=fixture();f.node('condition-free').value='first condition\nsecond condition';await f.click();assert.equal(f.calls.length,1);const p=f.calls[0].payload;assert.equal(p.question,'  original question  ');assert.equal(p.originalQuestion,p.question);assert.deepEqual(Array.from(p.conditions,c=>c.key),['free-1','free-2']);assert.deepEqual(Array.from(p.conditions,c=>c.topic),['자유 입력 조건 1','자유 입력 조건 2']);assert.deepEqual(Array.from(p.conditions,c=>c.value),['first condition','second condition']);assert.equal(f.node('consent').checked,false);assert.equal(f.shown(),1);
});
test('a fixed condition uses an unlocalized key and a separate display label',async()=>{
  const f=fixture();f.node('condition-free').value='';f.fixed.push({value:'period'});f.inputValues.set('period',{value:'2026-01-01~2026-01-31'});await f.click();assert.equal(f.calls.length,1);assert.deepEqual(Array.from(f.calls[0].payload.conditions,value=>({...value})),[{key:'period',topic:'기간 또는 기준 날짜',value:'2026-01-01~2026-01-31'}]);
});
test('more than four conditions and incomplete confirmation answers do not call a provider',async()=>{
  const f=fixture();f.node('condition-free').value='a\nb\nc\nd\ne';await f.click();assert.equal(f.calls.length,0);
  f.node('condition-free').value='a';f.node('condition-question').value='Which period?';await f.click();assert.equal(f.calls.length,0);assert.match(f.error(),/함께/);
});
test('busy and remote-running clicks have no requests or modal side effects',async()=>{
  for(const key of ['busy','remoteRunning']){const f=fixture();f.context[key]=true;await f.click();assert.equal(f.calls.length,0);assert.equal(f.shown(),0);}
});
test('two actual handler invocations issue only one pending preview',async()=>{
  const f=fixture(),pending=deferred();f.context.post=(path,payload)=>{f.calls.push({path,payload});return pending.promise;};
  const first=f.click();await f.click();assert.equal(f.calls.length,1);pending.resolve({preview:{token:'t',source:'review',profile:{selection:{owner:'APP',name:'P'},provider:'oci',model:'M'}}});await first;assert.equal(f.shown(),1);
});
test('edited conditions discard a late preview and cancel its exact token',async()=>{
  const f=fixture(),pending=deferred();f.context.post=(path,payload)=>{f.calls.push({path,payload});return path==='cancel'?Promise.resolve({cancelled:true}):pending.promise;};
  const work=f.click();f.node('condition-free').value='changed condition';pending.resolve({preview:{token:'obsolete'}});await work;
  assert.deepEqual(f.calls.map(c=>c.path),['condition/preview','cancel']);assert.equal(f.calls[1].payload.token,'obsolete');assert.equal(f.context.prepared,null);assert.equal(f.shown(),0);assert.match(f.error(),/변경/);
});
test('changed profile or question invalidates an in-flight preview',async()=>{
  for(const change of [f=>f.context.selected='OTHER',f=>f.node('question').value='new question']){
    const f=fixture(),pending=deferred();f.context.post=(path,payload)=>{f.calls.push({path,payload});return path==='cancel'?Promise.resolve({}):pending.promise;};const work=f.click();change(f);pending.resolve({preview:{token:'old'}});await work;assert.equal(f.shown(),0);assert.equal(f.calls.at(-1).path,'cancel');
  }
});
test('a failed preview is visible in the condition dialog and never retried',async()=>{
  const f=fixture();f.context.post=async(path,payload)=>{f.calls.push({path,payload});throw new Error('scope check failed');};await f.click();assert.equal(f.calls.length,1);assert.equal(f.error(),'scope check failed');assert.equal(f.context.busy,false);assert.equal(f.context.prepared,null);assert.equal(f.shown(),0);
});
