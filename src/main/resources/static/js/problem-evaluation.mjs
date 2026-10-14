import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';

export const verdictLabel=value=>t('evaluation.'+value,value);
export const evaluationIdentity=(parentId,attemptId,criteria)=>JSON.stringify([parentId,attemptId,criteria]);

// A detached response must never replace the selected question or a newer preview.
export function mountProblemEvaluation(root,csrf){
  const host=document.createElement('section');host.className='app-card';host.hidden=true;
  root.querySelector('[data-problem-actions]').before(host);
  const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text)n.textContent=text;if(cls)n.className=cls;return n;};
  const label=(key,control)=>{const n=el('label');n.append(el('span',t('evaluation.'+key,key)),control);return n;};
  const button=(key,handler)=>{const n=el('button',t('evaluation.'+key,key),'btn app-btn app-btn-secondary');n.type='button';n.addEventListener('click',handler);return n;};
  const attempt=el('select',null,'form-select'),criteria=el('textarea',null,'form-control');criteria.maxLength=16000;criteria.rows=3;
  const form=el('div',null,'workbench-form');form.append(label('attempt',attempt),label('criteria',criteria));
  const basis=el('details'),expected=el('p'),expectedSql=el('pre',null,'app-preview-value'),actualSql=el('pre',null,'app-preview-value'),savedResponse=el('pre',null,'app-preview-value');
  expectedSql.dataset.codeLanguage='sql';actualSql.dataset.codeLanguage='sql';
  basis.append(el('summary',t('evaluation.evidenceView','평가 기준·SQL·저장 응답 확인')),
    el('h3',t('problemQuestion.expected','기대 동작')),expected,
    el('h3',t('problemQuestion.expectedSql','참고 SQL')),expectedSql,
    el('h3',t('problemQuestion.sql','SQL')),actualSql,
    el('h3',t('evaluation.savedResponse','저장 응답 (실행 결과로 검증되지 않음)')),savedResponse);
  const transmission=el('details'),previewText=el('pre',null,'app-preview-value');transmission.append(el('summary',t('evaluation.transmission','전송 자료')),previewText);
  const consent=el('input');consent.type='checkbox';const consentLabel=label('consent',consent);consentLabel.className='workbench-check';
  const message=el('p',null,'app-filter-message');message.setAttribute('role','status');message.setAttribute('aria-live','polite');
  const report=el('pre',null,'app-preview-value'),decision=el('div',null,'workbench-form');decision.hidden=true;
  const verdict=el('select',null,'form-select');verdict.append(new Option(t('evaluation.choose','판정 선택'),''));
  for(const v of ['ACCEPTABLE','NEEDS_CHANGE','UNDECIDED'])verdict.append(new Option(verdictLabel(v),v));
  const reason=el('textarea',null,'form-control');reason.maxLength=8000;
  const confirmed=el('input');confirmed.type='checkbox';const confirmedLabel=label('confirm',confirmed);confirmedLabel.className='workbench-check';
  const history=el('div'),heading=el('h2',t('evaluation.title','AI 검토 · 사용자 판정'));
  let selected=null,prepared=null,result=null,busy=false,generation=0;
  const identity=()=>evaluationIdentity(selected?.parent.id,attempt.value,criteria.value);
  const showEvidence=()=>{
    const item=selected?.attempts.find(a=>a.id===attempt.value);
    expected.textContent=selected?.parent.expected||'—';expectedSql.textContent=selected?.parent.expectedSql||'';expectedSql.hidden=!expectedSql.textContent;
    actualSql.textContent=item?.sql||'';actualSql.hidden=!actualSql.textContent;savedResponse.textContent=item?.response||'—';
  };
  const post=(path,data)=>assistantApi('/ai-test/problems/evaluation/'+path,assistantPost(csrf,data));
  const setMessage=(text,error=false)=>{message.textContent=text;message.className=error?'app-alert is-error':'app-filter-message';};
  const lock=()=>{attempt.disabled=busy;criteria.disabled=busy;prepare.disabled=busy||!attempt.value;run.disabled=busy||!prepared||!consent.checked;save.disabled=busy||!result||!verdict.value||!reason.value.trim()||!confirmed.checked;};
  const invalidate=()=>{generation++;prepared=null;result=null;previewText.textContent='';report.textContent='';consent.checked=false;confirmed.checked=false;decision.hidden=true;verdict.value='';reason.value='';lock();};
  async function loadHistory(){
    const parentId=selected?.parent.id;if(!parentId)return;
    try{
      const rows=await assistantApi('/ai-test/problems/evaluation/history?'+new URLSearchParams({parentId}));
      if(selected?.parent.id!==parentId)return;history.replaceChildren();
      if(!rows.length){history.append(el('p',t('evaluation.empty','저장된 평가가 없습니다.'),'workbench-empty'));return;}
      for(const row of rows){
        const block=el('details');block.append(el('summary',`${verdictLabel(row.verdict)} · ${row.decidedAt} · ${row.actor}`));
        block.append(el('p',`${t('evaluation.attempt','실행 기록')}: ${row.attemptId}`));
        block.append(el('p',`${t('evaluation.basis','평가 기준 SHA-256')}: ${row.report.context.criteriaHash}`));
        const reviewer=row.report.reviewer;block.append(el('p',`${reviewer.selection.owner}.${reviewer.selection.name} · ${reviewer.provider} / ${reviewer.model} · ${row.report.elapsedMillis} ms`));
        block.append(el('h3',t('evaluation.human','사용자 판정 사유')),el('p',row.reason));
        block.append(el('h3',t('evaluation.ai','AI 검토 의견 (참고)')),el('pre',row.report.text,'app-preview-value'));
        const input=el('details');input.append(el('summary',t('evaluation.transmission','전송 자료')),el('pre',row.report.context.input,'app-preview-value'));block.append(input);
        const reuse=button('reuse',()=>{criteria.value=row.report.context.additionalCriteria;invalidate();setMessage(t('evaluation.reuseNotice','추가 기준을 복사했습니다. 같은 기준 식별자인지 확인한 뒤 비교하세요.'));});
        block.append(reuse);history.append(block);
      }
    }catch(ex){if(selected?.parent.id===parentId)setMessage(ex.message,true);}
  }
  const prepare=button('prepare',async()=>{
    if(busy||!selected)return;invalidate();const id=identity(),seq=generation;busy=true;lock();setMessage('');
    try{
      const value=await post('preview',{parentId:selected.parent.id,attemptId:attempt.value,criteria:criteria.value});
      if(seq!==generation||id!==identity())return;prepared=value;
      previewText.textContent=`${value.preview.profile.selection.owner}.${value.preview.profile.selection.name} · ${value.preview.profile.provider} / ${value.preview.profile.model}\nmax_tokens: ${value.sessionMaxTokens??t('assistant.tokens.inherit','프로필 값 사용')}\n${t('evaluation.basis','평가 기준 SHA-256')}: ${value.context.criteriaHash}\n\n${value.preview.source}`;transmission.open=true;
    }catch(ex){if(seq===generation)setMessage(ex.message,true);}finally{busy=false;lock();}
  });
  const run=button('run',async()=>{
    if(busy||!prepared||!consent.checked)return;const token=prepared.preview.token,seq=generation;prepared=null;busy=true;lock();setMessage(t('evaluation.running','AI 검토 중… 자동 재시도하지 않습니다.'));
    try{
      const value=await post('review',{token,consent:true});if(seq!==generation)return;
      result=value;report.textContent=value.text;decision.hidden=false;setMessage(t('evaluation.reviewed','AI 의견을 확인하고 사용자 판정을 저장하세요. 아직 DB에 저장하지 않았습니다.'));
    }catch(ex){if(seq===generation)setMessage(ex.message,true);}finally{busy=false;consent.checked=false;lock();}
  });
  const save=button('save',async()=>{
    if(busy||!result||!confirmed.checked)return;busy=true;lock();const seq=generation;
    try{
      await post('save',{reportId:result.id,verdict:verdict.value,reason:reason.value,confirmed:true});
      if(seq!==generation)return;result=null;decision.hidden=true;setMessage(t('evaluation.saved','AI 의견과 사용자 판정을 저장했습니다.'));await loadHistory();
    }catch(ex){if(seq===generation)setMessage(ex.message,true);}finally{busy=false;lock();}
  });
  decision.append(label('verdict',verdict),label('human',reason),confirmedLabel,save);
  const actions=el('div',null,'workbench-actions');actions.append(prepare,run);
  const settings=el('a',t('aitest.assistantSettings','AI 도우미 설정'));settings.href='/ai-assistant';
  host.append(heading,el('p',t('evaluation.help','저장된 자료를 AI가 검토하고 사용자가 최종 판정합니다. SQL 실행·정답 보증·업무 정의 자동 변경은 하지 않습니다.')),
    settings,form,basis,actions,transmission,consentLabel,message,el('h3',t('evaluation.ai','AI 검토 의견 (참고)')),report,decision,el('h3',t('evaluation.history','평가 이력')),history);
  attempt.addEventListener('change',()=>{invalidate();showEvidence();});criteria.addEventListener('input',invalidate);consent.addEventListener('change',lock);
  for(const n of [verdict,reason,confirmed])n.addEventListener('input',lock);
  root.addEventListener('problem-detail',event=>{selected=event.detail;invalidate();host.hidden=!selected;if(!selected)return;
    criteria.value='';attempt.replaceChildren();for(const a of selected.attempts)attempt.append(new Option(`${a.capturedAt} · ${a.profile||a.snapshotKind} · ${a.id}`,a.id));
    setMessage('');history.replaceChildren();showEvidence();lock();loadHistory();
  });
  lock();
}
