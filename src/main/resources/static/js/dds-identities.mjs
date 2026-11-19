import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {canApply} from './dds-management.mjs';
import {t} from './i18n.mjs';
export const localRoles=roles=>roles.filter(r=>!(r.MAPPED_TO??'').trim()).map(r=>r.DATA_ROLE);
export const userAssignments=(state,user)=>state.assignments.filter(r=>r.GRANTEE===user&&r.GRANTEE_TYPE==='END USER');
export function probeSummary(result){
  return [result.endUser,result.rolesStatus==='VERIFIED'?result.activeRoles.join(', '):t('ddsIdentity.rolesUnknown','Roles unverified'),
    result.queryStatus==='READ_COMPLETED'?t('ddsIdentity.rows','Visible rows')+': '+result.visibleRows:
      t('ddsIdentity.queryFailed','Read rejected or failed')+' ('+(result.queryCode??'')+')'].join(' · ');
}
if(typeof document!=='undefined')document.querySelectorAll('[data-ddsi]').forEach(root=>{
  const get=k=>root.querySelector(`[data-di-${k}]`),text=(k,v)=>get(k).textContent=v;
  const post=(path,data)=>assistantApi('/db/security/manage/'+path,assistantPost(get('csrf'),data));
  let state=null,revision='',plan=null,busy=false;
  function controls(){root.querySelectorAll('button,input,select').forEach(n=>n.disabled=busy);get('apply').disabled=!canApply(plan,get('confirm').value,get('consent').checked,busy);}
  function invalidate(){plan=null;get('review').hidden=true;get('consent').checked=false;get('confirm').value='';get('password').value='';controls();}
  function options(node,values){node.replaceChildren(...values.map(value=>{const o=document.createElement('option');o.value=value;o.textContent=value;return o;}));}
  function detail(){invalidate();get('probe-password').value='';get('probe-consent').checked=false;text('probe-result','');
    const user=get('user').value,row=state.users.find(r=>r.USERNAME===user);
    text('detail',row?[row.ACCOUNT_STATUS,row.AUTHENTICATION_TYPE,row.SCHEMA??''].filter(Boolean).join(' · '):t('ddsIdentity.empty','No local users'));
    get('assignments').replaceChildren(...userAssignments(state,user).map(r=>{const li=document.createElement('li');li.textContent=[r.DATA_ROLE,r.START_TIME,r.END_TIME].filter(Boolean).join(' · ');return li;}));
  }
  function mode(){invalidate();const create=get('action').value==='CREATE';get('name-field').hidden=!create;get('name').required=create;get('auth-field').hidden=!create;get('role-field').hidden=create;}
  async function run(work){if(busy)return;busy=true;controls();text('message',t('ddsManage.working','Working'));try{await work();}catch(e){invalidate();text('message',e.message);}finally{busy=false;controls();}}
  async function load(){invalidate();const data=await assistantApi('/db/security/manage/identities');state=data.state;revision=data.revision;
    options(get('user'),state.users.map(r=>r.USERNAME));options(get('role'),localRoles(state.roles));detail();mode();get('content').hidden=false;
    const tables=await assistantApi('/db/security/manage/state');options(get('table'),[...new Set(tables.state.columns.map(r=>r.TABLE_NAME))]);text('message',t('ddsManage.loaded','Loaded'));
  }
  get('load').addEventListener('click',()=>run(load));get('user').addEventListener('change',detail);
  get('action').addEventListener('change',mode);get('form').addEventListener('input',invalidate);
  get('form').addEventListener('submit',e=>{e.preventDefault();run(async()=>{
    const create=get('action').value==='CREATE';invalidate();
    plan=await post('identity-preview',{revision,draft:{action:get('action').value,username:create?get('name').value:get('user').value,
      role:create?'':get('role').value,passwordAuthentication:create&&get('auth').checked}});
    text('sql',plan.sql);text('confirm-label',plan.confirmation);get('password-field').hidden=!plan.draft.passwordAuthentication;get('review').hidden=false;text('message',t('ddsManage.review','Review'));
  });});
  get('confirm').addEventListener('input',controls);get('consent').addEventListener('change',controls);
  get('probe-form').addEventListener('input',()=>text('probe-result',''));
  get('apply').addEventListener('click',()=>{
    if(!canApply(plan,get('confirm').value,get('consent').checked,busy))return;
    const payload={token:plan.token,confirmation:get('confirm').value,consent:true,password:get('password').value};invalidate();
    run(async()=>{try{await post('identity-apply',payload);}finally{payload.password='';}await load();text('message',t('ddsManage.verified','Verified'));});
  });
  get('probe-form').addEventListener('submit',e=>{e.preventDefault();const payload={username:get('user').value,password:get('probe-password').value,table:get('table').value,consent:get('probe-consent').checked};
    get('probe-password').value='';get('probe-consent').checked=false;text('probe-result','');
    run(async()=>{try{text('probe-result',probeSummary(await post('identity-probe',payload)));text('message',t('ddsIdentity.probeDone','Read observation completed'));}finally{payload.password='';}});
  });
});
