import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';

export function canCreateFunction(status){
  return status?.canCreate===true&&status.dictionary==='READY'&&status.text==='READY'&&['MISSING','LEGACY','INCOMPLETE'].includes(status.function);
}
export function canTestFunction(status){
  return status?.dictionary==='READY'&&status.text==='READY'&&status.function==='READY';
}
export function searchFlow(codes){
  const body=codes?.find(code=>code.type==='PACKAGE BODY')?.sql||'';
  const start=body.lastIndexOf('  FUNCTION SEARCH('),end=body.lastIndexOf('END DBC_GLOSSARY_API;');
  return start<0||end<start?'':body.slice(start,end).trim();
}
export function mountGlossaryFunction(root){
  const get=key=>root.querySelector(`[data-gf-${key}]`),csrf=get('csrf');
  let status=null,preview=null,busy=false,uncertain=false,expiryTimer=null,generation=0;
  const api=(path,data)=>assistantApi(root.dataset.base+'/'+path,data===undefined?undefined:assistantPost(csrf,data));
  const say=text=>{get('message').textContent=text;};
  function controls(){
    get('refresh').disabled=busy;
    get('preview').disabled=busy||uncertain||!canCreateFunction(status);
    get('create').disabled=busy||uncertain||!preview||Date.now()>=Date.parse(preview.expires)||!get('consent').checked;
    get('test').disabled=busy||uncertain||!canTestFunction(status)||!get('question').value.trim();
    get('question').disabled=busy;
  }
  function invalidate(){
    preview=null;clearTimeout(expiryTimer);get('confirm').hidden=true;get('consent').checked=false;controls();
  }
  function render(value){
    status=value;get('status').replaceChildren();get('errors').replaceChildren();
    for(const [key,val] of [['owner',value.owner],['dictionary',value.dictionary],['text',value.text],['function',value.function],['privilege',value.canCreate?'YES':'NO']]){
      const dt=document.createElement('dt'),dd=document.createElement('dd');
      dt.textContent=t('glossaryFunction.'+key,key);
      dd.textContent=['owner'].includes(key)?val:t('glossaryFunction.state.'+val,val);
      get('status').append(dt,dd);
    }
    for(const error of value.errors){const li=document.createElement('li');li.textContent=error;get('errors').append(li);}
    get('code').replaceChildren();
    get('flow').textContent=searchFlow(value.codes);
    for(const fragment of [...(value.codes||[])].sort((a,b)=>Number(b.type==='FUNCTION')-Number(a.type==='FUNCTION'))){
      const details=document.createElement('details'),summary=document.createElement('summary'),body=document.createElement('div'),pre=document.createElement('pre');
      details.className='app-disclosure';details.open=fragment.type==='FUNCTION';body.className='app-disclosure-body';
      summary.textContent=`${t('glossaryFunction.part.'+fragment.type.replaceAll(' ','_'),fragment.type)} · ${fragment.name} · ${t('glossaryFunction.action.'+fragment.action,fragment.action)}`;
      pre.className='app-preview-source';pre.dataset.codeLanguage='sql';pre.textContent=fragment.sql;
      body.append(pre);details.append(summary,body);get('code').append(details);
    }
    get('example').textContent=value.example;controls();
  }
  async function refresh(){
    if(busy)return;invalidate();busy=true;controls();
    try{render(await api('status'));uncertain=false;say('');}
    catch(ex){status=null;uncertain=true;say(ex.message);}
    finally{busy=false;controls();}
  }
  get('refresh').addEventListener('click',refresh);
  get('consent').addEventListener('change',controls);
  get('question').addEventListener('input',()=>{generation++;get('result').hidden=true;get('json').textContent='';controls();});
  get('preview').addEventListener('click',async()=>{
    if(busy||!canCreateFunction(status)||uncertain)return;invalidate();busy=true;controls();
    try{
      preview=await api('preview',{});get('plan').textContent=preview.sql;get('confirm').hidden=false;
      get('expiry').textContent=t('glossaryFunction.expires','확인 유효 기한: {0}',new Date(preview.expires).toLocaleString());
      expiryTimer=setTimeout(()=>{invalidate();say(t('glossaryFunction.expired','확인이 만료되었습니다. 다시 확인해 주세요.'));},Math.max(0,Date.parse(preview.expires)-Date.now()));
    }catch(ex){invalidate();say(ex.message);}
    finally{busy=false;controls();}
  });
  get('create').addEventListener('click',async()=>{
    if(get('create').disabled)return;
    const input={token:preview.token,consent:true};invalidate();busy=true;controls();
    try{
      render(await api('create',input));
      say(t(status.function==='READY'?'glossaryFunction.created':'glossaryFunction.inspect',
        status.function==='READY'?'함수가 생성되었습니다. 질문으로 검색을 확인하세요.':'생성 결과를 확인해 주세요. 자동 재생성하지 않습니다.'));
    }catch(ex){uncertain=true;status=null;say(t('glossaryFunction.uncertain','생성 결과를 확인하지 못했습니다. 객체가 남았을 수 있습니다. 다시 확인한 뒤 판단하세요.')+' '+ex.message);}
    finally{busy=false;controls();}
  });
  get('test').addEventListener('click',async()=>{
    if(get('test').disabled)return;busy=true;const ticket=++generation;controls();get('result').hidden=true;
    try{
      const data=await api('test',{question:get('question').value});
      if(ticket!==generation)return;
      get('json').textContent=JSON.stringify(data,null,2);get('result').hidden=false;
      say(t('glossaryFunction.searched','사전 검색 완료 · AI 호출 없음'));
    }catch(ex){if(ticket===generation)say(ex.message);}
    finally{busy=false;controls();}
  });
  refresh();
}
if(typeof document!=='undefined')document.querySelectorAll('[data-glossary-function]').forEach(mountGlossaryFunction);
