import test from 'node:test';
import assert from 'node:assert/strict';
import {resultArchiveAction} from '../../main/resources/static/js/select-ai-result-archive.mjs';
import {nativeResponseNote} from '../../main/resources/static/js/execution-history.mjs';
class Node{
  constructor(tag){this.tag=tag;this.children=[];this.events={};}
  append(...nodes){this.children.push(...nodes);}
  setAttribute(){}
  addEventListener(name,handler){this.events[name]=handler;}
  set innerHTML(value){throw new Error('Unsafe HTML');}
}
const doc={createElement:tag=>new Node(tag)},csrf={dataset:{csrfHeader:'X-CSRF-TOKEN'},value:'synthetic'};
test('explicit result save sends only current result ID and consent, then links the saved snapshot',async()=>{
  const old=globalThis.fetch;const requests=[];
  globalThis.fetch=async(url,options)=>{requests.push({url,options});return new Response(JSON.stringify({id:'saved-id'}),{headers:{'content-type':'application/json'}});};
  try{
    const host=new Node('div'),panel=resultArchiveAction(host,'generation',csrf,doc),[,button,link,status]=panel.children;
    assert.equal(requests.length,0);await button.events.click();assert.equal(requests.length,1);
    assert.equal(requests[0].url,'/ai-test/results/save');assert.deepEqual(JSON.parse(requests[0].options.body),{resultId:'generation',consent:true});
    assert.equal(requests[0].options.headers['X-CSRF-TOKEN'],'synthetic');assert.equal(button.disabled,true);assert.equal(link.href,'/ai-executions/results?id=saved-id');assert(status.textContent);
  }finally{globalThis.fetch=old;}
});
test('save error leaves current rows and a retryable action without rerunning SQL',async()=>{
  const old=globalThis.fetch;let count=0;
  globalThis.fetch=async()=>{count++;return new Response(JSON.stringify({error:'Storage unavailable'}),{status:503,headers:{'content-type':'application/json'}});};
  try{const host=new Node('div'),panel=resultArchiveAction(host,'generation',csrf,doc);await panel.children[1].events.click();assert.equal(panel.children[1].disabled,false);assert.equal(panel.children[3].textContent,'Storage unavailable');assert.equal(host.children[0],panel);assert.equal(count,1);}
  finally{globalThis.fetch=old;}
});
test('native RUNSQL history is explicitly described as generated SQL rather than retained rows',()=>{
  assert.equal(nativeResponseNote('RUNSQL'),nativeResponseNote('showsql'));assert.notEqual(nativeResponseNote('RUNSQL'),nativeResponseNote('NARRATE'));
});
