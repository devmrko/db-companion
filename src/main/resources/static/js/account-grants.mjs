import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
if(typeof document!=='undefined')document.querySelectorAll('[data-privilege-setup]').forEach(root=>{
 const get=k=>root.querySelector('[data-ag-'+k+']'),label=k=>t('accountGrant.'+k,k),post=(path,data)=>assistantApi('/db/accounts/'+path,assistantPost(get('csrf'),data));
 let plan=null,busy=false;
 const controls=()=>{for(const key of ['name','operation','preview','consent'])get(key).disabled=busy;get('apply').disabled=busy||!plan||!get('consent').checked;};
 const clear=()=>{plan=null;get('review').hidden=true;get('consent').checked=false;controls();};
 async function run(action){if(busy)return;busy=true;controls();get('message').textContent=label('working');try{await action();}catch(e){get('message').textContent=e.message;}finally{busy=false;controls();}}
 for(const key of ['name','operation'])get(key).addEventListener('input',clear);
 get('consent').addEventListener('change',controls);
 get('preview').addEventListener('click',()=>run(async()=>{clear();get('result').textContent='';plan=await post('privilege-preview',{name:get('name').value.trim(),operation:get('operation').value});get('sql').textContent=plan.statements.join(';\n')+';';get('review').hidden=false;get('message').textContent=label('help');}));
 get('apply').addEventListener('click',()=>{if(!plan||busy||!get('consent').checked)return;const token=plan.token;run(async()=>{clear();const status=await post('privilege-apply',{token,confirmed:true});get('result').textContent=JSON.stringify(status,null,2);get('message').textContent=label('done');});});
 controls();
});
