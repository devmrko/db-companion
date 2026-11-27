import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';

export const canApplyChange=(change,consent,busy)=>Boolean(change?.token)&&consent===true&&!busy;
if(typeof document!=='undefined')document.querySelectorAll('[data-setup-assistant]').forEach(root=>{
  const get=name=>root.querySelector(`[data-setup-${name}]`);
  let preview=null,change=null,busy=false;
  const message=text=>{get('status').textContent=text;};
  const post=(path,value)=>assistantApi('/ai-setup/assistant/'+path,assistantPost(get('csrf'),value));
  function controls(){get('preview').disabled=busy;get('question').disabled=busy;get('tools').disabled=busy;get('analyze').disabled=busy||!preview||!get('consent').checked;get('change').disabled=busy;get('apply').disabled=!canApplyChange(change,get('apply-consent').checked,busy);}
  function showTools(executions){get('tools-panel').hidden=!executions?.length;get('tool-results').textContent=(executions||[]).map(item=>`${item.sequence}. ${item.name}\n${item.result}`).join('\n\n');}
  async function perform(work){if(busy)return;busy=true;controls();message('');try{await work();}catch(error){message(error.message);if(error.diagnostic?.executions?.length){get('explanation').textContent='';get('change').hidden=true;get('result').hidden=false;showTools(error.diagnostic.executions);}}finally{busy=false;controls();}}
  function reset(){preview=null;change=null;get('consent').checked=false;get('apply-consent').checked=false;get('result').hidden=true;get('preview-panel').hidden=true;get('change-panel').hidden=true;get('tools-panel').hidden=true;get('tool-results').textContent='';}
  get('question').addEventListener('input',()=>{reset();message('');controls();});
  get('tools').addEventListener('change',()=>{reset();message('');controls();});
  get('consent').addEventListener('change',controls);get('apply-consent').addEventListener('change',controls);
  get('preview').addEventListener('click',()=>perform(async()=>{
    reset();
    preview=await post('preview',{purpose:root.dataset.purpose,profile:root.dataset.profile,question:get('question').value,serviceTools:get('tools').checked});
    get('source').textContent=preview.source;get('model').textContent=`${preview.profile.selection.name} · ${preview.profile.provider} / ${preview.profile.model||'—'}`;
    get('consent').checked=false;get('preview-panel').hidden=false;
    get('consent-text').textContent=get('tools').checked?t('setup.ai.toolsConsent','표시된 정보와 후속 서비스 조회 결과의 외부 전송 및 최대 AI 호출 7회 비용 발생에 동의합니다.'):t('setup.ai.consent','표시된 정보의 외부 전송과 AI 호출 1회 비용 발생에 동의합니다.');
  }));
  get('analyze').addEventListener('click',()=>perform(async()=>{
    const token=preview.token;preview=null;const result=await post('analyze',{token,consent:get('consent').checked});
    get('explanation').textContent=result.explanation;get('result').hidden=false;get('change').hidden=!(result.action||result.enableConversation);get('preview-panel').hidden=true;
    showTools(result.executions);
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
