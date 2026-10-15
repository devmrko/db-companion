import test from 'node:test';
import assert from 'node:assert/strict';
import {mountFunctionExplain} from '../../main/resources/static/js/function-explain.mjs';

class Control {
  value='';textContent='';hidden=false;checked=false;disabled=false;open=false;dataset={};events=new Map();
  addEventListener(type,handler){this.events.set(type,handler);}
  async fire(type,event={}){return this.events.get(type)?.(event);}
  scrollIntoView(){}
}
function dialogFixture(){
  const controls=Object.fromEntries(['message','run','close','consent','context','refine','fields','source','csrf','target','profile','source-details','scope','business','result-panel','refined-panel','result','refined'].map(name=>[name,new Control()]));
  controls.csrf.dataset.csrfHeader='X-CSRF';controls.csrf.value='synthetic';
  const dialog=new Control();dialog.dataset={preview:'/preview',run:'/explain'};
  dialog.querySelector=selector=>controls[selector.match(/data-ae-([^\]]+)/)[1]];
  dialog.showModal=()=>{dialog.open=true;};dialog.close=()=>{dialog.open=false;dialog.fire('close');};
  return {dialog,controls};
}
test('code explanation survives refinement; context edits revoke preview and consent; closing clears both',async()=>{
  const oldFetch=globalThis.fetch;const requests=[];let token=0;
  globalThis.fetch=async(url,options)=>{
    const input=JSON.parse(options.body);requests.push({url,input});
    const payload=url==='/preview'?{token:String(++token),reference:'APP.F',profile:{selection:{owner:'APP',name:'P'},provider:'mock',model:'m'},source:JSON.stringify({businessContext:input.businessContext}),characters:30,packageSource:false}:{text:input.token==='1'?'initial <script> text':'refined context text'};
    return new Response(JSON.stringify(payload),{headers:{'content-type':'application/json'}});
  };
  try{
    const {dialog,controls:c}=dialogFixture();const open=mountFunctionExplain(dialog);await open('APP','F');
    assert.equal(requests.length,1);assert.equal(c.run.disabled,true);assert.equal(c.business.hidden,true);
    c.consent.checked=true;await c.consent.fire('change');await c.run.fire('click');
    assert.equal(c.result.textContent,'initial <script> text');assert.equal(c.business.hidden,false);assert.equal(c.run.disabled,true);
    c.context.value='Purpose <script>';await c.context.fire('input');await c.refine.fire('click');
    assert.equal(requests.at(-1).input.businessContext,'Purpose <script>');assert.equal(c.consent.checked,false);assert.equal(c.run.disabled,true);
    c.consent.checked=true;await c.consent.fire('change');await c.run.fire('click');
    assert.equal(c.result.textContent,'initial <script> text');assert.equal(c.refined.textContent,'refined context text');
    assert.equal(c['refined-panel'].hidden,false);
    c.context.value='changed';await c.context.fire('input');
    assert.equal(c.run.disabled,true);assert.equal(c.consent.checked,false);assert.equal(c['refined-panel'].hidden,true);
    await c.close.fire('click');assert.equal(dialog.open,false);assert.equal(c.context.value,'');assert.equal(c.result.textContent,'');assert.equal(c.refined.textContent,'');
    assert.deepEqual(requests.filter(r=>r.url==='/explain').map(r=>Object.keys(r.input)),[['token','consent'],['token','consent']]);
  }finally{globalThis.fetch=oldFetch;}
});
test('failed refinement preview retains the initial explanation and cannot send an AI request',async()=>{
  const oldFetch=globalThis.fetch;let reads=0;
  globalThis.fetch=async(url)=>{
    if(url==='/preview'&&++reads>1)return new Response(JSON.stringify({error:'source changed'}),{status:409,headers:{'content-type':'application/json'}});
    const value=url==='/preview'?{token:'1',reference:'APP.F',profile:{selection:{owner:'APP',name:'P'},provider:'mock'},source:'{}',characters:2}:{text:'initial explanation'};
    return new Response(JSON.stringify(value),{headers:{'content-type':'application/json'}});
  };
  try{
    const {dialog,controls:c}=dialogFixture();await mountFunctionExplain(dialog)('APP','F');c.consent.checked=true;await c.consent.fire('change');await c.run.fire('click');
    c.context.value='purpose';await c.context.fire('input');await c.refine.fire('click');
    assert.equal(c.message.textContent,'source changed');assert.equal(c.result.textContent,'initial explanation');assert.equal(c.run.disabled,true);assert.equal(c.close.disabled,false);
  }finally{globalThis.fetch=oldFetch;}
});
