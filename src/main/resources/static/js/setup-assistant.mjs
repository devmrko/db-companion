import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';

export const canApplyChange=(change,consent,busy)=>Boolean(change?.token)&&consent===true&&!busy;
if(typeof document!=='undefined')document.querySelectorAll('[data-setup-assistant]').forEach(root=>{
  const get=name=>root.querySelector(`[data-setup-${name}]`);
  let preview=null,change=null,busy=false;
  const message=text=>{get('status').textContent=text;};
  const post=(path,value)=>assistantApi('/ai-setup/assistant/'+path,assistantPost(get('csrf'),value));
  function controls(){get('preview').disabled=busy;get('question').disabled=busy;get('analyze').disabled=busy||!preview||!get('consent').checked;get('change').disabled=busy;get('apply').disabled=!canApplyChange(change,get('apply-consent').checked,busy);}
  async function perform(work){if(busy)return;busy=true;controls();message('');try{await work();}catch(error){message(error.message);}finally{busy=false;controls();}}
  function reset(){preview=null;change=null;get('consent').checked=false;get('apply-consent').checked=false;get('result').hidden=true;get('preview-panel').hidden=true;get('change-panel').hidden=true;}
  get('question').addEventListener('input',()=>{reset();message('');controls();});
  get('consent').addEventListener('change',controls);get('apply-consent').addEventListener('change',controls);
  get('preview').addEventListener('click',()=>perform(async()=>{
    reset();
    preview=await post('preview',{purpose:root.dataset.purpose,profile:root.dataset.profile,question:get('question').value});
    get('source').textContent=preview.source;get('model').textContent=`${preview.profile.selection.name} · ${preview.profile.provider} / ${preview.profile.model||'—'}`;
    get('consent').checked=false;get('preview-panel').hidden=false;
  }));
  get('analyze').addEventListener('click',()=>perform(async()=>{
    const token=preview.token;preview=null;const result=await post('analyze',{token,consent:get('consent').checked});
    get('explanation').textContent=result.explanation;get('result').hidden=false;get('change').hidden=!(result.action||result.enableConversation);get('preview-panel').hidden=true;
  }));
  get('change').addEventListener('click',()=>perform(async()=>{
    change=await post('change-preview',{});get('change-value').textContent=`${change.profile} · ${change.attribute||'conversation'}: ${change.before||'—'} → ${change.after}`;
    get('apply-consent').checked=false;get('change-panel').hidden=false;
  }));
  get('apply').addEventListener('click',()=>perform(async()=>{
    const token=change.token;change=null;const result=await post('apply',{token,consent:get('apply-consent').checked});
    get('change-panel').hidden=true;get('change').hidden=true;
    message(result.message||t('setup.ai.applied','적용 결과를 확인했습니다. 설정 다시 확인을 눌러 재진단하세요.'));
    if(!result.verified)message(t('setup.ai.unverified','저장 결과를 확정하지 못했습니다. 프로필과 변경 이력을 확인하세요. 자동 재시도하지 않습니다.'));
  }));controls();
});
