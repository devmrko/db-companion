import test from 'node:test';
import assert from 'node:assert/strict';
import {mountGlossaryDocument} from '../../main/resources/static/js/glossary-document.mjs';
import {mountGlossaryTools} from '../../main/resources/static/js/glossary-tools.mjs';

class Element {
  constructor(tag){this.tag=tag;this.children=[];this.dataset={};this.attrs={};this.events={};this.textContent='';this.value='';this.checked=false;this.hidden=false;}
  append(...items){for(const item of items){item.parent=this;this.children.push(item);}}
  replaceChildren(...items){this.children=[];this.append(...items);}
  setAttribute(k,v){this.attrs[k]=v;}
  getAttribute(k){return this.attrs[k];}
  removeAttribute(k){delete this.attrs[k];}
  addEventListener(k,fn){(this.events[k]??=[]).push(fn);}
  async fire(k,event={}){for(const fn of this.events[k]||[])await fn(event);}
  focus(){this.focused=true;}
  all(){return this.children.flatMap(c=>[c,...c.all()]);}
  querySelectorAll(selector){return this.all().filter(n=>selector.split(',').some(s=>{
    if(s==='input:checked')return n.tag==='input'&&n.checked;
    if(s.startsWith('input[data-import-row]'))return n.tag==='input'&&n.dataset.importRow!==undefined&&(!s.endsWith(':checked')||n.checked);
    if(s.startsWith('[data-'))return n.attrs[s.slice(1,-1)]!==undefined;
    return n.tag===s;
  }));}
  querySelector(s){return this.querySelectorAll(s)[0];}
}
const text=n=>n.textContent+n.children.map(text).join('');
const visible=n=>!n.hidden&&(!n.parent||visible(n.parent));
test('document UI progresses through consent, candidates, review and completion without auto AI',async()=>{
  const oldFetch=globalThis.fetch;
  globalThis.document={createElement:tag=>new Element(tag)};
  const host=new Element('div'),root={querySelector:()=>host},calls=[];
  const source={id:'doc',name:'example.txt',expires:'2099-01-01T00:00:00Z',chunks:[{id:'C1',location:'text',start:0,end:30,text:'Revenue uses payment transactions.'}]};
  const candidate={term:'Revenue',level:'DETAIL',definition:'Period transaction total',criteria:'Sum payments',aliases:[],citations:[{chunkId:'C1',quote:source.chunks[0].text}]};
  let failAnalyze=false;
  globalThis.fetch=async(url,options)=>{
    const key=url.split('/').at(-1);calls.push(key);
    const data={upload:source,models:[],preview:{token:'once',source:'{"source":"example"}',profile:{selection:{owner:'DEMO',name:'ASSISTANT'},provider:'test',model:'test'}},analyze:{summary:'Revenue rules',warnings:[],candidates:[candidate]},review:{token:'review',entries:[{row:0,status:'NEW',value:{...candidate,enabled:false}}]},apply:{saved:1},clear:{cleared:true}};
    const fail=key==='analyze'&&failAnalyze;
    if(key==='analyze')assert.equal(JSON.parse(options.body).consent,true);
    return {ok:!fail,status:fail?503:200,headers:{get:()=> 'application/json'},json:async()=>fail?{error:'Model unavailable'}:data[key]};
  };
  try{
    let busy=false;mountGlossaryDocument(root,{csrf:{dataset:{csrfHeader:'X-CSRF'},value:'test'},busy:()=>busy,setBusy:v=>{busy=v;},canWrite:()=>true,reload:async()=>{}});
    const all=()=>host.all(),button=title=>all().find(n=>n.tag==='button'&&n.textContent===title);
    const check=title=>all().find(n=>n.tag==='label'&&text(n)===title).children[0];
    const stage=()=>all().filter(n=>n.dataset.documentStep!==undefined&&!n.hidden).map(n=>Number(n.dataset.documentStep));
    const upload=all().find(n=>n.type==='file');
    assert.deepEqual(stage(),[0]);assert.equal(button('문서 분석 준비').disabled,true);
    assert.equal(visible(button('검토한 항목 저장')),false);
    upload.files=[new File(['Revenue rules'],'example.txt')];await upload.fire('change');assert.deepEqual(calls,[]);
    await button('문서 분석 준비').fire('click');assert.deepEqual(calls,['upload']);assert.deepEqual(stage(),[1]);
    await button('로컬 임베딩 모델 조회').fire('click');
    const modelNotice=all().find(n=>n.textContent.startsWith('사용 가능한 로컬 모델'));
    assert.equal(modelNotice.parent.tag,'div');assert.equal(modelNotice.parent.parent.tag,'details');
    await button('AI 전송 미리보기').fire('click');assert.equal(button('요약·용어 후보 생성 (AI 1회)').disabled,true);
    await button('요약·용어 후보 생성 (AI 1회)').fire('click');assert.equal(calls.includes('analyze'),false);
    const consent=check('선택한 원문의 외부 전송과 AI 사용량 발생을 확인했습니다.');consent.checked=true;await consent.fire('input');
    await button('요약·용어 후보 생성 (AI 1회)').fire('click');assert.deepEqual(stage(),[2]);assert.equal(consent.checked,false);
    assert.equal(visible(button('검토한 항목 저장')),false);
    const candidateCheck=all().find(n=>n.type==='checkbox'&&n.value==='0');candidateCheck.checked=true;await candidateCheck.fire('change');
    await button('선택 후보 · 기존 사전과 비교').fire('click');assert.deepEqual(stage(),[3]);assert.equal(button('검토한 항목 저장').disabled,true);
    await button('후보 선택으로 돌아가기').fire('click');assert.deepEqual(stage(),[2]);
    await button('선택 후보 · 기존 사전과 비교').fire('click');
    const selected=all().find(n=>n.dataset.importRow==='0');selected.checked=true;await selected.fire('change');
    const saveConsent=check('선택 항목의 내용·활성 상태와 기존 사전 변경을 확인했습니다.');saveConsent.checked=true;await saveConsent.fire('input');
    assert.equal(button('검토한 항목 저장').disabled,false);await button('검토한 항목 저장').fire('click');
    assert.deepEqual(stage(),[3]);assert.equal(visible(button('검토한 항목 저장')),false);assert.match(text(host),/1개 항목을 출처/);
    await button('문서 지우기').fire('click');assert.deepEqual(stage(),[0]);assert.equal(consent.checked,false);
    upload.files=[new File(['Revenue rules'],'example.txt')];await button('문서 분석 준비').fire('click');await button('AI 전송 미리보기').fire('click');
    consent.checked=true;failAnalyze=true;await button('요약·용어 후보 생성 (AI 1회)').fire('click');
    assert.deepEqual(stage(),[1]);assert.equal(button('요약·용어 후보 생성 (AI 1회)').disabled,true);assert.equal(consent.checked,false);
    assert.equal(calls.filter(x=>x==='analyze').length,2);assert.equal(calls.filter(x=>x==='apply').length,1);
  }finally{globalThis.fetch=oldFetch;delete globalThis.document;}
});

test('glossary tabs preserve panels and support arrow, Home and End navigation',async()=>{
  const root=new Element('div'),tabs=[],panels=[];
  for(let i=0;i<2;i++){const tab=new Element('button'),panel=new Element('div');tab.setAttribute('data-glossary-tool-tab','');panel.setAttribute('data-glossary-tool-panel','');panel.id=`panel-${i}`;tab.setAttribute('aria-controls',panel.id);tabs.push(tab);panels.push(panel);root.append(tab,panel);}
  mountGlossaryTools(root);assert.deepEqual(panels.map(p=>p.hidden),[false,true]);
  await tabs[1].fire('click');assert.deepEqual(panels.map(p=>p.hidden),[true,false]);
  for(const key of ['ArrowRight','End','Home'])await tabs[key==='End'?0:1].fire('keydown',{key,preventDefault(){}});
  assert.equal(tabs[0].getAttribute('aria-selected'),'true');assert.equal(tabs[0].tabIndex,0);assert.equal(tabs[1].tabIndex,-1);
  tabs[1].disabled=true;await tabs[1].fire('click');assert.equal(panels[0].hidden,false);
});
