import test from 'node:test';
import assert from 'node:assert/strict';
import {canApplyChange} from '../../main/resources/static/js/setup-assistant.mjs';
test('apply needs an actual preview token, explicit consent and idle state',()=>{
  assert.equal(canApplyChange(null,true,false),false);
  assert.equal(canApplyChange({token:'reviewed'},false,false),false);
  assert.equal(canApplyChange({token:'reviewed'},true,true),false);
  assert.equal(canApplyChange({token:'reviewed'},true,false),true);
});
test('editing or failing to refresh a preview hides stale data and clears consent',async()=>{
  const elements=new Map();
  const get=name=>{
    if(!elements.has(name))elements.set(name,{hidden:false,checked:false,disabled:false,value:'',textContent:'',dataset:{csrfHeader:'X-CSRF-TOKEN'},events:{},addEventListener(type,handler){this.events[type]=handler;}});
    return elements.get(name);
  };
  const root={dataset:{purpose:'query',profile:'TEST_PROFILE'},querySelector:selector=>get(selector.slice(12,-1))};
  const originalDocument=globalThis.document,originalFetch=globalThis.fetch;
  const requests=[];let fail=false;
  globalThis.document={querySelectorAll:selector=>selector==='[data-setup-assistant]'?[root]:[]};
  globalThis.fetch=async(url,options)=>{
    requests.push({url,body:JSON.parse(options.body)});
    if(fail)throw new Error('Preview unavailable');
    return {ok:true,status:200,headers:new Headers({'content-type':'application/json'}),json:async()=>({token:'preview-token',source:'Synthetic diagnosis',profile:{selection:{name:'TEST_MODEL'},provider:'test',model:'test-model'}})};
  };
  try{
    await import('../../main/resources/static/js/setup-assistant.mjs?preview-regression');
    get('question').value='Explain readiness';
    await get('preview').events.click();
    assert.equal(get('preview-panel').hidden,false);
    assert.equal(get('analyze').disabled,true);
    get('consent').checked=true;get('consent').events.change();
    assert.equal(get('analyze').disabled,false);
    fail=true;
    await get('preview').events.click();
    assert.equal(get('preview-panel').hidden,true);
    assert.equal(get('consent').checked,false);
    assert.equal(get('analyze').disabled,true);
    assert.equal(get('status').textContent,'Preview unavailable');
    fail=false;
    await get('preview').events.click();
    get('consent').checked=true;get('apply-consent').checked=true;
    get('question').value='Revised question';get('question').events.input();
    assert.equal(get('preview-panel').hidden,true);
    assert.equal(get('consent').checked,false);
    assert.equal(get('apply-consent').checked,false);
    assert.equal(get('analyze').disabled,true);
    assert.equal(get('apply').disabled,true);
    assert.equal(get('status').textContent,'');
    assert.ok(requests.every(request=>request.url==='/ai-setup/assistant/preview'));
    assert.equal(requests[0].body.question,'Explain readiness');
  }finally{
    if(originalDocument===undefined)delete globalThis.document;else globalThis.document=originalDocument;
    globalThis.fetch=originalFetch;
  }
});
