import {t} from './i18n.mjs';
import {assistantApi,assistantPost,profileLabel} from './ai-assistant.mjs';
import {mountSourceViewer} from './source-viewer.mjs';
import {mountEvidence,renderEvidence} from './select-ai-evidence.mjs';
import {mountInspection,renderPromptInspection,renderSqlReferences} from './select-ai-inspection.mjs';

export const validQuestion=value=>typeof value==='string'&&value.trim().length>0&&value.length<=16000&&!value.includes('\0');
export const canPrepare=(selected,question,busy)=>Boolean(selected)&&validQuestion(question)&&!busy;
export const canSend=(prepared,consent,busy)=>Boolean(prepared?.preview?.token)&&consent===true&&!busy;
export const modeLabel=action=>action==='SQL'?t('aitest.sql','SQL 만들기'):action==='PROMPT'?t('aitest.showprompt','프롬프트 보기'):t('aitest.chat','대화');
export const canReview=(snapshot,selected,question,busy)=>Boolean(snapshot&&!snapshot.error&&snapshot.profile.selection.name===selected&&snapshot.question===question&&!busy);
export const isSqlResponse=value=>typeof value==='string'&&/^(SELECT|WITH)\b/i.test(value.trim().replace(/^```sql\n([\s\S]*)```$/,'$1').trim());

if(typeof document!=='undefined')document.querySelectorAll('[data-ai-test]').forEach(root=>{
  const get=name=>root.querySelector(`[data-test-${name}]`),dialog=get('dialog');
  let busy=false,remoteRunning=false,selected='',prepared=null,detailsLoaded=false,latest=null,execution=null,snapshot=null,reviewPrepared=null;
  const post=(path,data)=>assistantApi('/ai-test/'+path,assistantPost(get('csrf'),data));
  const message=(value,error=false)=>{get('message').textContent=value;get('message').className=error?'app-alert is-error':'app-filter-message';};
  const evidence=mountEvidence(root,{post,isBusy:()=>busy||remoteRunning,setBusy:value=>{busy=value;controls();},changed:()=>{prepared=null;controls();},message,question:()=>get('question').value});
  const inspection=mountInspection(root,{post,isBusy:()=>busy||remoteRunning,setBusy:value=>{busy=value;controls();},selected:()=>selected,question:()=>get('question').value,prompt:()=>snapshot,promptCompatible:()=>evidence.ready()&&evidence.matches(snapshot?.evidence),generated:()=>latest,generatedCompatible:()=>evidence.ready()&&evidence.matches(latest?.evidence),message});
  function controls(){
    const pending=busy||remoteRunning;
    get('profile').disabled=pending;get('question').disabled=pending;get('refresh').disabled=busy;
    evidence.controls(pending);
    inspection.controls();
    for(const name of ['sql','chat','showprompt'])get(name).disabled=!canPrepare(selected,get('question').value,pending)||!evidence.ready();
    get('run').disabled=!canSend(prepared,get('consent').checked,pending);get('close').disabled=busy;
    get('execute').disabled=pending||!latest||latest.profile.selection.name!==selected||latest.question!==get('question').value||!evidence.matches(latest.evidence);
    get('execution-run').disabled=pending||!execution||!get('execution-consent').checked;
    get('execution-close').disabled=busy;
    get('review').disabled=!canReview(snapshot,selected,get('question').value,pending)||!evidence.matches(snapshot?.evidence);
    get('review-run').disabled=!canSend(reviewPrepared,get('review-consent').checked,pending);get('review-close').disabled=busy;
    get('review-condition').textContent=snapshot&&(snapshot.profile.selection.name!==selected||snapshot.question!==get('question').value||!evidence.matches(snapshot.evidence))?t('aitest.evPromptChanged','질문·프로필·근거가 달라졌습니다. 프롬프트를 다시 조회해 주세요.'):'';
  }
  function render(outcome){
    latest=outcome;get('execute').hidden=!outcome||outcome.action!=='SQL'||Boolean(outcome.error)||!isSqlResponse(outcome.text);
    get('sql-references').hidden=outcome?.action!=='SQL';renderSqlReferences(get('sql-references'),outcome);
    get('rows').replaceChildren();get('execute-message').textContent='';
    get('result').hidden=!outcome;if(!outcome)return;
    const profile=outcome.profile;
    get('result-meta').textContent=`${profile.selection.owner}.${profile.selection.name} · ${profile.provider} / ${profile.model||'—'} · ${modeLabel(outcome.action)} · ${new Date(outcome.requestedAt).toLocaleString()} · ${(outcome.elapsedMillis/1000).toFixed(2)}s`;
    get('request-id').textContent=outcome.id;get('request-question').textContent=outcome.question;
    renderEvidence(get('request-evidence'),outcome.evidence);
    const host=get('result-content');host.replaceChildren();
    if(outcome.error){const error=document.createElement('p');error.className='app-alert is-error';error.textContent=[outcome.error,outcome.code,outcome.phase].filter(Boolean).join(' · ');host.append(error);}
    if(outcome.text){
      if(outcome.action==='SQL'&&!outcome.error&&isSqlResponse(outcome.text))mountSourceViewer(host,[{type:'SQL',text:outcome.text}],null);
      else {const text=document.createElement('div');text.className='app-assistant-result';text.textContent=outcome.text;host.append(text);}
    }
  }
  function renderPrompt(value){
    renderPromptInspection(get('prompt-inspection'),value);
    snapshot=value;get('prompt-result').hidden=!value;const host=get('prompt-content');host.replaceChildren();if(!value)return;
    const p=value.profile;get('prompt-meta').textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model||'—'} · ${new Date(value.requestedAt).toLocaleString()} · ${(value.elapsedMillis/1000).toFixed(2)}s`;
    get('prompt-question').textContent=value.question;
    renderEvidence(get('prompt-evidence'),value.evidence);
    if(value.error){const error=document.createElement('p');error.className='app-alert is-error';error.textContent=[value.error,value.code].filter(Boolean).join(' · ');host.append(error);}
    if(value.text){const detail=document.createElement('details'),summary=document.createElement('summary'),text=document.createElement('pre');summary.textContent=t('aitest.promptSource','프롬프트 원문');text.className='app-preview-value app-test-prompt';text.textContent=value.text;detail.append(summary,text);host.append(detail);}
  }
  function renderReview(value){
    get('review-result').hidden=!value;if(!value)return;const p=value.reviewer;
    get('review-meta').textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model||'—'} · ${new Date(value.requestedAt).toLocaleString()} · ${(value.elapsedMillis/1000).toFixed(2)}s`;
    get('review-content').textContent=value.error?[value.error,value.code].filter(Boolean).join(' · '):value.text;
  }
  async function load(refresh=false){
    if(busy)return;busy=true;controls();message(t('ui.8bf609c884ca','불러오는 중…'));
    try{
      const data=await assistantApi('/ai-test/options?'+new URLSearchParams({refresh}));
      selected=data.selected?.name??'';remoteRunning=data.running;
      const select=get('profile');select.replaceChildren(new Option(t('assistant.select','프로필 선택'),''));
      for(const item of data.profiles)select.append(new Option(profileLabel(item),item.name));
      if(selected&&!data.profiles.some(p=>p.name===selected))select.append(new Option(selected,selected));
      select.value=selected;render(data.latest);renderRows(data.execution);renderPrompt(data.prompt);renderReview(data.review);
      if(!get('question').value)get('question').value=[data.prompt,data.latest].filter(Boolean).sort((a,b)=>new Date(b.requestedAt)-new Date(a.requestedAt))[0]?.question||'';
      const evidenceError=await evidence.restore(data,refresh);
      inspection.restore(data.inspection);
      if(refresh){detailsLoaded=false;get('attributes').replaceChildren();get('details').open=false;}
      message(evidenceError||(remoteRunning?t('aitest.busy','요청 처리 중입니다. 완료 후 다시 시도해 주세요.'):data.profiles.length?'':t('assistant.noProfiles','사용 가능한 활성 프로필이 없습니다.')),Boolean(evidenceError));
    }catch(ex){selected='';get('profile').replaceChildren();message(ex.message,true);}finally{busy=false;controls();}
  }
  async function close(){
    if(busy)return;const token=prepared?.preview.token;prepared=null;dialog.close();controls();
    if(token){busy=true;controls();try{await post('cancel',{token});}catch(ex){message(ex.message,true);}finally{busy=false;controls();}}
  }
  async function preview(action){
    if(!canPrepare(selected,get('question').value,busy||remoteRunning)||!evidence.ready())return;
    busy=true;controls();message('');
    try{
      prepared=await post('preview',{action,question:get('question').value,...evidence.request()});
      const p=prepared.preview.profile;
      get('preview-meta').textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model||'—'} · ${modeLabel(action)}`;
      get('transmission').textContent=action==='PROMPT'?t('aitest.promptTransmission','현재 조건으로 SHOWPROMPT를 구성합니다. SQL은 실행하지 않습니다. Feedback·RAG 준비 과정에서 외부 호출과 비용이 발생할 수 있습니다.'):
        t('aitest.transmission','아래 프롬프트와 프로필 설정에 따른 메타데이터·Feedback·RAG 문맥이 제공자에게 전달될 수 있습니다. 이번 호출: conversation=false.');
      get('prompt').textContent=prepared.preview.source;get('consent').checked=false;dialog.showModal();
    }catch(ex){prepared=null;message(ex.message,true);}finally{busy=false;controls();}
  }
  get('profile').addEventListener('change',async()=>{
    if(busy||remoteRunning)return;busy=true;controls();message('');
    const name=get('profile').value;
    try{await post('selection',{name});selected=name;evidence.invalidate();inspection.invalidate();detailsLoaded=false;get('attributes').replaceChildren();get('details').open=false;}
    catch(ex){get('profile').value=selected;message(ex.message,true);}finally{busy=false;controls();}
  });
  get('details').addEventListener('toggle',async()=>{
    if(!get('details').open||detailsLoaded||!selected||busy||remoteRunning)return;
    busy=true;controls();
    try{
      const detail=await assistantApi('/ai-test/profile'),host=get('attributes');host.replaceChildren();
      const description=document.createElement('p');description.textContent=detail.profile.description||'—';host.append(description);
      for(const attr of detail.attributes){const heading=document.createElement('h3'),value=document.createElement('pre');heading.className='app-attribute-title';heading.textContent=attr.name;value.className='app-preview-value';value.textContent=attr.value;host.append(heading,value);}
      detailsLoaded=true;
    }catch(ex){message(ex.message,true);}finally{busy=false;controls();}
  });
  get('question').addEventListener('input',()=>{prepared=null;evidence.invalidate();controls();});
  get('sql').addEventListener('click',()=>preview('SQL'));get('chat').addEventListener('click',()=>preview('CHAT'));
  get('showprompt').addEventListener('click',()=>preview('PROMPT'));
  get('refresh').addEventListener('click',()=>load(!remoteRunning));
  get('close').addEventListener('click',close);dialog.addEventListener('cancel',event=>{event.preventDefault();close();});
  get('consent').addEventListener('change',controls);
  function renderRows(result){
    const host=get('rows');host.replaceChildren();if(!result)return;
    get('execute-message').textContent=result.error?[result.error,result.code].filter(Boolean).join(' · '):t('aitest.rowCount','{0}행 · {1}초',result.data.rows.length,(result.elapsedMillis/1000).toFixed(2));
    if(result.error)return;
    const data=result.data;let page=0;
    const stamp=document.createElement('p');stamp.className='app-filter-message';stamp.textContent=`${data.actor} · ${new Date(data.executedAt).toLocaleString()}`;host.append(stamp);
    if(data.truncated){const note=document.createElement('p');note.textContent=t('aitest.moreRows','200행까지만 표시합니다. 추가 행이 있습니다.');host.append(note);}
    const wrapper=document.createElement('div');wrapper.className='table-responsive';const table=document.createElement('table');table.className='app-table';
    const head=document.createElement('thead'),header=document.createElement('tr'),body=document.createElement('tbody');
    for(const name of data.columns){const cell=document.createElement('th');cell.textContent=name;header.append(cell);}head.append(header);table.append(head,body);wrapper.append(table);host.append(wrapper);
    const paging=document.createElement('div');paging.className='app-assistant-actions';const previous=document.createElement('button'),next=document.createElement('button'),count=document.createElement('span');
    for(const button of [previous,next]){button.type='button';button.className='btn app-btn app-btn-quiet';}
    previous.textContent=t('ui.da7e61c67cc5','이전');next.textContent=t('ui.aef613c6612d','다음');paging.append(previous,count,next);host.append(paging);
    const paint=()=>{body.replaceChildren();for(const row of data.rows.slice(page*10,page*10+10)){const tr=document.createElement('tr');for(const cell of row){const td=document.createElement('td');td.textContent=cell.value===null?'NULL':cell.value;if(cell.truncated){td.append(document.createTextNode('…'));td.title=t('aitest.cellClipped','2,000자 표시 한도');}tr.append(td);}body.append(tr);}count.textContent=`${page+1} / ${Math.max(1,Math.ceil(data.rows.length/10))}`;previous.disabled=page===0;next.disabled=(page+1)*10>=data.rows.length;};
    previous.addEventListener('click',()=>{page--;paint();});next.addEventListener('click',()=>{page++;paint();});paint();
  }
  const executionDialog=get('execution-dialog');
  async function closeExecution(){if(busy)return;const token=execution?.token;execution=null;executionDialog.close();controls();if(token){try{await post('cancel',{token});}catch(ex){message(ex.message,true);}}}
  get('execution-close').addEventListener('click',closeExecution);executionDialog.addEventListener('cancel',event=>{event.preventDefault();closeExecution();});
  get('execution-consent').addEventListener('change',controls);
  get('execute').addEventListener('click',async()=>{
    if(busy||remoteRunning||!latest)return;busy=true;controls();message('');
    try{execution=await post('execute/preview',{resultId:latest.id});get('execution-sql').textContent=execution.sql;get('execution-consent').checked=false;executionDialog.showModal();}
    catch(ex){execution=null;message(ex.message,true);}finally{busy=false;controls();}
  });
  get('execution-run').addEventListener('click',async()=>{
    if(busy||remoteRunning||!execution||!get('execution-consent').checked)return;
    const token=execution.token;execution=null;busy=true;controls();executionDialog.close();get('rows').replaceChildren();message(t('aitest.executing','조회 중…'));
    try{renderRows(await post('execute',{token,consent:true}));message('');}
    catch(ex){message(`${ex.message} ${t('aitest.uncertain','결과가 불확실합니다. 새로고침으로 상태를 확인하세요. 자동 재시도하지 않았습니다.')}`,true);remoteRunning=true;}
    finally{busy=false;controls();}
  });
  get('run').addEventListener('click',async()=>{
    if(!canSend(prepared,get('consent').checked,busy||remoteRunning))return;
    const token=prepared.preview.token;prepared=null;busy=true;controls();dialog.close();
    message(t('aitest.generating','요청 처리 중…'));
    try{const result=await post('generate',{token,consent:true});if(result.action==='PROMPT')renderPrompt(result);else render(result);inspection.refreshPrompt();renderReview(null);message(result.error||'',Boolean(result.error));}
    catch(ex){message(`${ex.message} ${t('aitest.uncertain','결과가 불확실합니다. 새로고침으로 상태를 확인하세요. 자동 재시도하지 않았습니다.')}`,true);remoteRunning=true;}
    finally{busy=false;controls();}
  });
  const reviewDialog=get('review-dialog');
  async function closeReview(){if(busy)return;const token=reviewPrepared?.preview.token;reviewPrepared=null;reviewDialog.close();controls();if(token){try{await post('cancel',{token});}catch(ex){message(ex.message,true);}}}
  get('review-close').addEventListener('click',closeReview);reviewDialog.addEventListener('cancel',event=>{event.preventDefault();closeReview();});
  get('review-consent').addEventListener('change',controls);
  get('review').addEventListener('click',async()=>{
    if(!canReview(snapshot,selected,get('question').value,busy||remoteRunning)||!evidence.matches(snapshot.evidence))return;
    busy=true;controls();message('');
    try{
      reviewPrepared=await post('review/preview',{promptId:snapshot.id});const p=reviewPrepared.preview.profile;
      get('review-preview-meta').textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model||'—'} · ${reviewPrepared.preview.characters.toLocaleString()} ${t('aitest.characters','자')}`;
      get('review-match').textContent=reviewPrepared.generated?t('aitest.reviewWithSql','같은 질문·프로필 설정의 생성 응답을 함께 검토합니다.'):t('aitest.reviewPromptOnly','일치하는 생성 응답이 없어 프롬프트의 근거만 검토합니다.');
      get('review-source').textContent=reviewPrepared.preview.source;get('review-consent').checked=false;reviewDialog.showModal();
    }catch(ex){reviewPrepared=null;message(ex.message,true);}finally{busy=false;controls();}
  });
  get('review-run').addEventListener('click',async()=>{
    if(!canSend(reviewPrepared,get('review-consent').checked,busy||remoteRunning))return;
    const token=reviewPrepared.preview.token;reviewPrepared=null;busy=true;controls();reviewDialog.close();message(t('aitest.generating','요청 처리 중…'));
    try{const result=await post('review',{token,consent:true});renderReview(result);message(result.error||'',Boolean(result.error));}
    catch(ex){message(`${ex.message} ${t('aitest.uncertain','결과가 불확실합니다. 새로고침으로 상태를 확인하세요. 자동 재시도하지 않았습니다.')}`,true);remoteRunning=true;}
    finally{busy=false;controls();}
  });
  load();
});
