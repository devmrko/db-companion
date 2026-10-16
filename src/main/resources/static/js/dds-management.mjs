import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
const label=k=>t('ddsManage.'+k,k);
export const canApply=(plan,confirmation,consent,busy)=>!!plan&&confirmation===plan.confirmation&&consent&&!busy;
export function roleMapping(value){if(!value)return {mode:'LOCAL',group:''};const at=value.indexOf('=');const mode=value.slice(0,at);if(!['IAM_OAUTH_GROUP','IAM_GROUP_NAME'].includes(mode))throw new Error(label('mappingInvalid'));return {mode,group:value.slice(at+1)};}
export function grantDraft(row,schema){
  const match=/^\s*"?([A-Z][A-Z0-9_]{0,127})"?\s*=\s*(-?[0-9]+(?:\.[0-9]+)?|'(?:[^']|'')*')\s*$/.exec(row.PREDICATE??'');
  if(!match)throw new Error(label('unsupported'));
  const text=match[2].startsWith("'");
  return {schema,kind:'GRANT',action:'UPDATE',name:row.GRANT_NAME,table:row.OBJECT_NAME,role:row.GRANTEE,column:match[1],valueType:text?'TEXT':'NUMBER',value:text?match[2].slice(1,-1).replaceAll("''","'"):match[2],enabled:false};
}
if(typeof document!=='undefined')document.querySelectorAll('[data-ddsm]').forEach(root=>{
  const get=k=>root.querySelector(`[data-dm-${k}]`),post=(path,data)=>assistantApi('/db/security/manage/'+path,assistantPost(get('csrf'),data));
  let state=null,revision='',plan=null,busy=false,restore=null,restoreRevision='';
  const message=text=>get('message').textContent=text;
  function controls(){root.querySelectorAll('button,input,select').forEach(n=>n.disabled=busy);get('apply').disabled=!canApply(plan,get('confirm').value,get('consent').checked,busy);get('name').readOnly=get('action').value==='UPDATE';}
  function invalidate(){plan=null;get('review').hidden=true;get('consent').checked=false;get('confirm').value='';controls();}
  function options(select,values){select.replaceChildren(...values.map(value=>{const option=document.createElement('option');option.value=value;option.textContent=value;return option;}));}
  function columns(){options(get('column'),state.columns.filter(r=>r.TABLE_NAME===get('table').value&&['NUMBER','VARCHAR2','CHAR','NVARCHAR2','NCHAR'].includes(r.DATA_TYPE)).map(r=>r.COLUMN_NAME));}
  function mappingMode(){const local=get('role-mode').value==='LOCAL';get('group-field').hidden=local;get('enabled-field').hidden=!local;}
  function fill(d){get('name').value=d.name;get('enabled').checked=d.enabled;get('table').value=d.table;columns();get('role').value=d.role;get('column').value=d.column;get('value-type').value=d.valueType||'NUMBER';get('value').value=d.value;const m=roleMapping(d.mapping);get('role-mode').value=m.mode;get('group').value=m.group;mappingMode();}
  function selected(){
    if(get('action').value!=='UPDATE')return;
    const name=get('existing').value,role=get('kind').value==='ROLE';
    const row=(role?state.roles:state.grants).find(r=>(role?r.DATA_ROLE:r.GRANT_NAME)===name);
    if(!row){get('name').value='';return;}
    try{fill(role?{name,enabled:['TRUE','1'].includes(String(row.ENABLED_BY_DEFAULT).toUpperCase()),mapping:row.MAPPED_TO,table:'',role:'',column:'',valueType:'NUMBER',value:''}:grantDraft(row,root.dataset.schema));}
    catch(e){get('name').value='';message(e.message);}
  }
  function mode(){
    invalidate();const role=get('kind').value==='ROLE',update=get('action').value==='UPDATE';
    get('role-field').hidden=!role;get('grant-fields').hidden=role;get('existing').parentElement.hidden=!update;
    options(get('existing'),[...new Set((role?state.roles:state.grants).map(r=>role?r.DATA_ROLE:r.GRANT_NAME))]);
    if(update)selected();else get('name').value='';
  }
  async function run(action){if(busy)return;busy=true;controls();message(label('working'));try{await action();}catch(e){invalidate();message(e.message);}finally{busy=false;controls();}}
  async function load(){invalidate();const data=await assistantApi('/db/security/manage/state');state=data.state;revision=data.revision;get('form').hidden=false;options(get('table'),[...new Set(state.columns.map(r=>r.TABLE_NAME))]);options(get('role'),state.roles.map(r=>r.DATA_ROLE));columns();mode();message(label('loaded'));}
  function draft(){return {schema:root.dataset.schema,kind:get('kind').value,action:get('action').value,name:get('name').value,table:get('table').value,role:get('role').value,column:get('column').value,valueType:get('value-type').value,value:get('value').value,enabled:get('enabled').checked,mapping:get('role-mode').value==='LOCAL'?'':get('role-mode').value+'='+get('group').value};}
  async function preview(d){invalidate();plan=await post('preview',{draft:d,revision});get('sql').textContent=plan.sql+';';get('before').textContent=plan.previous?JSON.stringify(plan.previous,null,2):'';get('confirm-label').textContent=plan.confirmation;get('review').hidden=false;message(label('review'));}
  get('refresh').addEventListener('click',()=>run(load));
  get('form').addEventListener('input',invalidate);
  get('kind').addEventListener('change',mode);get('action').addEventListener('change',mode);
  get('existing').addEventListener('change',selected);get('table').addEventListener('change',columns);
  get('role-mode').addEventListener('change',mappingMode);
  get('form').addEventListener('submit',e=>{e.preventDefault();run(()=>preview(draft()));});
  get('confirm').addEventListener('input',controls);get('consent').addEventListener('change',controls);
  get('apply').addEventListener('click',()=>{
    if(!canApply(plan,get('confirm').value,get('consent').checked,busy))return;
    const current=plan,payload={token:plan.token,confirmation:get('confirm').value,consent:true};plan=null;
    run(async()=>{const result=await post('apply',payload);await load();restore=current.previous;restoreRevision=revision;get('restore').hidden=!restore;message(label('verified')+' · '+result.status);document.querySelector('[data-dds-refresh]')?.click();});
  });
  get('restore').addEventListener('click',()=>run(async()=>{
    const latest=await assistantApi('/db/security/manage/state');if(latest.revision!==restoreRevision)throw new Error(label('stale'));
    state=latest.state;revision=latest.revision;get('kind').value=restore.kind;get('action').value='UPDATE';mode();fill(restore);await preview(restore);
  }));
});
