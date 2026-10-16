import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
if(typeof document!=='undefined')document.querySelectorAll('[data-scott-setup]').forEach(root=>{
  const get=k=>root.querySelector('[data-sc-'+k+']'),label=k=>t('scottSetup.'+k,k),post=(path,data)=>assistantApi('/db/scott-demo/'+path,assistantPost(get('csrf'),data));
  let plan=null,status=null,busy=false;
  const controls=()=>{get('status').disabled=busy;get('verify').disabled=busy||!status?.objects?.includes('EMP:TABLE')||!status?.objects?.includes('DEPT:TABLE');get('preview').disabled=busy||!status?.canInstall;get('install').disabled=busy||!plan||!get('consent').checked;get('consent').disabled=busy;};
  const show=v=>{get('result').textContent=JSON.stringify(v,null,2);};
  const clear=()=>{plan=null;get('consent').checked=false;get('review').hidden=true;get('admin').hidden=true;get('admin-sql').textContent='';};
  async function run(action){if(busy)return;busy=true;controls();get('message').textContent=label('working');try{await action();}catch(e){get('message').textContent=e.message;}finally{busy=false;controls();}}
  async function refresh(){clear();status=null;status=await assistantApi('/db/scott-demo/status');show(status);get('admin-sql').textContent=(status.adminSql||[]).join('\n');get('admin').hidden=!status.adminSql?.length;get('message').textContent=label(status.canInstall?'ready':status.objects.length?'exists':'privileges');}
  get('status').addEventListener('click',()=>run(refresh));
  get('verify').addEventListener('click',()=>run(async()=>{clear();const result=await assistantApi('/db/scott-demo/verify');show(result);get('message').textContent=label(result.verified?'installed':'failed');}));
  get('preview').addEventListener('click',()=>run(async()=>{clear();plan=await post('preview',{});get('sql').textContent=plan.statements.join(';\n\n')+';';get('review').hidden=false;get('message').textContent=label('warning');}));
  get('consent').addEventListener('change',controls);
  get('install').addEventListener('click',()=>{if(!plan||!get('consent').checked)return;const token=plan.token;run(async()=>{clear();status=null;const result=await post('install',{token,confirmed:true});show(result);get('message').textContent=label(result.verified?'installed':'failed');});});
  run(refresh);
});
