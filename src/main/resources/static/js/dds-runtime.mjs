import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
export function readinessChecks(r={}){
  const state=value=>value==='PRESENT'?'CONFIGURED':value==='MISSING'?'ACTION':'UNKNOWN';
  return [
    {key:'provider',term:'identity_provider_type',state:r.provider==='OCI_IAM'?'CONFIGURED':['UNSET','NONE'].includes(r.provider)?'ACTION':'UNKNOWN'},
    {key:'oauth',term:'identity_provider_oauth_config',state:state(r.oauthConfig)},
    {key:'credential',term:'OCI_IAM_DOMAIN_DB_CRED$',state:state(r.signingCredential)}
  ];
}
export function runtimeSummary(r){
  const lines=r.steps.map((s,i)=>`${i+1}. ${s.requestedUser} → ${s.endUser??'—'} · ${s.status}${s.code?' '+s.code:''} · ${s.visibleRows??'—'} · clear=${s.contextCleared}`);
  if(r.memoryScope==='RECORD_ACCESS_ONLY')r.steps.forEach((s,i)=>lines.push(`${i+1}. record A=${s.recordAVisible??'—'}, B=${s.recordBVisible??'—'}`));
  return lines.join('\n');
}
if(typeof document!=='undefined')document.querySelectorAll('[data-dds-runtime]').forEach(root=>{
  const get=k=>root.querySelector(`[data-dr-${k}]`);
  let busy=false;
  function readiness(r){
    const host=get('readiness-result');host.replaceChildren();
    for(const check of readinessChecks(r)){
      const row=document.createElement('details');row.className='app-config-disclosure';
      const summary=document.createElement('summary'),badge=document.createElement('span');
      badge.className='app-setup-state app-setup-'+check.state;
      badge.textContent=t('setup.state.'+check.state,check.state);summary.append(badge);
      const label=document.createElement('span');label.textContent=t('ddsRuntime.check.'+check.key,check.key);summary.append(label);
      const required=document.createElement('span');required.className='app-requirement';required.textContent=t('setup.requirement.REQUIRED','Required');summary.append(required);
      const term=document.createElement('code');term.textContent=check.term;
      row.append(summary,term);host.append(row);
    }
  }
  get('readiness').addEventListener('click',async()=>{
    if(busy)return;busy=true;get('readiness').disabled=true;
    try{readiness(await assistantApi('/db/security/manage/runtime-readiness'));}
    catch(error){readiness({});const errorText=document.createElement('p');errorText.textContent=error.message;get('readiness-result').append(errorText);}
    finally{busy=false;get('readiness').disabled=false;}
  });
  function mode(){const tokens=get('mode').value==='TOKEN',memory=get('target').value==='MEMORY';
    root.querySelectorAll('[data-dr-token-field]').forEach(n=>n.hidden=!tokens);
    for(const k of ['token-a','token-b']){get(k).required=tokens;if(!tokens)get(k).value='';}
    get('memory-fields').hidden=!memory;get('table-field').hidden=memory;get('table').required=!memory;
    for(const k of ['store','record-a','record-b'])get(k).required=memory;
    get('result').textContent='';
  }
  get('mode').addEventListener('change',mode);get('target').addEventListener('change',mode);
  get('form').addEventListener('input',()=>get('result').textContent='');
  get('form').addEventListener('submit',async e=>{
    e.preventDefault();if(busy||!get('consent').checked)return;
    const payload={poolUser:get('pool-user').value,poolPassword:get('pool-password').value,databaseToken:get('database-token').value,
      mode:get('mode').value,a:{name:get('user-a').value,token:get('token-a').value},b:{name:get('user-b').value,token:get('token-b').value},
      table:get('table').value,memoryStore:get('target').value==='MEMORY'?get('store').value:'',recordA:get('record-a').value,recordB:get('record-b').value,consent:true};
    for(const k of ['pool-password','database-token','token-a','token-b'])get(k).value='';get('consent').checked=false;
    busy=true;root.querySelectorAll('input,select,button').forEach(n=>n.disabled=true);get('result').textContent=t('ddsManage.working','Working');
    try{get('result').textContent=runtimeSummary(await assistantApi('/db/security/manage/runtime-probe',assistantPost(get('csrf'),payload)));}
    catch(error){get('result').textContent=error.message;}
    finally{payload.poolPassword='';payload.databaseToken='';payload.a.token='';payload.b.token='';busy=false;root.querySelectorAll('input,select,button').forEach(n=>n.disabled=false);}
  });mode();readiness({});
});
