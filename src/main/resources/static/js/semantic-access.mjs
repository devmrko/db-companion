import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
export function canGrant(preview,user,consent,busy,now=Date.now()) {return !!preview&&preview.username===user&&consent&&!busy&&now<Date.parse(preview.expires);}
export function mountAccess(root){
 const get=k=>root.querySelector(`[data-sa-${k}]`),label=k=>t('semanticContext.'+k,k);
 let busy=false,preview=null,checked=null,timer;
 const api=(path,data)=>assistantApi('/ai-profiles/semantic/access'+path,data===undefined?undefined:assistantPost(get('csrf'),data));
 const say=v=>{get('message').textContent=v;};
 const controls=()=>{get('user').disabled=busy;get('check').disabled=busy||!get('user').value;get('preview').disabled=busy||!checked||checked.username!==get('user').value||checked.granted;get('grant').disabled=!canGrant(preview,get('user').value,get('consent').checked,busy);};
 const invalidate=()=>{preview=null;clearTimeout(timer);get('review').hidden=true;get('consent').checked=false;};
 async function check(){if(busy)return;busy=true;checked=null;invalidate();controls();try{checked=await api('?'+new URLSearchParams({username:get('user').value}));say(label(checked.granted?'accessAlready':'accessMissing'));}catch(e){say(e.message);}finally{busy=false;controls();}}
 get('user').addEventListener('change',()=>{checked=null;invalidate();controls();say('');});
 get('check').addEventListener('click',check);
 get('preview').addEventListener('click',async()=>{if(busy)return;busy=true;invalidate();controls();try{preview=await api('/preview',{username:get('user').value});get('sql').textContent=preview.sql;get('review').hidden=false;timer=setTimeout(()=>{invalidate();controls();say(label('accessExpired'));},Math.max(0,Date.parse(preview.expires)-Date.now()));}catch(e){say(e.message);}finally{busy=false;controls();}});
 get('consent').addEventListener('change',controls);
 get('grant').addEventListener('click',async()=>{if(!canGrant(preview,get('user').value,get('consent').checked,busy))return;const input={token:preview.token,consent:true};busy=true;checked=null;invalidate();controls();try{checked=await api('/grant',input);say(label(checked.granted?'accessGranted':'uncertain'));}catch(e){say(e.message+' '+label('uncertain'));}finally{busy=false;controls();}});
 async function load(){busy=true;controls();try{for(const user of await api('/users')){const option=document.createElement('option');option.value=user;option.textContent=user;get('user').append(option);}}catch(e){say(e.message);}finally{busy=false;controls();}}
 load();
}
if(typeof document!=='undefined')document.querySelectorAll('[data-sem-access]').forEach(mountAccess);
