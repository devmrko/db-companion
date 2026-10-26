import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
export const templates=Object.freeze({
  ECHO:'BEGIN\n  RETURN p_input;\nEND;',
  GLOSSARY:'BEGIN\n  RETURN DBC_GLOSSARY_API.SEARCH(p_input);\nEND;'
});
export const ready=(plan,consent,busy,attempted,secret,kind)=>!!plan&&consent&&!busy&&!attempted&&(kind!=='CREDENTIAL'||!!secret?.trim());
if(typeof document!=='undefined')document.querySelectorAll('[data-local-create]').forEach(root=>{
  const get=k=>root.querySelector(`[data-lc-${k}]`),kind=root.dataset.kind,tr=k=>t('localCreate.'+k,k);
  let plan=null,busy=false,attempted=false;
  const controls=()=>{get('fields').disabled=busy;get('consent').disabled=busy;get('create').disabled=!ready(plan,get('consent').checked,busy,attempted,get('secret')?.value,kind);};
  const clear=()=>{plan=null;attempted=false;get('review').hidden=true;get('consent').checked=false;if(get('secret'))get('secret').value='';controls();};
  const message=text=>{get('message').textContent=text;};
  const post=(path,data)=>assistantApi(root.dataset.base+'/'+path,assistantPost(get('csrf'),data));
  if(get('body'))get('body').value=templates.ECHO;
  get('template-apply')?.addEventListener('click',()=>{get('body').value=templates[get('template').value];clear();});
  get('form').addEventListener('input',clear);
  get('consent').addEventListener('change',controls);get('secret')?.addEventListener('input',controls);
  get('form').addEventListener('submit',async e=>{
    e.preventDefault();if(busy)return;clear();busy=true;controls();message(tr('working'));get('result').hidden=true;get('detail').hidden=true;
    try{plan=await post('preview',{schema:root.dataset.schema,kind,name:get('name').value,username:get('username')?.value??'',body:get('body')?.value??''});get('sql').textContent=plan.sql;get('review').hidden=false;message(tr('review'));}
    catch(ex){message(ex.message);}finally{busy=false;controls();}
  });
  get('create').addEventListener('click',async()=>{
    if(!ready(plan,get('consent').checked,busy,attempted,get('secret')?.value,kind))return;
    busy=true;attempted=true;controls();message(tr('working'));
    const payload={token:plan.token,consent:true,secret:get('secret')?.value??''};if(get('secret'))get('secret').value='';
    try{const result=await post('apply',payload);get('result').textContent=JSON.stringify(result,null,2);get('result').hidden=false;message(tr(result.verified?'success':'invalid'));get('detail').href=kind==='FUNCTION'?'/db/functions?'+new URLSearchParams({schema:root.dataset.schema,name:result.name}):'/db/credentials';get('detail').hidden=false;document.querySelector(kind==='FUNCTION'?'[data-functions-refresh]':'[data-credentials-refresh]')?.click();}
    catch(ex){message(ex.message);}finally{payload.secret='';busy=false;controls();}
  });
  window.addEventListener('pagehide',()=>{if(get('secret'))get('secret').value='';});
});
