import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
const text=k=>t('schedulerManage.'+k,k);
export const canApply=(plan,confirmation,consent,busy)=>!!plan&&confirmation===plan.confirmation&&consent&&!busy;
export function jobDraft(row,schema){
  const match=/^FREQ=MINUTELY;INTERVAL=([0-9]{1,5})$/.exec(row.REPEAT_INTERVAL??'');
  return {schema,action:'UPDATE',name:row.JOB_NAME,code:row.JOB_ACTION??'',minutes:match?Number(match[1]):0,comments:row.COMMENTS??''};
}
if(typeof document!=='undefined')document.querySelectorAll('[data-scheduler-manage]').forEach(root=>{
  const get=k=>root.querySelector(`[data-sm-${k}]`),post=(path,data)=>assistantApi('/db/scheduler/manage/'+path,assistantPost(get('csrf'),data));
  let state=null,revision='',plan=null,busy=false,restore=null,restoreRevision='';
  const message=value=>get('message').textContent=value;
  function controls(){root.querySelectorAll('button,input,select,textarea').forEach(n=>n.disabled=busy);get('settings').querySelectorAll('input,textarea').forEach(n=>n.disabled=busy||get('settings').hidden);get('apply').disabled=!canApply(plan,get('confirm').value,get('consent').checked,busy);get('name').readOnly=get('action').value!=='CREATE';}
  function invalidate(){plan=null;get('review').hidden=true;get('consent').checked=false;get('confirm').value='';controls();}
  function fill(d){get('name').value=d.name;get('code').value=d.code;get('minutes').value=d.minutes;get('comments').value=d.comments;}
  function selected(){const row=state.jobs.find(r=>r.JOB_NAME===get('existing').value);if(row)fill(jobDraft(row,root.dataset.schema));else fill({name:'',code:'',minutes:60,comments:''});}
  function mode(){invalidate();const create=get('action').value==='CREATE';get('existing-field').hidden=create;get('settings').hidden=!['CREATE','UPDATE'].includes(get('action').value);if(create)fill({name:'',code:'BEGIN NULL; END;',minutes:60,comments:''});else selected();controls();}
  async function run(f){if(busy)return;busy=true;controls();message(text('working'));try{await f();}catch(e){invalidate();message(e.message);}finally{busy=false;controls();}}
  async function load(){invalidate();const data=await assistantApi('/db/scheduler/manage/state');state=data.state;revision=data.revision;get('existing').replaceChildren(...state.jobs.map(row=>{const option=document.createElement('option');option.value=row.JOB_NAME;option.textContent=row.JOB_NAME;return option;}));get('form').hidden=false;mode();message(text('loaded'));}
  function draft(){return {schema:root.dataset.schema,action:get('action').value,name:get('name').value,code:get('code').value,minutes:Number(get('minutes').value),comments:get('comments').value};}
  async function preview(d){invalidate();plan=await post('preview',{draft:d,revision});get('sql').textContent=plan.sql;get('execution').textContent=plan.executionCode??'';get('before').textContent=plan.previous?JSON.stringify(plan.previous,null,2):'';get('confirm-label').textContent=plan.confirmation;get('review').hidden=false;message(text('review'));}
  get('refresh').addEventListener('click',()=>run(load));get('form').addEventListener('input',invalidate);get('action').addEventListener('change',mode);get('existing').addEventListener('change',selected);
  get('form').addEventListener('submit',e=>{e.preventDefault();run(()=>preview(draft()));});
  get('confirm').addEventListener('input',controls);get('consent').addEventListener('change',controls);
  get('apply').addEventListener('click',()=>{
    if(!canApply(plan,get('confirm').value,get('consent').checked,busy))return;
    const current=plan,input={token:plan.token,confirmation:get('confirm').value,consent:true};plan=null;
    run(async()=>{const result=await post('apply',input);await load();restore=current.previous;restoreRevision=revision;get('restore').hidden=!restore;message(text(result.status==='SUBMITTED'?'submitted':'verified'));document.querySelector('[data-scheduler-refresh]')?.click();});
  });
  get('restore').addEventListener('click',()=>run(async()=>{const latest=await assistantApi('/db/scheduler/manage/state');if(latest.revision!==restoreRevision)throw new Error(text('stale'));state=latest.state;revision=latest.revision;get('action').value='UPDATE';mode();fill(restore);await preview(restore);}));
});
