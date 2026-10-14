import test from 'node:test';
import assert from 'node:assert/strict';
import {mountProblemEvaluation,evaluationIdentity} from '../../main/resources/static/js/problem-evaluation.mjs';

class Element{
  constructor(tag,text=''){this.tag=tag;this.children=[];this.events={};this.dataset={};this.value='';this.textContent=text;this.disabled=false;this.checked=false;}
  append(...nodes){for(const n of nodes){n.parent=this;this.children.push(n);if(this.tag==='select'&&this.children.length===1)this.value=n.value;}}
  before(node){this.parent.append(node);}
  replaceChildren(...nodes){this.children=[];this.value='';this.append(...nodes);}
  setAttribute(){}
  addEventListener(name,fn){this.events[name]=fn;}
  fire(name,event){return this.events[name]?.(event);}
  all(){return this.children.flatMap(n=>[n,...n.all()]);}
}
const response=data=>({ok:true,status:200,headers:{get:()=> 'application/json'},json:async()=>data});
const detail=id=>({parent:{id},attempts:[{id:'attempt-'+id,capturedAt:'time',profile:'APP.TEST'}]});
const plan={preview:{token:'token',profile:{selection:{owner:'APP',name:'REVIEW'},provider:'mock',model:'mock'},source:'synthetic evidence'},context:{criteriaHash:'hash'}};
const flush=()=>new Promise(resolve=>setImmediate(resolve));

test('identity binds question, attempt and evaluation criteria',()=>{
  assert.notEqual(evaluationIdentity('p','a','one'),evaluationIdentity('p','a','two'));
  assert.notEqual(evaluationIdentity('p','a','one'),evaluationIdentity('q','a','one'));
});
test('UI requires external consent and human verdict, keeps report after failed save, ignores late preview',async()=>{
  const original={document:globalThis.document,fetch:globalThis.fetch,Option:globalThis.Option};
  globalThis.document={createElement:tag=>new Element(tag)};
  globalThis.Option=class extends Element{constructor(text,value){super('option',text);this.value=value;}};
  const root=new Element('main'),actions=new Element('section');root.append(actions);root.querySelector=()=>actions;
  let delayed=null,delayPreview=false,failSave=false,calls=[];
  globalThis.fetch=async(url,options)=>{
    calls.push({url,options});
    if(url.includes('/history'))return response([]);
    if(url.endsWith('/preview'))return delayPreview?new Promise(done=>{delayed=done;}):response(plan);
    if(url.endsWith('/review'))return response({id:'report',text:'ADVISORY REPORT'});
    if(url.endsWith('/save')){if(failSave)return {...response({error:'save failed'}),ok:false,status:503};return response({});}
    throw new Error('Unexpected request');
  };
  try{
    mountProblemEvaluation(root,{dataset:{csrfHeader:'X-CSRF'},value:'synthetic'});
    const find=(tag,text)=>root.all().find(n=>n.tag===tag&&n.textContent===text);
    const prepare=find('button','prepare'),run=find('button','run'),save=find('button','save');
    root.fire('problem-detail',{detail:detail('one')});await flush();
    assert.equal(run.disabled,true);assert.equal(save.disabled,true);
    await prepare.fire('click');assert.equal(run.disabled,true);
    const inputs=root.all().filter(n=>n.tag==='input');inputs[0].checked=true;inputs[0].fire('change');assert.equal(run.disabled,false);
    await run.fire('click');assert.equal(save.disabled,true);assert.ok(root.all().some(n=>n.textContent==='ADVISORY REPORT'));
    const verdict=root.all().find(n=>n.tag==='select'&&n.children.some(c=>c.value==='NEEDS_CHANGE'));
    const reason=root.all().find(n=>n.tag==='textarea'&&n.maxLength===8000);
    verdict.value='NEEDS_CHANGE';verdict.fire('input');reason.value='missing period';reason.fire('input');inputs[1].checked=true;inputs[1].fire('input');assert.equal(save.disabled,false);
    failSave=true;await save.fire('click');assert.equal(save.disabled,false);assert.ok(root.all().some(n=>n.textContent==='ADVISORY REPORT'));
    failSave=false;await save.fire('click');assert.equal(save.disabled,true);assert.equal(calls.filter(c=>c.url.endsWith('/review')).length,1);
    assert.equal(calls.find(c=>c.url.endsWith('/save')).options.headers['X-CSRF'],'synthetic');
    delayPreview=true;const pending=prepare.fire('click');await flush();root.fire('problem-detail',{detail:detail('two')});delayed(response(plan));await pending;
    assert.equal(run.disabled,true);assert.ok(!root.all().some(n=>n.textContent==='synthetic evidence'));
  }finally{Object.assign(globalThis,original);}
});
