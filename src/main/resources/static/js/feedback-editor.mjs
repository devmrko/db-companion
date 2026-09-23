import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
export const feedbackSaveAllowed=(form,busy,attempted,confirm,consent)=>!!form?.historyReady&&!busy&&!attempted&&confirm&&consent;
if(typeof document!=='undefined')document.querySelectorAll('[data-feedback-editor]').forEach(root=>{
  const get=k=>root.querySelector(`[data-fe-${k}]`);let form=null,busy=false,attempted=false,id='';
  const message=(text,error=false)=>{get('message').textContent=text;get('message').className=error?'app-alert is-error':'app-filter-message';};
  const controls=()=>{get('fields').disabled=busy||!form?.historyReady||attempted;get('close').disabled=busy;get('install').disabled=busy;get('save').disabled=!feedbackSaveAllowed(form,busy,attempted,get('confirm').checked,get('consent').checked);root.setAttribute('aria-busy',String(busy));};
  async function load(){form=await assistantApi(root.dataset.url+'?'+new URLSearchParams({schema:root.dataset.schema,profile:root.dataset.profile,id}));get('sql').value=form.sqlText;get('sql').readOnly=form.editing;get('response').value=form.response;get('feedback').value=form.feedback;get('install').hidden=form.historyReady;}
  document.querySelectorAll('[data-feedback-edit]').forEach(button=>button.addEventListener('click',async()=>{if(busy)return;id=button.dataset.feedbackEdit;form=null;attempted=false;get('form').reset();get('result').hidden=true;root.showModal();busy=true;controls();message(t('creation.loading','Checking'));
    try{await load();message(form.historyReady?'':t('creation.historyRequired','Common history required'));}catch(ex){message(ex.message,true);}finally{busy=false;controls();}
  }));
  get('install').addEventListener('click',async()=>{if(busy)return;busy=true;controls();try{await assistantApi(root.dataset.install,assistantPost(get('csrf'),{schema:root.dataset.schema}));await load();message('');}catch(ex){message(ex.message,true);}finally{busy=false;controls();}});
  for(const name of ['confirm','consent'])get(name).addEventListener('change',controls);
  for(const name of ['sql','response','feedback'])get(name).addEventListener('input',()=>{get('confirm').checked=false;get('consent').checked=false;controls();});
  get('form').addEventListener('submit',async event=>{event.preventDefault();if(!feedbackSaveAllowed(form,busy,attempted,get('confirm').checked,get('consent').checked))return;busy=true;attempted=true;controls();
    try{const result=await assistantApi(root.dataset.url,assistantPost(get('csrf'),{token:form.token,sqlText:get('sql').value,response:get('response').value,feedback:get('feedback').value,confirmed:true,consent:true}));
      message(result.verified?t('feedbackEdit.saved','Saved'):result.stage+'\n'+result.detail.split('\n').map(v=>v.startsWith('creation.')?t(v,v):v).join('\n'),!result.verified);
    }catch(ex){message(t('creation.uncertain','Check current state')+'\n'+ex.message,true);}finally{busy=false;get('result').hidden=false;controls();}
  });
  get('close').addEventListener('click',()=>{if(!busy)root.close();});root.addEventListener('cancel',event=>{if(busy)event.preventDefault();});window.addEventListener('beforeunload',e=>{if(root.open&&(busy||!attempted)){e.preventDefault();e.returnValue='';}});
});
