import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
export const networkCanApply=(plan,consent,busy)=>!!plan&&consent&&!busy;
if(typeof document!=='undefined')document.querySelectorAll('[data-ai-network]').forEach(root=>{
 const get=k=>root.querySelector('[data-an-'+k+']'),label=k=>t('aiNetwork.'+k,k),post=(path,data)=>assistantApi('/db/accounts/ai-network/'+path,assistantPost(get('csrf'),data));
 let plan=null,busy=false;
 const controls=()=>{for(const k of ['name','provider','status','preview','consent'])get(k).disabled=busy;get('apply').disabled=!networkCanApply(plan,get('consent').checked,busy);};
 const clear=()=>{plan=null;get('review').hidden=true;get('consent').checked=false;controls();};
 const input=()=>({name:get('name').value.trim(),provider:get('provider').value});
 async function run(action){if(busy)return;busy=true;controls();get('message').textContent=label('working');try{await action();}catch(e){get('message').textContent=e.message;}finally{busy=false;controls();}}
 for(const k of ['name','provider'])get(k).addEventListener('input',()=>{clear();get('result').textContent='';get('message').textContent='';});
 get('consent').addEventListener('change',controls);
 get('status').addEventListener('click',()=>run(async()=>{clear();const state=await post('status',input());get('result').textContent=JSON.stringify(state,null,2);get('message').textContent=label('checked');}));
 get('preview').addEventListener('click',()=>run(async()=>{clear();get('result').textContent='';plan=await post('preview',input());get('sql').textContent=plan.sql;get('result').textContent=JSON.stringify(plan.before,null,2);get('review').hidden=false;get('message').textContent=label('scope');}));
 get('apply').addEventListener('click',()=>{if(!networkCanApply(plan,get('consent').checked,busy))return;const token=plan.token;run(async()=>{clear();const result=await post('apply',{token,confirmed:true});get('result').textContent=JSON.stringify(result,null,2);get('message').textContent=label('done');});});
 controls();
});
