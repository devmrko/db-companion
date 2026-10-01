import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
import './select-ai-batch.mjs';

const problemStatuses={RECEIVED:['received','접수','is-received'],UNDER_REVIEW:['underReview','검토 중','is-under-review'],REVIEWED:['reviewed','검토 완료','is-reviewed'],ON_HOLD:['onHold','보류','is-on-hold']};
export const problemStatusLabel=status=>{const value=problemStatuses[status];return value?t('problemQuestion.status.'+value[0],value[1]):String(status??'—');};
export const problemStatusClass=status=>problemStatuses[status]?.[2]??'';
export const problemStorageView=state=>({
  ready:state==='READY',canSetup:state==='MISSING',
  message:state==='READY'?t('problemQuestion.storageReady','문제 질문 저장소 준비 완료 (DBC_APP_RECORD)')
    :state==='MISSING'?t('problemQuestion.storageMissing','문제 질문 저장소(DBC_APP_RECORD)가 아직 없습니다. 저장소를 준비한 후 다시 확인해 주세요.')
    :state==='MISMATCH'?t('problemQuestion.storageMismatch','DBC_APP_RECORD의 구조가 앱 요구사항과 다릅니다. 기존 테이블을 변경하지 않았습니다. 관리자에게 확인해 주세요.')
    :t('problemQuestion.storageUnavailable','문제 질문 저장소의 상태를 확인하지 못했습니다. 연결과 조회 권한을 확인한 뒤 다시 시도해 주세요.')
});
const uiElement=(tag,text,cls)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;};

export const redactDiagnostic=value=>String(value??'')
  .replace(/-----BEGIN [^-]*(?:PRIVATE KEY|CERTIFICATE)[^-]*-----[\s\S]*?-----END [^-]*(?:PRIVATE KEY|CERTIFICATE)[^-]*-----/gi,'[REDACTED PEM]')
  .replace(/(^\s*(?:authorization|cookie)\s*:\s*).*$|("(?:authorization|cookie|password|passwd|secret|token|credential|private[_ -]?key|wallet|api[_ -]?key|oracle[_ -]?(?:password|wallet(?:[_ -]?path)?))"\s*:\s*)"(?:\\.|[^"\\])*"/gim,(all,header,jsonKey)=>header?`${header}[REDACTED]`:`${jsonKey}"[REDACTED]"`)
  .replace(/(^\s*(?:password|passwd|secret|token|credential|private[ _-]?key|wallet|api[_ -]?key|oracle[_ -]?(?:password|wallet(?:[_ -]?path)?))\s*[:=]\s*)(?:"[^"]*"|'[^']*'|.*)$/gim,'$1[REDACTED]');
export const diagnosticMarkdown=(detail,include={question:true,sql:true,error:true,options:true,prompt:true,metadata:true,feedback:true})=>{
  const p=detail.parent, lines=[`# Select AI problem question`, '', `- Status: ${p.status}`, `- Created: ${p.createdAt}`, '', '## Question', redactDiagnostic(p.question), '', '## Problem description', redactDiagnostic(p.description), '', '## Expected behavior', redactDiagnostic(p.expected)];
  if(!include.question)lines.splice(4);if(include.sql&&p.expectedSql)lines.push('','## Expected SQL (user supplied)','```sql',redactDiagnostic(p.expectedSql),'```');
  for(const a of detail.attempts){
    lines.push('','## Attempt',`- Captured: ${a.capturedAt}`,`- Record source: ${a.snapshotKind||'not recorded'}`,`- Availability: ${a.availability||'not recorded'}`);
    if(include.options)lines.push('','### Profile and timing',redactDiagnostic(`Profile: ${a.profile||'not recorded'}\nModel: ${a.model||'not recorded'}\nElapsed (ms): ${a.elapsedMillis??'not recorded'}`));
    if(include.question){lines.push('','### Input',redactDiagnostic(a.input));if(a.conditions)lines.push('','### Confirmed conditions',redactDiagnostic(a.conditions));}
    if(include.sql&&a.sql)lines.push('','### SQL','```sql',redactDiagnostic(a.sql),'```');
    if(include.error&&a.error){lines.push('','### Error',redactDiagnostic(a.error));if(a.response)lines.push('','### Generation response (unverified)',redactDiagnostic(a.response));}
    for(const [enabled,title,snapshot,legacy] of [[include.options,'Options',a.optionSnapshot,a.options],[include.prompt,'Full PROMPT',a.promptSnapshot,a.prompt],[include.metadata,'Metadata',a.metadataSnapshot,a.metadata],[include.feedback,'Feedback',a.feedbackSnapshot,a.feedback]])if(enabled&&(snapshot||legacy)){lines.push('',`### ${title}`,`- Source/status: ${snapshot?.availability||a.availability||'not recorded'}`,`- Checked: ${snapshot?.checkedAt||'not recorded'}`,redactDiagnostic(snapshot?.value??legacy));}
  }
  return lines.join('\n')+'\n';
};
export const attemptObservation=(attempt,name)=>{
  const key={options:'optionSnapshot',prompt:'promptSnapshot',metadata:'metadataSnapshot',feedback:'feedbackSnapshot'}[name];
  const snapshot=key?attempt[key]:null;
  return {value:snapshot?.value??attempt[name]??'',availability:snapshot?.availability??(attempt[name]?'LEGACY':'NOT_RECORDED'),checkedAt:snapshot?.checkedAt??null};
};
export const clearSavedComparison=host=>{host.hidden=true;host.replaceChildren();};
export const exportDetail=(detail,ids)=>({...detail,attempts:detail.attempts.filter(attempt=>ids.includes(attempt.id))});

if(typeof document!=='undefined')document.querySelectorAll('[data-problem-questions]').forEach(root=>{
  const get=name=>root.querySelector(`[data-problem-${name}]`),csrf=get('csrf');let selected=null,storageReady=false,checkingStorage=false;
  const post=(path,data)=>assistantApi('/ai-test/problems'+path,assistantPost(csrf,data));
  const message=(value,error=false)=>{get('message').textContent=value;get('message').className=error?'app-alert is-error':'app-filter-message';};
  const field=name=>root.querySelector(`[data-attempt-${name}]`);
  function renderDetail(value){clearSavedComparison(get('comparison'));selected=value;const host=get('detail');host.hidden=false;host.replaceChildren();const heading=document.createElement('h3');heading.textContent=value.parent.question;host.append(heading);const editor=document.createElement('div'),status=document.createElement('select'),retained=document.createElement('input'),save=document.createElement('button');for(const name of ['RECEIVED','UNDER_REVIEW','REVIEWED','ON_HOLD'])status.append(new Option(problemStatusLabel(name),name));status.value=value.parent.status;retained.type='checkbox';retained.checked=value.parent.retained;save.type='button';save.className='btn app-btn app-btn-quiet';save.textContent=t('problemQuestion.saveState','상태·보존 저장');save.addEventListener('click',async()=>{try{const parent=await post('/update?id='+encodeURIComponent(value.parent.id),{status:status.value,retained:retained.checked,expectedUpdatedAt:value.parent.updatedAt});renderDetail({...value,parent});await list();}catch(ex){message(ex.message,true);}});editor.className='workbench-actions';status.className='form-select';status.setAttribute('aria-label',t('problemQuestion.status','상태'));const retainedLabel=uiElement('label',undefined,'workbench-check');retainedLabel.append(retained,document.createTextNode(t('problemQuestion.retained','보존')));const statusLabel=uiElement('label');statusLabel.append(status);editor.append(statusLabel,retainedLabel,save);host.append(heading,editor);const meta=document.createElement('p');meta.className='app-filter-message';meta.textContent=t('workbench.attemptSummary','{0} · 실행 기록 {1}건',problemStatusLabel(value.parent.status),value.attempts.length);host.append(meta);for(const attempt of value.attempts){const block=document.createElement('details'),summary=document.createElement('summary'),pick=document.createElement('input');pick.type='checkbox';pick.dataset.attemptId=attempt.id;pick.setAttribute('aria-label',t('problemQuestion.pickCompare','비교할 실행 기록 선택'));summary.append(pick,document.createTextNode(` ${new Date(attempt.capturedAt).toLocaleString()} · ${attempt.profile||t('problemQuestion.profileMissing','profile not recorded')}`));block.append(summary);const pre=document.createElement('pre');pre.className='app-preview-value';const snapshot=name=>attemptObservation(attempt,name).value;const status=name=>{const v=attemptObservation(attempt,name);return `${v.availability} · ${v.checkedAt||t('problemQuestion.timeUnknown','조회 시각 미확인')}`;};pre.textContent=[attempt.input,attempt.conditions&&`confirmed conditions:\n${attempt.conditions}`,attempt.sql,attempt.error,...['options','prompt','metadata','feedback'].flatMap(name=>[`${name}: ${status(name)}`,snapshot(name)])].filter(Boolean).join('\n\n');block.append(pre);host.append(block);}field('parent').value=value.parent.id;get('attempt-card').hidden=false;get('actions').hidden=false;}
  async function list(before=''){
    try{
      const page=await assistantApi('/ai-test/problems/list?'+new URLSearchParams({before}));
      const host=get('list');host.replaceChildren();
      if(!page.rows.length){
        host.append(uiElement('p',t('problemQuestion.empty','저장된 문제 질문이 없습니다.'),'workbench-empty'));
      }else{
        const table=uiElement('table',undefined,'workbench-table'),head=uiElement('thead'),headers=uiElement('tr');
        table.setAttribute('aria-label',t('problemQuestion.savedList','저장된 문제 질문'));
        for(const [key,fallback,cls]of [['workbench.pick','선택','workbench-pick'],['problemQuestion.status','상태',''],['problemQuestion.question','질문',''],['workbench.attempts','실행 기록','workbench-count']]){
          const th=uiElement('th',t(key,fallback),cls);th.scope='col';headers.append(th);
        }
        head.append(headers);table.append(head);const body=uiElement('tbody');
        for(const item of page.rows){
          const row=uiElement('tr'),pickCell=uiElement('td',undefined,'workbench-pick'),pick=uiElement('input');
          pick.type='checkbox';pick.dataset.batchParent=item.id;pick.dataset.batchQuestion=item.question;
          pick.setAttribute('aria-label',t('problemQuestion.batchPick','일괄 테스트 질문 선택: {0}',item.question));pickCell.append(pick);
          const statusCell=uiElement('td'),badge=uiElement('span',problemStatusLabel(item.status),'workbench-badge '+problemStatusClass(item.status));statusCell.append(badge);
          const questionCell=uiElement('td'),button=uiElement('button',item.question,'workbench-question-link');button.type='button';
          button.addEventListener('click',async()=>{
            try{renderDetail(await assistantApi('/ai-test/problems/detail?'+new URLSearchParams({id:item.id})));get('detail').scrollIntoView({block:'start'});}
            catch(ex){message(ex.message,true);}
          });
          questionCell.append(button);row.append(pickCell,statusCell,questionCell,uiElement('td',String(item.attempts),'workbench-count'));body.append(row);
        }
        table.append(body);host.append(table);
      }
      if(page.next){
        const footer=uiElement('div',undefined,'app-pagination'),more=uiElement('button',t('problemQuestion.more','더 보기'),'btn app-btn app-btn-secondary');
        more.type='button';more.addEventListener('click',()=>list(page.next));footer.append(more);host.append(footer);
      }
      root.dispatchEvent(new Event('problem-list-rendered'));
    }catch(ex){message(ex.message,true);}
  }
  async function status(){
    if(checkingStorage)return;checkingStorage=true;storageReady=false;get('save').disabled=true;get('storage-refresh').disabled=true;
    get('storage').textContent=t('problemQuestion.storageChecking','문제 질문 저장소 확인 중…');get('storage-setup').hidden=true;get('storage-guide').hidden=true;
    root.querySelector('[data-batch-panel]').hidden=true;get('batch-link').hidden=true;
    selected=null;get('detail').hidden=true;get('attempt-card').hidden=true;get('actions').hidden=true;clearSavedComparison(get('comparison'));get('list').replaceChildren();message('');
    root.dispatchEvent(new Event('problem-list-rendered'));
    try{
      const data=await assistantApi('/ai-test/problems/status'),view=problemStorageView(data.storage);
      get('storage').textContent=view.message;get('storage-setup').hidden=!view.canSetup;get('storage-guide').hidden=!view.canSetup;
      storageReady=view.ready;get('save').disabled=!storageReady;root.querySelector('[data-batch-panel]').hidden=!storageReady;get('batch-link').hidden=!storageReady;
      if(storageReady)await list();
      else get('list').append(uiElement('p',view.message,'workbench-empty'));
    }catch(ex){get('storage').textContent=ex.message;get('list').append(uiElement('p',ex.message,'workbench-empty'));}
    finally{checkingStorage=false;get('storage-refresh').disabled=false;}
  }
  get('storage-refresh').addEventListener('click',status);
  get('create').addEventListener('submit',async event=>{event.preventDefault();if(!storageReady)return;const question=get('question').value,description=get('description').value,expected=get('expected').value;if(!question.trim()||!description.trim()||!expected.trim())return;try{const result=await post('',{question,description,expected,expectedSql:get('expected-sql').value,status:get('status').value});message(t('problemQuestion.created','저장했습니다: {0}',result.id));event.target.reset();await list();}catch(ex){message(ex.message,true);}});
  get('attempt').addEventListener('submit',async event=>{event.preventDefault();if(!selected||!field('input').value.trim())return;try{await post('/attempt',{parentId:selected.parent.id,input:field('input').value,conditions:field('conditions').value,response:field('response').value,sql:field('sql').value,error:field('error').value,profile:'',model:'',options:'',prompt:'',metadata:field('metadata').value,feedback:'',snapshotKind:'USER_SELECTED',availability:'CAPTURED',elapsedMillis:0});message(t('problemQuestion.attemptSaved','실행 기록을 저장했습니다.'));event.target.reset();renderDetail(await assistantApi('/ai-test/problems/detail?'+new URLSearchParams({id:selected.parent.id})));await list();}catch(ex){message(ex.message,true);}});
  const exportDialog=get('export-dialog'),exportFields=['question','sql','error','options','prompt','metadata','feedback'];
  const exportOptions=()=>Object.fromEntries(exportFields.map(name=>[name,get(`export-${name}`).checked]));
  let exporting=null;const refreshExport=()=>{if(exporting)get('export-preview').value=redactDiagnostic(diagnosticMarkdown(exporting,exportOptions()));};
  get('export').addEventListener('click',()=>{if(!selected)return;const ids=[...get('detail').querySelectorAll('[data-attempt-id]:checked')].map(node=>node.dataset.attemptId);if(!ids.length){message(t('problemQuestion.exportSelect','내보낼 실행 기록을 선택해 주세요.'),true);return;}exporting=exportDetail(selected,ids);refreshExport();exportDialog.showModal();});
  for(const name of exportFields)get(`export-${name}`).addEventListener('change',refreshExport);
  get('export-close').addEventListener('click',()=>exportDialog.close());
  get('export-download').addEventListener('click',()=>{if(!selected)return;const safe=redactDiagnostic(get('export-preview').value),blob=new Blob([safe],{type:'text/markdown;charset=utf-8'}),link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download=`select-ai-problem-${selected.parent.id}.md`;link.click();setTimeout(()=>URL.revokeObjectURL(link.href),0);});
  get('compare').addEventListener('click',async()=>{if(!selected)return;const ids=[...get('detail').querySelectorAll('[data-attempt-id]:checked')].map(node=>node.dataset.attemptId);if(ids.length!==2){message(t('problemQuestion.compareSelect','비교할 실행 기록 두 건을 선택해 주세요.'),true);return;}try{const parentId=selected.parent.id;const result=await post('/compare',{parentId,leftId:ids[0],rightId:ids[1]}),host=get('comparison');if(selected?.parent.id!==parentId)return;host.hidden=false;host.textContent=JSON.stringify(result,null,2);}catch(ex){message(ex.message,true);}});
  get('delete').addEventListener('click',async()=>{if(!selected)return;try{const preview=await post('/delete-preview',{ids:[selected.parent.id],confirmed:false,records:0,attempts:0,fingerprint:''});if(!confirm(`문제 ${preview.records}건과 실행 기록 ${preview.attempts}건을 삭제하시겠습니까?`))return;await post('/delete',{ids:preview.ids,confirmed:true,records:preview.records,attempts:preview.attempts,fingerprint:preview.fingerprint});selected=null;clearSavedComparison(get('comparison'));get('detail').hidden=true;get('attempt-card').hidden=true;get('actions').hidden=true;message('선택 자료를 삭제했습니다.');await list();}catch(ex){message(ex.message,true);}});
  status();
});
