import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';

export function recoveryNote(attempt){
  if(attempt?.snapshotKind!=='MANUAL_RECOVERY')return null;
  try{const value=JSON.parse(attempt.conditions).recovery;return value&&typeof value.action==='string'?value:null;}catch{return null;}
}
export function recoveryChoices(detail){return (detail?.attempts||[]).filter(a=>a.snapshotKind!=='MANUAL_RECOVERY');}
const element=(tag,text)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;return node;};
export function mountRecovery(root,csrf){
  const panel=element('section');panel.className='app-card app-config';panel.hidden=true;
  root.querySelector('[data-problem-detail]').after(panel);
  let selected=null,busy=false;
  root.addEventListener('problem-detail',event=>{
    selected=event.detail;panel.replaceChildren();panel.hidden=!selected;if(!selected)return;
    const heading=element('h2',t('followup.recovery','실패·복구 이력'));
    const note=element('p',t('followup.recoveryHelp','저장된 실행 기록에 원인·조치·재시험을 연결합니다. 조치 메모는 사용자가 기록한 내용이며 실행 성공이나 업무 정답을 증명하지 않습니다.'));
    panel.append(heading,note);
    const attempts=recoveryChoices(selected);
    for(const attempt of selected.attempts){const recovery=recoveryNote(attempt);if(!recovery)continue;
      const disclosure=element('details');disclosure.className='app-config-disclosure';
      disclosure.append(element('summary',`${new Date(attempt.capturedAt).toLocaleString()} · ${recovery.action}`));
      for(const [key,value]of [['before',recovery.beforeId],['retest',recovery.retestId||'—'],['cause',recovery.cause||'—'],['action',recovery.action],['assessment',recovery.assessment]]){
        const line=element('p');line.append(element('strong',t('followup.'+key,key)+': '),document.createTextNode(value));disclosure.append(line);
      }panel.append(disclosure);
    }
    if(!attempts.length){panel.append(element('p',t('followup.needAttempt','먼저 Select AI 테스트 결과를 이 문제 질문에 저장하세요.')));return;}
    const disclosure=element('details');disclosure.className='app-config-disclosure';disclosure.append(element('summary',t('followup.addRecovery','조치 기록 추가')));
    const form=element('form');form.className='app-config-form';const inputs={};
    for(const key of ['before','retest','cause','action','assessment']){
      const label=element('label');label.className='app-field';label.append(element('span',t('followup.'+key,key)));
      const input=element(['before','retest'].includes(key)?'select':'textarea');input.className='form-control';inputs[key]=input;
      if(input.tagName==='SELECT'){
        if(key==='retest')input.append(new Option(t('followup.notRetested','아직 재시험하지 않음'),''));
        for(const a of attempts)input.append(new Option(`${new Date(a.capturedAt).toLocaleString()} · ${a.profile||'—'} · ${a.id}`,a.id));
      }else{input.maxLength=4000;input.rows=3;input.required=key!=='cause';}
      label.append(input);form.append(label);
    }
    const status=element('p');status.setAttribute('role','status');
    const submit=element('button',t('followup.saveRecovery','조치 기록 저장'));submit.type='submit';submit.className='btn app-btn app-btn-primary';
    form.append(submit,status);disclosure.append(form);panel.append(disclosure);
    form.addEventListener('submit',async event=>{
      event.preventDefault();if(busy||!selected)return;
      if(inputs.before.value===inputs.retest.value){status.textContent=t('followup.distinct','조치 전과 재시험 기록을 다르게 선택하세요.');return;}
      const parent=selected.parent;busy=true;submit.disabled=true;
      try{
        await assistantApi('/ai-test/problems/recovery',assistantPost(csrf,{parentId:parent.id,expectedUpdatedAt:parent.updatedAt,note:{beforeId:inputs.before.value,retestId:inputs.retest.value,cause:inputs.cause.value,action:inputs.action.value,assessment:inputs.assessment.value}}));
        status.textContent=t('followup.recoverySaved','조치 기록을 저장했습니다.');
        root.dispatchEvent(new CustomEvent('problem-reload',{detail:{id:parent.id}}));
      }catch(error){status.textContent=error.message+' '+t('followup.uncertain','목록에서 저장 여부를 확인하세요. 자동 재시도하지 않았습니다.');}
      finally{busy=false;}
    });
  });
}
