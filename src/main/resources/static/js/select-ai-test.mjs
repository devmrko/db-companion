import {t} from './i18n.mjs';
import {assistantApi,assistantPost,profileLabel} from './ai-assistant.mjs';
import {mountSourceViewer} from './source-viewer.mjs';
import {renderComparisonRows} from './select-ai-comparison-view.mjs';
import {mountEvidence,renderEvidence} from './select-ai-evidence.mjs';
import {mountInspection,renderPromptInspection,renderSqlReferences} from './select-ai-inspection.mjs';
import {mountBusinessGlossary,renderGlossarySnapshot} from './business-glossary.mjs';
import {mountProgress} from './select-ai-progress.mjs';
import {mountResultReview} from './select-ai-result-review.mjs';

export const validQuestion=value=>typeof value==='string'&&value.trim().length>0&&value.length<=16000&&!value.includes('\0');
export const canPrepare=(selected,question,busy)=>Boolean(selected)&&validQuestion(question)&&!busy;
export const preparationBlockers=(selected,question,busy,glossaryReady,ontologyReady)=>{
  if(busy)return [t('aitest.readyBusy','요청 처리 중입니다.')];
  const reasons=[];
  if(!selected)reasons.push(t('aitest.readyProfile','상단의 테스트 프로필을 선택해 주세요. A/B 프로필 선택과는 별개입니다.'));
  if(!validQuestion(question))reasons.push(t('aitest.readyQuestion','질문을 입력해 주세요 (최대 16,000자).'));
  if(!glossaryReady)reasons.push(t('aitest.readyGlossary','업무 용어 사전: 현재 질문·프로필로 검색해 주세요. 검색된 정의는 자동 첨부됩니다. 한도 초과 시 질문 범위를 좁혀 주세요.'));
  if(!ontologyReady)reasons.push(t('aitest.readyOntology','온톨로지: RDF 근거를 찾아 선택·적용해 주세요. 사용하지 않으면 체크를 해제하세요.'));
  return reasons;
};
export const canSend=(prepared,consent,busy)=>Boolean(prepared?.preview?.token)&&consent===true&&!busy;
export const modeLabel=action=>action==='SQL'?t('aitest.sql','SQL 만들기'):action==='PROMPT'?t('aitest.showprompt','프롬프트 보기'):t('aitest.chat','대화');
export const canReview=(snapshot,selected,question,busy)=>Boolean(snapshot&&!snapshot.error&&snapshot.profile.selection.name===selected&&snapshot.question===question&&!busy);
export const isSqlResponse=value=>typeof value==='string'&&/^(SELECT|WITH)\b/i.test(value.trim().replace(/^```sql\n([\s\S]*)```$/,'$1').trim());
/** Drops UI-only fields; every endpoint receives only its strict DTO contract. */
export const selectedSavePayload=(save,parent,fields)=>{
  const common=save.kind==='comparison'?{generation:save.generation,side:save.side,resultId:save.resultId,saveToken:save.saveToken}:{resultId:save.resultId,saveToken:save.saveToken};
  return parent?{...common,parentId:parent.id,parentUpdatedAt:parent.updatedAt,includeSnapshots:fields.includeSnapshots}:{...common,description:fields.description,expected:fields.expected,expectedSql:fields.expectedSql,status:fields.status,includeSnapshots:fields.includeSnapshots};
};

if(typeof document!=='undefined')document.querySelectorAll('[data-ai-test]').forEach(root=>{
  const get=name=>root.querySelector(`[data-test-${name}]`),dialog=get('dialog');
  let busy=false,remoteRunning=false,selected='',prepared=null,detailsLoaded=false,latest=null,execution=null,snapshot=null,reviewPrepared=null;
  let comparison=null,comparisonAi=null,comparisonShowPrompt=null;
  const progress=mountProgress(get('progress'),{fetchStatus:()=>assistantApi('/ai-test/progress',{signal:AbortSignal.timeout(5000)})});
  const post=async(path,data)=>{
    const options=assistantPost(get('csrf'),data);
    if(!['generate','execute'].includes(path))return assistantApi('/ai-test/'+path,options);
    const id=crypto.randomUUID();options.headers['X-AI-Progress-Id']=id;progress.begin(id);
    try{return await assistantApi('/ai-test/'+path,options);}finally{await progress.settle(id);}
  };
  const message=(value,error=false)=>{get('message').textContent=value;get('message').className=error?'app-alert is-error':'app-filter-message';};
  const resultReview=mountResultReview(root,{post,latest:()=>latest,selected:()=>selected,isBusy:()=>busy||remoteRunning,
    setBusy:value=>{busy=value;controls();},uncertain:()=>{remoteRunning=true;},message});
  const evidence=mountEvidence(root,{post,isBusy:()=>busy||remoteRunning,setBusy:value=>{busy=value;controls();},changed:()=>{prepared=null;controls();},message,question:()=>get('question').value});
  const glossary=mountBusinessGlossary(root,{csrf:get('csrf'),question:()=>get('question').value,profile:()=>selected,busy:()=>busy||remoteRunning,setBusy:value=>{busy=value;controls();},changed:()=>{prepared=null;controls();},message});
  const inspection=mountInspection(root,{post,isBusy:()=>busy||remoteRunning,setBusy:value=>{busy=value;controls();},selected:()=>selected,question:()=>get('question').value,prompt:()=>snapshot,promptCompatible:()=>evidence.ready()&&evidence.matches(snapshot?.evidence)&&glossary.matches(snapshot?.glossary),generated:()=>latest,generatedCompatible:()=>evidence.ready()&&evidence.matches(latest?.evidence)&&glossary.matches(latest?.glossary),message});
  function controls(){
    const pending=busy||remoteRunning;
    progress.setBusy(pending);
    resultReview.controls();
    get('profile').disabled=pending;get('question').disabled=pending;get('refresh').disabled=busy;
    evidence.controls(pending);
    glossary.controls();
    inspection.controls();
    const readiness=get('readiness');
    readiness.textContent=preparationBlockers(selected,get('question').value,pending,glossary.ready(),evidence.ready()).join('\n');
    readiness.hidden=!readiness.textContent;
    for(const name of ['sql','chat','showprompt'])get(name).disabled=!canPrepare(selected,get('question').value,pending)||!evidence.ready()||!glossary.ready();
    get('condition-open').disabled=!canPrepare(selected,get('question').value,pending)||!evidence.ready()||!glossary.ready();
    for(const node of root.querySelectorAll('[data-test-condition-question],[data-test-condition-answer],[data-test-condition-free],[data-test-condition-choice],[data-test-condition-value]'))node.disabled=pending||(node.dataset.testConditionValue!==undefined&&!root.querySelector(`[data-test-condition-choice][value="${node.dataset.testConditionValue}"]`)?.checked);
    get('condition-review').disabled=pending;get('condition-close').disabled=pending;
    get('run').disabled=!canSend(prepared,get('consent').checked,pending);get('close').disabled=busy;
    get('execute').disabled=pending||!latest||latest.profile.selection.name!==selected||latest.question!==get('question').value||!evidence.matches(latest.evidence)||!glossary.matches(latest.glossary);
    get('execution-run').disabled=pending||!execution||!get('execution-consent').checked;
    get('execution-close').disabled=busy;
    get('review').disabled=!canReview(snapshot,selected,get('question').value,pending)||!evidence.matches(snapshot?.evidence)||!glossary.matches(snapshot?.glossary);
    get('review-run').disabled=!canSend(reviewPrepared,get('review-consent').checked,pending);get('review-close').disabled=busy;
    get('review-condition').textContent=snapshot&&(snapshot.profile.selection.name!==selected||snapshot.question!==get('question').value||!evidence.matches(snapshot.evidence))?t('aitest.evPromptChanged','질문·프로필·근거가 달라졌습니다. 프롬프트를 다시 조회해 주세요.'):'';
    get('save-problem').disabled=pending||!latest;
    if(get('comparison-left')){get('comparison-preview').disabled=pending||!selected||!validQuestion(get('question').value);get('comparison-left-inspection').disabled=pending||!comparison;get('comparison-right-inspection').disabled=pending||!comparison;get('comparison-left-run').disabled=pending||!comparison||!get('comparison-consent').checked;get('comparison-right-run').disabled=pending||!comparison||!get('comparison-consent').checked;get('comparison-showprompt-preview').disabled=pending||!comparison;get('comparison-showprompt-left-run').disabled=pending||!comparisonShowPrompt||!get('comparison-showprompt-consent').checked;get('comparison-showprompt-right-run').disabled=pending||!comparisonShowPrompt||!get('comparison-showprompt-consent').checked;get('comparison-ai-run').disabled=pending||!comparisonAi||!get('comparison-ai-consent').checked;}
  }
  const comparisonDialog=get('comparison-dialog');
  const comparisonAiDialog=get('comparison-ai-dialog');
  const comparisonShowPromptDialog=get('comparison-showprompt-dialog');
  get('comparison-preview').addEventListener('click',async()=>{if(glossary.enabled()){message(t('businessGlossary.comparisonUnsupported','업무 용어 참고는 단일 테스트에서 사용해 주세요. A/B 비교는 아직 지원하지 않습니다.'),true);return;}const context=evidence.request();if(context.useOntology){message(t('aitest.comparison.evidenceUnsupported','온톨로지 근거 비교는 A/B에서 아직 지원하지 않습니다. 근거 사용을 해제하거나 단일 테스트를 사용해 주세요.'),true);return;}busy=true;controls();try{comparison=await post('comparison/preview',{left:get('comparison-left').value,right:get('comparison-right').value,question:get('question').value});get('comparison-plan').textContent=t('aitest.comparison.plan','기본 SQL 생성 {0}회 · SHOWPROMPT {1}회 · AI 비교 {2}회',comparison.baseCalls,comparison.showPromptCalls,comparison.aiComparisonCalls);get('comparison-source').textContent=`A: ${comparison.left.source}\n\nB: ${comparison.right.source}`;get('comparison-consent').checked=false;comparisonDialog.showModal();}catch(ex){message(ex.message,true);}finally{busy=false;controls();}});
  get('comparison-consent').addEventListener('change',controls);get('comparison-close').addEventListener('click',()=>comparisonDialog.close());
  function inspectionControls(host,side,snapshot){if(!snapshot)return;const named=(snapshot.objects||[]).filter(item=>item.name);if(named.length){const select=document.createElement('select'),button=document.createElement('button');for(const item of named)select.append(new Option(`${item.owner}.${item.name}`,JSON.stringify([item.owner,item.name])));button.type='button';button.className='btn app-btn app-btn-quiet';button.textContent='선택 객체 읽기';button.addEventListener('click',async()=>{const [owner,name]=JSON.parse(select.value);busy=true;controls();try{await post('comparison/inspection/table',{side,id:snapshot.id,owner,name});await renderComparison();}catch(ex){message(ex.message,true);}finally{busy=false;controls();}});host.append(select,button);}const items=snapshot.feedback?.rows?.items||[];if(items.length){const select=document.createElement('select'),button=document.createElement('button');for(const item of items)select.append(new Option(item.id,item.id));button.type='button';button.className='btn app-btn app-btn-quiet';button.textContent='선택 Feedback 상세 읽기';button.addEventListener('click',async()=>{busy=true;controls();try{await post('comparison/inspection/feedback/detail',{side,id:snapshot.id,rowId:select.value});await renderComparison();}catch(ex){message(ex.message,true);}finally{busy=false;controls();}});host.append(select,button);}}
  let observedComparison=null;
  const differencesLabel=document.createElement('label'),differencesToggle=document.createElement('input');
  differencesToggle.type='checkbox';differencesToggle.dataset.testComparisonDifferences='';differencesLabel.append(differencesToggle,document.createTextNode(` ${t('aitest.comparison.differencesOnly','차이·미확인 항목만 보기')}`));get('comparison-result').before(differencesLabel);
  function drawComparison(){if(!observedComparison)return;const host=get('comparison-result');renderComparisonRows(host,observedComparison,differencesToggle.checked);for(const [side,label] of [['left','A'],['right','B']]){const section=document.createElement('section'),heading=document.createElement('h3');heading.textContent=t('aitest.comparison.inspection','{0} 참고정보 추가 조회',label);section.append(heading);inspectionControls(section,side,observedComparison[`${side}Inspection`]);const outcome=observedComparison[side];if(comparison?.generation&&outcome?.id){const save=document.createElement('button');save.type='button';save.className='btn app-btn app-btn-quiet';save.textContent=t('aitest.comparison.saveResult','{0} 결과를 문제 질문으로 저장',label);save.addEventListener('click',()=>openComparisonProblemSave(side,outcome));section.append(save);}host.append(section);}}
  differencesToggle.addEventListener('change',drawComparison);
  async function renderComparison(){observedComparison=await assistantApi('/ai-test/comparison/result');drawComparison();}
  async function comparisonRun(side){if(!comparison||!get('comparison-consent').checked)return;busy=true;controls();try{const output=await post('comparison/generate',{token:comparison[side].token,consent:true}),host=get('comparison-result'),line=document.createElement('pre');line.className='app-preview-value';line.textContent=`${side.toUpperCase()} · ${output.error||output.text||''}`;host.append(line);await renderComparison();}catch(ex){message(ex.message,true);}finally{busy=false;controls();}}
  async function comparisonInspection(side){if(!comparison)return;busy=true;controls();try{await post('comparison/inspection',{side});await renderComparison();}catch(ex){message(ex.message,true);}finally{busy=false;controls();}}
  get('comparison-left-run').addEventListener('click',()=>comparisonRun('left'));get('comparison-right-run').addEventListener('click',()=>comparisonRun('right'));
  get('comparison-left-inspection').addEventListener('click',()=>comparisonInspection('left'));get('comparison-right-inspection').addEventListener('click',()=>comparisonInspection('right'));
  get('comparison-showprompt-preview').addEventListener('click',async()=>{if(!comparison)return;busy=true;controls();try{comparisonShowPrompt=await post('comparison/showprompt/preview',{});get('comparison-showprompt-source').textContent=`A: ${comparisonShowPrompt.left.source}\n\nB: ${comparisonShowPrompt.right.source}`;get('comparison-showprompt-consent').checked=false;comparisonShowPromptDialog.showModal();}catch(ex){message(ex.message,true);}finally{busy=false;controls();}});get('comparison-showprompt-close').addEventListener('click',()=>comparisonShowPromptDialog.close());get('comparison-showprompt-consent').addEventListener('change',controls);async function showPromptRun(side){if(!comparisonShowPrompt||!get('comparison-showprompt-consent').checked)return;busy=true;controls();try{await post('comparison/showprompt',{token:comparisonShowPrompt[side].token,consent:true});await renderComparison();}catch(ex){message(ex.message,true);}finally{busy=false;controls();}}get('comparison-showprompt-left-run').addEventListener('click',()=>showPromptRun('left'));get('comparison-showprompt-right-run').addEventListener('click',()=>showPromptRun('right'));
  get('comparison-ai-preview').addEventListener('click',async()=>{busy=true;controls();try{const fields=[...root.querySelectorAll('[data-comparison-ai-field]:checked')].map(input=>input.value);comparisonAi=await post('comparison/ai-preview',{fields});get('comparison-ai-source').textContent=comparisonAi.source;get('comparison-ai-consent').checked=false;comparisonAiDialog.showModal();}catch(ex){message(ex.message,true);}finally{busy=false;controls();}});get('comparison-ai-consent').addEventListener('change',controls);get('comparison-ai-close').addEventListener('click',()=>comparisonAiDialog.close());get('comparison-ai-run').addEventListener('click',async()=>{if(!comparisonAi||!get('comparison-ai-consent').checked)return;busy=true;controls();try{const result=await post('comparison/ai',{token:comparisonAi.token,consent:true}),line=document.createElement('pre');line.className='app-preview-value';line.textContent=result.text;get('comparison-result').append(line);comparisonAiDialog.close();}catch(ex){message(ex.message,true);}finally{busy=false;controls();}});
  const problemDialog=get('problem-dialog');let problemSave=null;
  async function openProblemDialog(save){get('problem-description').value='';get('problem-expected').value='';get('problem-sql').value='';get('problem-snapshots').checked=false;get('problem-parent-fields').hidden=false;const parent=get('problem-parent');parent.replaceChildren(new Option(t('problemQuestion.new','새 문제 질문'),''));parent.parentElement.querySelector('.app-problem-parent-more')?.remove();let cursor='';const more=document.createElement('button');more.type='button';more.className='btn app-btn app-btn-quiet app-problem-parent-more';more.textContent=t('problemQuestion.more','이전 문제 질문 더 보기');const loadParents=async()=>{const page=await assistantApi('/ai-test/problems/list?'+new URLSearchParams({before:cursor}));for(const item of page.rows){const option=new Option(`${item.status} · ${item.question}`,item.id);option.dataset.updatedAt=item.updatedAt;parent.append(option);}cursor=page.next||'';more.hidden=!cursor;};more.addEventListener('click',async()=>{more.disabled=true;try{await loadParents();}catch(ex){message(ex.message,true);}finally{more.disabled=false;}});parent.parentElement.append(more);try{await loadParents();problemSave=save;problemDialog.showModal();}catch(ex){more.remove();message(ex.message,true);}}
  get('save-problem').addEventListener('click',async()=>{if(!latest)return;busy=true;controls();try{const save=await post('problem/save-preview',{resultId:latest.id});await openProblemDialog({kind:'single',resultId:latest.id,saveToken:save.token});}catch(ex){message(ex.message,true);}finally{busy=false;controls();}});
  async function openComparisonProblemSave(side,outcome){if(!comparison?.generation)return;busy=true;controls();try{const save=await post('comparison/problem/save-preview',{generation:comparison.generation,side,resultId:outcome.id});await openProblemDialog({kind:'comparison',generation:comparison.generation,side,resultId:outcome.id,saveToken:save.token});}catch(ex){message(ex.message,true);}finally{busy=false;controls();}}
  get('problem-parent').addEventListener('change',()=>{get('problem-parent-fields').hidden=Boolean(get('problem-parent').value);});
  get('problem-close').addEventListener('click',()=>problemDialog.close());
  get('problem-run').addEventListener('click',async()=>{if(!problemSave)return;const parentOption=get('problem-parent').selectedOptions[0],parent=parentOption?.value?{id:parentOption.value,updatedAt:parentOption.dataset.updatedAt}:null;if(!parent&&(!get('problem-description').value.trim()||!get('problem-expected').value.trim()))return;busy=true;controls();try{const base=problemSave.kind==='comparison'?'comparison/problem':'problem',payload=selectedSavePayload(problemSave,parent,{description:get('problem-description').value,expected:get('problem-expected').value,expectedSql:get('problem-sql').value,status:'RECEIVED',includeSnapshots:get('problem-snapshots').checked});const result=await post(parent?base+'/attempt':base,payload);problemSave=null;problemDialog.close();message(t('problemQuestion.saved','문제 질문에 저장했습니다: {0}',result.id));}catch(ex){message(ex.message,true);}finally{busy=false;controls();}});
  function render(outcome){
    resultReview.clear();
    latest=outcome;get('execute').hidden=!outcome||outcome.action!=='SQL'||Boolean(outcome.error)||!isSqlResponse(outcome.text);
    get('sql-references').hidden=outcome?.action!=='SQL';renderSqlReferences(get('sql-references'),outcome);
    get('rows').replaceChildren();get('execute-message').textContent='';
    get('result').hidden=!outcome;if(!outcome)return;
    const profile=outcome.profile;
    get('result-meta').textContent=`${profile.selection.owner}.${profile.selection.name} · ${profile.provider} / ${profile.model||'—'} · ${modeLabel(outcome.action)} · ${new Date(outcome.requestedAt).toLocaleString()} · ${(outcome.elapsedMillis/1000).toFixed(2)}s`;
    get('request-id').textContent=outcome.id;get('request-question').textContent=outcome.question;
    renderEvidence(get('request-evidence'),outcome.evidence);
    const host=get('result-content');host.replaceChildren();
    if(outcome.confirmation){const details=document.createElement('details'),summary=document.createElement('summary'),text=document.createElement('pre');summary.textContent=t('aitest.condition.confirmed','사용자 확정 조건');text.className='app-preview-value';text.textContent=JSON.stringify(outcome.confirmation,null,2);details.append(summary,text);host.append(details);}
    renderGlossarySnapshot(host,outcome.glossary);
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
    renderGlossarySnapshot(host,value.glossary);
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
      progress.restore();
      const select=get('profile');select.replaceChildren(new Option(t('assistant.select','프로필 선택'),''));
      for(const item of data.profiles)select.append(new Option(profileLabel(item),item.name));
      if(get('comparison-left')){for(const name of ['comparison-left','comparison-right']){const target=get(name);target.replaceChildren();for(const item of data.profiles)target.append(new Option(profileLabel(item),item.name));}get('comparison-left').value=selected;get('comparison-right').value=data.profiles.find(p=>p.name!==selected)?.name||selected;}
      if(selected&&!data.profiles.some(p=>p.name===selected))select.append(new Option(selected,selected));
      select.value=selected;render(data.latest);renderRows(data.execution);renderPrompt(data.prompt);renderReview(data.review);resultReview.render(data.resultReview);
      if(!get('question').value)get('question').value=[data.prompt,data.latest].filter(Boolean).sort((a,b)=>new Date(b.requestedAt)-new Date(a.requestedAt))[0]?.question||'';
      const evidenceError=await evidence.restore(data,refresh);
      await glossary.load();
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
    if(!canPrepare(selected,get('question').value,busy||remoteRunning)||!evidence.ready()||!glossary.ready())return;
    busy=true;controls();message('');
    try{
      prepared=await post('preview',{action,question:get('question').value,...evidence.request(),...glossary.request()});
      const p=prepared.preview.profile;
      get('preview-meta').textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model||'—'} · ${modeLabel(action)}`;
      get('transmission').textContent=action==='PROMPT'?t('aitest.promptTransmission','현재 조건으로 SHOWPROMPT를 구성합니다. SQL은 실행하지 않습니다. Feedback·RAG 준비 과정에서 외부 호출과 비용이 발생할 수 있습니다.'):
        t('aitest.transmission','아래 프롬프트와 프로필 설정에 따른 메타데이터·Feedback·RAG 문맥이 제공자에게 전달될 수 있습니다. 이번 호출: conversation=false.');
      get('prompt').textContent=prepared.preview.source;get('consent').checked=false;dialog.showModal();
    }catch(ex){prepared=null;message(ex.message,true);}finally{busy=false;controls();}
  }
  const conditionDialog=get('condition-dialog');let conditionOriginal='';
  const conditionError=value=>{get('condition-error').textContent=value||'';get('condition-error').hidden=!value;};
  const closeCondition=()=>{if(!busy)conditionDialog.close();};
  const conditionChanged=()=>{conditionError('');controls();};
  get('condition-open').addEventListener('click',()=>{if(!canPrepare(selected,get('question').value,busy||remoteRunning)||!evidence.ready()||!glossary.ready())return;conditionOriginal=get('question').value;get('condition-original').textContent=conditionOriginal;get('condition-question').value='';get('condition-answer').value='';get('condition-free').value='';conditionError('');for(const item of root.querySelectorAll('[data-test-condition-choice]'))item.checked=false;for(const item of root.querySelectorAll('[data-test-condition-value]'))item.value='';conditionDialog.showModal();controls();});
  for(const item of root.querySelectorAll('[data-test-condition-choice],[data-test-condition-question],[data-test-condition-answer],[data-test-condition-free],[data-test-condition-value]'))item.addEventListener('input',conditionChanged);for(const item of root.querySelectorAll('[data-test-condition-choice]'))item.addEventListener('change',conditionChanged);
  get('condition-close').addEventListener('click',closeCondition);conditionDialog.addEventListener('cancel',event=>{event.preventDefault();closeCondition();});
  get('condition-review').addEventListener('click',async()=>{
    if(busy||remoteRunning)return;
    const labels={period:t('aitest.condition.period','기간 또는 기준 날짜'),aggregation:t('aitest.condition.aggregation','집계 단위 또는 기준'),scope:t('aitest.condition.scope','대상 범위 또는 제외 조건'),ordering:t('aitest.condition.ordering','업무상 비교 또는 정렬 기준')};
    const fixed=[...root.querySelectorAll('[data-test-condition-choice]:checked')].map(item=>({key:item.value,topic:labels[item.value]||item.value,value:root.querySelector(`[data-test-condition-value="${item.value}"]`)?.value.trim()||''})),free=get('condition-free').value.split(/\r?\n/).map(value=>value.trim()).filter(Boolean).map((value,index)=>({key:`free-${index+1}`,topic:t('aitest.condition.freeItem','자유 입력 조건 {0}',index+1),value})),conditions=[...fixed,...free];
    if(conditionOriginal!==get('question').value){conditionError(t('aitest.condition.changed','원 질문이 변경되었습니다. 조건 확인을 다시 열어 주세요.'));return;}
    if(conditions.length>4||fixed.some(item=>!item.value)){conditionError(t('aitest.condition.actualValue','선택한 각 항목의 실제 값을 입력하고 조건은 최대 4개로 제한해 주세요.'));return;}
    if((get('condition-question').value.trim()==='')!== (get('condition-answer').value.trim()==='')){conditionError(t('aitest.condition.pair','확인 질문과 사용자 답변은 함께 입력해 주세요.'));return;}
    if(!conditions.length&&!get('condition-question').value.trim()){conditionError(t('aitest.condition.required','실제 값이 있는 확정 조건 또는 확인 질문·답변을 입력해 주세요.'));return;}
    const questionAtRequest=get('question').value,profileAtRequest=selected,formFingerprint=JSON.stringify({question:get('condition-question').value,answer:get('condition-answer').value,conditions});
    busy=true;controls();message('');
    try{const value=await post('condition/preview',{action:'SQL',question:questionAtRequest,originalQuestion:conditionOriginal,confirmationQuestion:get('condition-question').value,confirmationAnswer:get('condition-answer').value,conditions,...evidence.request(),...glossary.request()});const currentFixed=[...root.querySelectorAll('[data-test-condition-choice]:checked')].map(item=>({key:item.value,topic:labels[item.value]||item.value,value:root.querySelector(`[data-test-condition-value="${item.value}"]`)?.value.trim()||''})),currentFree=get('condition-free').value.split(/\r?\n/).map(value=>value.trim()).filter(Boolean).map((value,index)=>({key:`free-${index+1}`,topic:t('aitest.condition.freeItem','자유 입력 조건 {0}',index+1),value})),currentFingerprint=JSON.stringify({question:get('condition-question').value,answer:get('condition-answer').value,conditions:[...currentFixed,...currentFree]});if(questionAtRequest!==get('question').value||profileAtRequest!==selected||conditionOriginal!==questionAtRequest||formFingerprint!==currentFingerprint){prepared=null;try{await post('cancel',{token:value.preview.token});}catch(ignore){}conditionError(t('aitest.condition.stale','질문·프로필 또는 조건이 변경되었습니다. 조건 확인을 다시 열어 주세요.'));return;}prepared=value;const p=prepared.preview.profile;get('preview-meta').textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model||'—'} · SQL · ${t('aitest.condition.confirmed','사용자 확정 조건')}`;get('transmission').textContent=t('aitest.condition.transmission','아래 원 질문과 사용자가 확정한 조건이 제공자에게 전달됩니다. 조건 확인 자체는 AI 호출이 아니며, 다음 동의 후 SQL 생성 1회만 요청합니다.');get('prompt').textContent=prepared.preview.source;get('consent').checked=false;conditionDialog.close();dialog.showModal();}
    catch(ex){prepared=null;conditionError(ex.message);}finally{busy=false;controls();}
  });
  get('profile').addEventListener('change',async()=>{
    if(busy||remoteRunning)return;busy=true;controls();message('');
    const name=get('profile').value;
    try{await post('selection',{name});selected=name;evidence.invalidate();glossary.invalidate();inspection.invalidate();detailsLoaded=false;get('attributes').replaceChildren();get('details').open=false;}
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
  get('question').addEventListener('input',()=>{prepared=null;evidence.invalidate();glossary.invalidate();controls();});
  get('sql').addEventListener('click',()=>preview('SQL'));get('chat').addEventListener('click',()=>preview('CHAT'));
  get('showprompt').addEventListener('click',()=>preview('PROMPT'));
  get('refresh').addEventListener('click',()=>load(!remoteRunning));
  get('close').addEventListener('click',close);dialog.addEventListener('cancel',event=>{event.preventDefault();close();});
  get('consent').addEventListener('change',controls);
  function renderRows(result){
    resultReview.setExecution(result);
    const host=get('rows');host.replaceChildren();if(!result)return;
    get('execute-message').textContent=result.error?[result.error,result.code,t('aitest.progress.seconds','{0}초',(result.elapsedMillis/1000).toFixed(2))].filter(Boolean).join(' · '):t('aitest.rowCount','{0}행 · {1}초',result.data.rows.length,(result.elapsedMillis/1000).toFixed(2));
    if(result.code==='ORA-01013')get('execute-message').textContent+='\n'+t('aitest.progress.cancelHint','작업 취소가 반환되었습니다. 앱의 SQL 실행 제한은 {0}초입니다. 제한시간에 따른 취소일 수 있으나 이 코드만으로 원인을 확정할 수 없습니다. 단계별 시간을 확인하세요.',root.dataset.executionTimeoutSeconds||'—');
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
    const token=execution.token;execution=null;resultReview.clear();busy=true;controls();executionDialog.close();get('rows').replaceChildren();message(t('aitest.executing','조회 중…'));
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
