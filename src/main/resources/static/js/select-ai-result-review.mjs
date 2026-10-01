import {t} from './i18n.mjs';

export const canReviewResult=(outcome,execution,selected,busy)=>Boolean(!busy&&outcome?.action==='SQL'&&!outcome.error
  &&execution?.data&&!execution.error&&outcome.id===execution.resultId&&outcome.id===execution.data.searchId
  &&outcome.profile.selection.name===selected);
export const matchingResultReview=(review,execution)=>Boolean(review&&execution?.data&&review.resultId===execution.resultId
  &&review.sqlHash===execution.data.hash&&review.executedAt===execution.data.executedAt);

export function mountResultReview(root,{post,latest,selected,isBusy,setBusy,uncertain,message}){
  const get=name=>root.querySelector(`[data-result-review-${name}]`),dialog=get('dialog');
  let execution=null,prepared=null;
  function controls(){
    const busy=isBusy();get('panel').hidden=!execution?.data||Boolean(execution.error);
    get('open').disabled=!canReviewResult(latest(),execution,selected(),busy);
    get('baseline').disabled=busy;get('close').disabled=busy;
    get('run').disabled=busy||!prepared||!get('consent').checked;
  }
  function render(review){
    const matches=matchingResultReview(review,execution);get('result').hidden=!matches;
    get('content').textContent=matches?(review.error||review.text||''):'';
    if(!matches)return;
    const p=review.reviewer;
    get('meta').textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model||'—'} · ${new Date(review.requestedAt).toLocaleString()} · ${(review.elapsedMillis/1000).toFixed(2)}s`;
    get('binding').textContent=`${review.resultId} · ${review.executedAt} · SHA-256 ${review.sqlHash}`;
  }
  async function close(){
    if(isBusy())return;const token=prepared?.token;prepared=null;dialog.close();controls();
    if(token){setBusy(true);try{await post('cancel',{token});}catch(ex){message(ex.message,true);}finally{setBusy(false);}}
  }
  get('open').addEventListener('click',async()=>{
    if(!canReviewResult(latest(),execution,selected(),isBusy()))return;
    prepared=null;setBusy(true);message('');
    try{
      prepared=await post('result-review/preview',{resultId:execution.resultId,baseline:get('baseline').value});
      const p=prepared.profile;get('preview-meta').textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model||'—'} · ${prepared.characters.toLocaleString()}`;
      get('source').textContent=prepared.source;get('consent').checked=false;dialog.showModal();
    }catch(ex){message(ex.message,true);}finally{setBusy(false);}
  });
  get('close').addEventListener('click',close);dialog.addEventListener('cancel',event=>{event.preventDefault();close();});
  get('consent').addEventListener('change',controls);
  get('run').addEventListener('click',async()=>{
    if(isBusy()||!prepared||!get('consent').checked)return;
    const token=prepared.token;prepared=null;render(null);setBusy(true);dialog.close();
    get('status').textContent=t('aitest.resultReview.running','AI가 실행 기준을 검토하고 있습니다…');get('status').setAttribute('aria-busy','true');
    try{const value=await post('result-review',{token,consent:true});render(value);message(value.error||'',Boolean(value.error));}
    catch(ex){message(`${ex.message} ${t('aitest.uncertain','결과가 불확실합니다. 새로고침으로 상태를 확인하세요. 자동 재시도하지 않았습니다.')}`,true);uncertain();}
    finally{get('status').textContent='';get('status').setAttribute('aria-busy','false');setBusy(false);}
  });
  return {controls,render,setExecution(value){execution=value;prepared=null;render(null);},clear(){execution=null;prepared=null;render(null);get('baseline').value='';}};
}
