import test from 'node:test';
import assert from 'node:assert/strict';
import {definitionReviewFlow,validDefinitionChoice,wireDefinitionReview} from '../../main/resources/static/js/ontology-definition.mjs';
const choice=()=>({mode:'EXACT_ALIAS',originalQuestion:'  original question\n  ',searchQuery:'metric',profile:'P',tables:['T'],selected:['T:1:TABLE:T']});
const preview=()=>({token:'one-use',source:'reviewed source',profile:{selection:{owner:'APP',name:'P'},provider:'oci',model:'model'}});
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
function fixture(overrides={}){let selected=choice();const calls=[],results=[],errors=[],cancelled=[];const flow=definitionReviewFlow({selection:()=>selected,contextPreview:async c=>{calls.push(['context',c]);return {token:'scope'};},generationPreview:async(c,t)=>{calls.push(['preview',c,t]);return preview();},generate:async token=>{calls.push(['generate',token]);return {sqlResponse:true,text:'SELECT 1 FROM DUAL'};},cancel:token=>cancelled.push(token),onResult:r=>results.push(r),onError:e=>errors.push(e),...overrides});return {flow,calls,results,errors,cancelled,select:v=>selected=v};}
test('definition review accepts exact/alias only with bounded explicit choices',()=>{
  assert.equal(validDefinitionChoice(choice()),true);
  assert.equal(validDefinitionChoice({...choice(),mode:'ORACLE_TEXT'}),true);
  for(const patch of [{mode:'OTHER'},{originalQuestion:' '},{originalQuestion:'x'.repeat(16001)},{originalQuestion:'x\0'},{tables:[]},{selected:[]},{selected:['x','x']},{profile:''}])assert.equal(validDefinitionChoice({...choice(),...patch}),false);
});
test('preview preserves the original question and does not generate SQL before consent',async()=>{
  const f=fixture();assert.equal(await f.flow.send(),false);assert.equal(await f.flow.prepare(),true);
  assert.deepEqual(f.calls.map(v=>v[0]),['context','preview']);assert.equal(f.calls[1][1].originalQuestion,choice().originalQuestion);assert.equal(f.calls[1][2],'scope');
  assert.equal(f.flow.state().canSend,false);assert.equal(await f.flow.send(),false);
  f.flow.confirm(true);assert.equal(await f.flow.send(),true);assert.equal(f.results.length,1);
  assert.equal(await f.flow.send(),false);assert.equal(f.calls.filter(v=>v[0]==='generate').length,1);
});
test('double click starts at most one generation while its response is pending',async()=>{
  const pending=deferred();let calls=0;const f=fixture({generate:()=>{calls++;return pending.promise;}});await f.flow.prepare();f.flow.confirm(true);
  const first=f.flow.send();assert.equal(await f.flow.send(),false);assert.equal(calls,1);pending.resolve({text:'SQL'});await first;assert.equal(f.flow.state().spent,true);
});
test('changed question, definitions, profile or scope cannot reuse reviewed consent',async()=>{
  for(const patch of [{originalQuestion:'new question'},{selected:['different']},{profile:'OTHER'},{tables:['OTHER']},{searchQuery:'other'}]){
    const f=fixture();await f.flow.prepare();f.flow.confirm(true);f.select({...choice(),...patch});assert.equal(f.flow.state().canSend,false);assert.equal(await f.flow.send(),false);assert.equal(f.calls.filter(v=>v[0]==='generate').length,0);
  }
});
test('invalidating context read suppresses the later preview request',async()=>{
  const pending=deferred();let previews=0;const f=fixture({contextPreview:()=>pending.promise,generationPreview:async()=>{previews++;return preview();}});
  const work=f.flow.prepare();f.flow.invalidate();pending.resolve({token:'scope'});assert.equal(await work,false);assert.equal(previews,0);assert.equal(f.flow.state().preview,null);
});
test('obsolete generation preview is discarded and cannot open a dialog',async()=>{
  const pending=deferred();let displayed=0;const f=fixture({generationPreview:()=>pending.promise,onPreview:()=>displayed++});const work=f.flow.prepare();await Promise.resolve();f.flow.invalidate();pending.resolve(preview());await work;await Promise.resolve();assert.deepEqual(f.cancelled,['one-use']);assert.equal(displayed,0);
});
test('generation network failure spends the request without retry',async()=>{
  let calls=0;const f=fixture({generate:async()=>{calls++;throw new Error('response unknown');}});await f.flow.prepare();f.flow.confirm(true);assert.equal(await f.flow.send(),false);assert.equal(f.errors[0].message,'response unknown');assert.equal(await f.flow.send(),false);assert.equal(calls,1);
});
test('a late generation response never replaces changed user context',async()=>{
  const pending=deferred();const f=fixture({generate:()=>pending.promise});await f.flow.prepare();f.flow.confirm(true);const send=f.flow.send();f.flow.invalidate();pending.resolve({text:'old answer'});await send;assert.deepEqual(f.results,[]);
});
class Control {constructor(){this.handlers=new Map();this.disabled=false;this.checked=false;this.textContent='';this.value='';this.open=false;}addEventListener(k,fn){this.handlers.set(k,fn);}fire(k,event={}){return this.handlers.get(k)?.(event);}showModal(){this.open=true;}close(){this.open=false;}set innerHTML(_){throw new Error('HTML injection');}}
function mounted(overrides={}){const nodes=Object.fromEntries(['prepare','send','consent','close','originalQuestion','dialog','question','payload','profile','status'].map(k=>[k,new Control()]));let selected=choice(),external=false;const generated=[];const ui=wireDefinitionReview(nodes,{selection:()=>selected,contextPreview:async()=>({token:'scope'}),generationPreview:async()=>preview(),generate:async token=>{generated.push(token);return {sqlResponse:false,error:'review needed',code:'ORA-20004',phase:'sql-response',text:'<script>raw response</script>'};},cancel:()=>{},externalBusy:()=>external,...overrides});return {nodes,ui,generated,select:v=>selected=v,lock:v=>external=v};}
test('real event wiring keeps buttons disabled after an unrelated global unlock',async()=>{
  const f=mounted();assert.equal(f.nodes.send.disabled,true);await f.nodes.prepare.fire('click');assert.equal(f.nodes.dialog.open,true);assert.equal(f.nodes.question.textContent,choice().originalQuestion);assert.match(f.nodes.profile.textContent,/APP.P.*model/);
  f.nodes.send.disabled=false;f.nodes.consent.disabled=false;f.ui.render();assert.equal(f.nodes.send.disabled,true);
  f.nodes.consent.checked=true;f.nodes.consent.fire('change');assert.equal(f.nodes.send.disabled,false);
  f.lock(true);f.ui.render();assert.equal(f.nodes.send.disabled,true);f.lock(false);f.ui.render();assert.equal(f.nodes.send.disabled,false);
  await f.nodes.send.fire('click');f.nodes.send.disabled=false;f.ui.render();assert.equal(f.nodes.send.disabled,true);assert.equal(f.nodes.consent.disabled,true);assert.deepEqual(f.generated,['one-use']);
  assert.match(f.nodes.status.textContent,/ORA-20004/);assert.match(f.nodes.status.textContent,/<script>raw response<\/script>/);
});
test('original-question input invalidates dialog and consent',async()=>{
  const f=mounted();await f.nodes.prepare.fire('click');f.nodes.consent.checked=true;f.nodes.consent.fire('change');f.select({...choice(),originalQuestion:'changed'});f.nodes.originalQuestion.fire('input');assert.equal(f.nodes.dialog.open,false);assert.equal(f.nodes.consent.checked,false);assert.equal(f.nodes.send.disabled,true);await f.nodes.send.fire('click');assert.deepEqual(f.generated,[]);
});
test('preflight error is visible even when no preview dialog was opened',async()=>{
  const f=mounted({contextPreview:async()=>{throw new Error('scope unavailable');}});await f.nodes.prepare.fire('click');assert.equal(f.nodes.dialog.open,true);assert.equal(f.nodes.status.textContent,'scope unavailable');assert.equal(f.nodes.payload.textContent,'');assert.equal(f.nodes.send.disabled,true);
});
test('escape and close cannot hide an in-flight call',async()=>{
  const pending=deferred();const f=mounted({generate:()=>pending.promise});await f.nodes.prepare.fire('click');f.nodes.consent.checked=true;f.nodes.consent.fire('change');const send=f.nodes.send.fire('click');let prevented=false;f.nodes.dialog.fire('cancel',{preventDefault:()=>prevented=true});f.nodes.close.fire('click');assert.equal(prevented,true);assert.equal(f.nodes.dialog.open,true);pending.resolve({text:'done'});await send;
});
