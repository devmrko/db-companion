import {t} from './i18n.mjs';
/** Local review protocol: selected definitions, original question and one explicit SQL-generation call. */
export const validDefinitionChoice=c=>Boolean(c&&['EXACT_ALIAS','ORACLE_TEXT'].includes(c.mode)&&typeof c.originalQuestion==='string'&&c.originalQuestion.trim()&&c.originalQuestion.length<=16000&&!c.originalQuestion.includes('\0')&&c.profile?.trim()&&c.searchQuery?.trim()&&Array.isArray(c.tables)&&c.tables.length>0&&c.tables.length<=100&&Array.isArray(c.selected)&&c.selected.length>0&&c.selected.length<=20&&new Set(c.selected).size===c.selected.length);
export function definitionReviewFlow({selection,contextPreview,generationPreview,generate,cancel=()=>{},changed=()=>{},onPreview=()=>{},onResult=()=>{},onError=()=>{}}){
  let revision=0,active=null,busy=false,consent=false,spent=false;
  const key=()=>JSON.stringify(selection());
  const state=()=>({busy,consent,spent,preview:active?.preview??null,canPreview:!busy&&validDefinitionChoice(selection()),canSend:!busy&&!!active&&!spent&&consent&&active.key===key()});
  const notify=()=>changed(state());
  const invalidate=()=>{revision++;const token=active?.preview?.token;active=null;consent=false;spent=false;if(token)Promise.resolve().then(()=>cancel(token)).catch(()=>{});notify();};
  return {
    state,invalidate,
    confirm(value){consent=value===true;notify();},
    async prepare(){
      if(busy||!validDefinitionChoice(selection()))return false;
      invalidate();const choice=structuredClone(selection()),fingerprint=JSON.stringify(choice),request=revision;busy=true;notify();
      try{
        const context=await contextPreview(choice);if(request!==revision||fingerprint!==key())return false;
        const preview=await generationPreview(choice,context.token);
        if(request!==revision||fingerprint!==key()){Promise.resolve().then(()=>cancel(preview.token)).catch(()=>{});return false;}
        active={preview,key:fingerprint};onPreview(preview,choice);return true;
      }catch(error){if(request===revision)onError(error);return false;}
      finally{busy=false;notify();}
    },
    async send(){
      if(!state().canSend)return false;
      const request=revision,prepared=active;spent=true;busy=true;notify();
      try{const result=await generate(prepared.preview.token);if(request===revision&&prepared.key===key())onResult(result);return true;}
      catch(error){if(request===revision)onError(error);return false;}
      finally{busy=false;notify();}
    }
  };
}
/** All rendered values use textContent; this module never evaluates or executes generated SQL. */
export function wireDefinitionReview(nodes,{selection,contextPreview,generationPreview,generate,cancel,run=fn=>fn(),externalBusy=()=>false}){
  let flow;
  const render=()=>{if(!flow)return;const state=flow.state(),locked=state.busy||externalBusy();nodes.prepare.disabled=locked||!state.canPreview;nodes.send.disabled=locked||!state.canSend;nodes.consent.disabled=locked||!state.preview||state.spent;nodes.consent.checked=state.consent;nodes.close.disabled=state.busy;};
  flow=definitionReviewFlow({selection,contextPreview,generationPreview,generate,cancel,changed:render,
    onPreview:(preview,choice)=>{nodes.question.textContent=choice.originalQuestion;nodes.payload.textContent=preview.source;const p=preview.profile;nodes.profile.textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model??'—'} · ${t('ontology.glossary.oneSql','SQL generation: one call; generated SQL is not executed.')}`;nodes.status.textContent='';nodes.dialog.showModal();},
    onResult:result=>{nodes.status.textContent=[result.sqlResponse?'SQL 형태 응답 · 실행·정합성 미검증':'응답 확인 필요 · 자동 재시도하지 않습니다.',result.error,result.code,result.phase,result.text].filter(Boolean).join('\n\n');},
    onError:error=>{if(!nodes.dialog.open){nodes.question.textContent=selection().originalQuestion;nodes.payload.textContent='';nodes.profile.textContent='';nodes.dialog.showModal();}nodes.status.textContent=error.message||'요청 결과를 확인하지 못했습니다. 자동 재시도하지 않습니다.';}
  });
  const invalidate=()=>{flow.invalidate();nodes.dialog.close();nodes.status.textContent='';};
  nodes.prepare.addEventListener('click',()=>run(()=>flow.prepare()));
  nodes.send.addEventListener('click',()=>run(()=>flow.send()));
  nodes.consent.addEventListener('change',()=>flow.confirm(nodes.consent.checked));
  nodes.close.addEventListener('click',()=>{if(!flow.state().busy)invalidate();});nodes.dialog.addEventListener('cancel',event=>{if(flow.state().busy)event.preventDefault();else invalidate();});
  nodes.originalQuestion.addEventListener('input',invalidate);
  render();return {invalidate,render,state:flow.state};
}
