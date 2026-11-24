import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
if(typeof document!=='undefined')document.querySelectorAll('[data-demo-data]').forEach(root=>{
  const get=key=>root.querySelector('[data-demo-'+key+']');
  const form=get('form');
  let busy=false;
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy||!form.reportValidity())return;
    busy=true;get('build').disabled=true;get('message').textContent='';
    const data=Object.fromEntries(new FormData(form));data.rows=Number(data.rows);
    try{
      const result=await assistantApi('/db/demo-data/prompt',assistantPost(get('csrf'),data));
      get('prompt').value=result.prompt;get('input').hidden=true;get('review').hidden=false;get('copy').focus();
    }catch(error){get('message').textContent=error.message;}
    finally{busy=false;get('build').disabled=false;}
  });
  get('back').addEventListener('click',()=>{get('prompt').value='';get('review').hidden=true;get('input').hidden=false;get('message').textContent='';form.elements.scenario.focus();});
  get('copy').addEventListener('click',async()=>{
    try{await navigator.clipboard.writeText(get('prompt').value);get('message').textContent=t('demoData.copied','프롬프트를 복사했습니다. Codex에 전달하세요.');}
    catch{get('prompt').focus();get('prompt').select();get('message').textContent=t('demoData.manualCopy','선택한 프롬프트를 직접 복사하세요.');}
  });
});
