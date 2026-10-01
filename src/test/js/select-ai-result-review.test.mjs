import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {canReviewResult,matchingResultReview,mountResultReview} from '../../main/resources/static/js/select-ai-result-review.mjs';
const profile={selection:{owner:'APP',name:'P'},provider:'test',model:'synthetic'};
const outcome={id:'r',action:'SQL',profile,question:'q'};
const execution={resultId:'r',data:{searchId:'r',hash:'h',executedAt:'2026-01-01T00:00:00Z',rows:[]}};
const review={resultId:'r',sqlHash:'h',executedAt:execution.data.executedAt,reviewer:profile,requestedAt:'2026-01-01T00:00:01Z',elapsedMillis:1,text:'<img src=x onerror=alert(1)>'};
test('only the successful matching execution can be reviewed; empty results are valid',()=>{
  assert.equal(canReviewResult(outcome,execution,'P',false),true);
  for(const result of [null,{...execution,error:'failed'},{...execution,resultId:'other'},{...execution,data:null}])assert.equal(canReviewResult(outcome,result,'P',false),false);
  assert.equal(canReviewResult(outcome,execution,'OTHER',false),false);
  assert.equal(canReviewResult(outcome,execution,'P',true),false);
  assert.equal(canReviewResult({...outcome,action:'CHAT'},execution,'P',false),false);
  assert.equal(matchingResultReview(review,execution),true);
  for(const changed of [{...review,sqlHash:'changed'},{...review,executedAt:'later'},{...review,resultId:'other'}])assert.equal(matchingResultReview(changed,execution),false);
});
function harness(handler){
  const nodes=new Map(),calls=[];let busy=false,uncertain=false,component;
  const get=name=>{if(!nodes.has(name))nodes.set(name,{textContent:'',value:'',checked:false,handlers:{},addEventListener(event,fn){this.handlers[event]=fn;},setAttribute(){},showModal(){this.open=true;},close(){this.open=false;}});return nodes.get(name);};
  component=mountResultReview({querySelector:selector=>get(selector.match(/data-result-review-(.+)\]/)[1])},{
    post:async(path,body)=>{calls.push({path,body});return handler?handler(path,body):path.endsWith('/preview')?{token:'t',profile,characters:10,source:'sanitized payload'}:review;},
    latest:()=>outcome,selected:()=> 'P',isBusy:()=>busy,setBusy:value=>{busy=value;component.controls();},uncertain:()=>{uncertain=true;},message:value=>{get('message').textContent=value;}
  });
  component.setExecution(execution);component.controls();
  return {component,get,calls,event:(name,event='click')=>get(name).handlers[event](),uncertain:()=>uncertain};
}
test('explicit preview and consent make exactly one token-only call; HTML is text',async()=>{
  const h=harness();h.get('baseline').value='count unique users';
  await h.event('open');assert.deepEqual(h.calls[0],{path:'result-review/preview',body:{resultId:'r',baseline:'count unique users'}});
  assert.equal(h.get('run').disabled,true);await h.event('run');assert.equal(h.calls.length,1);
  h.get('consent').checked=true;await h.event('consent','change');assert.equal(h.get('run').disabled,false);
  await h.event('run');assert.deepEqual(h.calls[1],{path:'result-review',body:{token:'t',consent:true}});
  await h.event('run');assert.equal(h.calls.length,2);assert.equal(h.get('content').textContent,review.text);
  assert.equal(h.get('result').hidden,false);assert.equal(h.get('status').textContent,'');
  h.component.setExecution({...execution,data:{...execution.data,executedAt:'later'}});h.component.render(review);assert.equal(h.get('result').hidden,true);
});
test('close cancels preview, ambiguous transport failure never retries',async()=>{
  const h=harness();await h.event('open');await h.event('close');assert.deepEqual(h.calls[1],{path:'cancel',body:{token:'t'}});
  const failure=harness(path=>{if(path.endsWith('/preview'))return {token:'t',profile,characters:1,source:'payload'};throw Error('interrupted');});
  await failure.event('open');failure.get('consent').checked=true;await failure.event('run');await failure.event('run');
  assert.equal(failure.calls.length,2);assert.equal(failure.uncertain(),true);assert.match(failure.get('message').textContent,/interrupted/);
});
test('shared busy boundary blocks double send and exposes in-progress status',async()=>{
  let finish;
  const h=harness(path=>path.endsWith('/preview')?{token:'t',profile,characters:1,source:'payload'}:new Promise(resolve=>{finish=resolve;}));
  await h.event('open');h.get('consent').checked=true;const running=h.event('run');
  assert.ok(h.get('status').textContent);assert.equal(h.get('open').disabled,true);assert.equal(h.get('close').disabled,true);
  await h.event('run');assert.equal(h.calls.length,2);finish(review);await running;
  assert.equal(h.get('open').disabled,false);
});
test('template wiring and all translations exist without automatic execution or storage',()=>{
  const source=fs.readFileSync('src/main/resources/static/js/select-ai-result-review.mjs','utf8');
  const html=fs.readFileSync('src/main/resources/templates/ai-test.html','utf8');
  for(const [,name]of source.matchAll(/get\('([^']+)'\)/g))assert.ok(html.includes(`data-result-review-${name}`),name);
  assert.doesNotMatch(source,/innerHTML|localStorage|sessionStorage|post\('execute|post\('generate/);
  const messages=JSON.parse(fs.readFileSync('tools/i18n/feature-result-review.json','utf8'));
  for(const [key,values]of Object.entries(messages)){assert.equal(values.length,4);for(const suffix of ['','_ko','_en','_ja','_zh_CN'])assert.ok(fs.readFileSync(`src/main/resources/i18n/messages${suffix}.properties`,'utf8').includes(key+'='));}
});
