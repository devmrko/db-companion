import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
export const credentialCanApply=(plan,consent,hasValue,busy)=>Boolean(plan&&consent&&hasValue&&!busy);
if(typeof document!=='undefined')document.querySelectorAll('[data-credential-edit]').forEach(root=>{
  const get=k=>root.querySelector('[data-ce-'+k+']'),label=k=>t('credentialEdit.'+k,k),post=(path,data)=>assistantApi('/db/credentials/edit/'+path,assistantPost(get('csrf'),data));
  let plan=null,busy=false;
  const controls=()=>{for(const k of ['name','attribute','preview','restore','consent','value'])get(k).disabled=busy;get('apply').disabled=!credentialCanApply(plan,get('consent').checked,Boolean(get('value').value),busy);};
  const clear=()=>{plan=null;get('value').value='';get('consent').checked=false;get('review').hidden=true;controls();};
  async function run(action){if(busy)return;busy=true;controls();get('message').textContent=label('working');try{await action();}catch(e){get('message').textContent=e.message;}finally{busy=false;controls();}}
  for(const k of ['name','attribute'])get(k).addEventListener('input',clear);
  get('value').addEventListener('input',controls);get('consent').addEventListener('change',controls);
  const preview=restore=>run(async()=>{clear();get('result').textContent='';plan=await post('preview',{name:get('name').value.trim(),attribute:get('attribute').value,restore});get('sql').textContent=plan.sql;get('purpose').textContent=label(restore?'restoreHelp':'changeHelp');get('value-label').textContent=label(restore?'oldValue':'value');get('review').hidden=false;get('message').textContent=label('review');});
  get('preview').addEventListener('click',()=>preview(false));get('restore').addEventListener('click',()=>preview(true));
  get('apply').addEventListener('click',()=>{if(!credentialCanApply(plan,get('consent').checked,Boolean(get('value').value),busy))return;let value=get('value').value;const token=plan.token;clear();run(async()=>{try{const result=await post('apply',{token,value,consent:true});get('result').textContent=JSON.stringify(result,null,2);get('message').textContent=label('done');}finally{value=null;get('value').value='';}});});
  window.addEventListener('pagehide',()=>{get('value').value='';});controls();
});
