import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
export const repairCanApply=(plan,consent,busy)=>Boolean(plan&&consent&&!busy);
if(typeof document!=='undefined')document.querySelectorAll('[data-function-repair]').forEach(root=>{
  const get=k=>root.querySelector('[data-fr-'+k+']'),label=k=>t('functionRepair.'+k,k),post=(path,data)=>assistantApi('/db/functions/repair/'+path,assistantPost(get('csrf'),data));
  let loaded=null,plan=null,busy=false,canRestore=false;
  const controls=()=>{get('load').disabled=busy;get('name').disabled=busy;get('source').disabled=busy||!loaded;get('preview').disabled=busy||!loaded;get('restore').disabled=busy||!loaded||!canRestore;get('consent').disabled=busy;get('apply').disabled=!repairCanApply(plan,get('consent').checked,busy);};
  const clear=()=>{plan=null;get('review').hidden=true;get('consent').checked=false;controls();};
  const show=data=>{loaded=data.current;canRestore=data.canRestore;get('source').value=loaded.source;get('result').textContent=JSON.stringify({name:loaded.name,status:loaded.status,errors:loaded.errors,canRestore},null,2);};
  async function run(action){if(busy)return;busy=true;controls();get('message').textContent=label('working');try{await action();}catch(e){get('message').textContent=e.message;}finally{busy=false;controls();}}
  get('load').addEventListener('click',()=>run(async()=>{clear();loaded=null;canRestore=false;get('source').value='';show(await assistantApi('/db/functions/repair/source?'+new URLSearchParams({name:get('name').value.trim()})));get('message').textContent=label('loaded');}));
  get('name').addEventListener('input',()=>{loaded=null;canRestore=false;clear();get('source').value='';get('result').textContent='';});
  get('source').addEventListener('input',clear);get('consent').addEventListener('change',controls);
  const preview=restore=>run(async()=>{clear();plan=await post('preview',{source:get('source').value,restore});get('before').textContent=plan.before.source;get('sql').textContent=plan.sql;get('review').hidden=false;get('message').textContent=label('help');});
  get('preview').addEventListener('click',()=>preview(false));get('restore').addEventListener('click',()=>preview(true));
  get('apply').addEventListener('click',()=>{if(!repairCanApply(plan,get('consent').checked,busy))return;const token=plan.token;run(async()=>{clear();loaded=null;const data=await post('apply',{token,consent:true});show(data);get('message').textContent=label(data.current.status==='VALID'?'valid':'invalidResult');});});
  controls();
});
