import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {renderImportPreview,selectedImportRows} from './glossary-transfer.mjs';
import {t} from './i18n.mjs';
const label=(key,fallback,...args)=>t('glossaryDocument.'+key,fallback,...args);
const node=(tag,text)=>{const el=document.createElement(tag);if(text!==undefined)el.textContent=text;return el;};
const details=(title,body)=>{const el=node('details');el.className='app-disclosure';el.append(node('summary',title),body);return el;};
const pre=text=>{const el=node('pre',text);el.className='app-preview-value';return el;};
export function validDocumentFile(file){return !!file&&file.size>0&&file.size<=4_000_000&&/\.(txt|md|docx|pdf)$/i.test(file.name);}
export function documentRows(analysis,rows){return !!analysis&&rows.length>0&&new Set(rows).size===rows.length&&rows.every(i=>Number.isInteger(i)&&i>=0&&i<analysis.candidates.length);}
export function mountGlossaryDocument(root,{csrf,busy,setBusy,canWrite,reload}){
  const host=root.querySelector('[data-glossary-document]');if(!host)return {controls:()=>{}};
  let source=null,preview=null,analysis=null,review=null,models=[],indexed=false,uncertain=false;
  host.className='app-glossary-form';
  const button=(text,fn)=>{const b=node('button',text);b.type='button';b.className='btn app-btn app-btn-secondary';b.addEventListener('click',fn);return b;};
  const field=(title,input)=>{const el=node('label');if(input.type==='checkbox'){el.className='app-glossary-pick';el.append(input,node('span',title));}else el.append(node('span',title),input);return el;};
  const checkbox=()=>{const el=node('input');el.type='checkbox';return el;};
  const info=node('p');info.role='status';info.setAttribute('aria-live','polite');
  const upload=node('input');upload.type='file';upload.accept='.txt,.md,.docx,.pdf';upload.className='form-control app-glossary-file';
  const meta=node('p'),chunks=node('div'),hits=node('div'),candidates=node('div'),summary=node('div'),diff=node('div');
  const query=node('input');query.className='form-control';query.maxLength=500;
  const model=node('select');model.className='form-select';
  const semantic=checkbox(),indexConsent=checkbox(),consent=checkbox(),enabled=checkbox(),saveConsent=checkbox();
  const payload=pre(''),previewInfo=node('p');
  const message=(text,error=false)=>{info.textContent=text;info.className=error?'app-alert is-error':'app-filter-message';};
  const chunkIds=()=>[...chunks.querySelectorAll('input:checked')].map(el=>el.value);
  const rows=()=>[...candidates.querySelectorAll('input:checked')].map(el=>Number(el.value));
  const saveRows=()=>[...diff.querySelectorAll('input[data-import-row]:checked')].map(el=>Number(el.dataset.importRow));
  const post=(path,value)=>assistantApi('/business-glossary/document/'+path,assistantPost(csrf,value));
  function invalidatePreview(){preview=null;consent.checked=false;payload.textContent='';previewInfo.textContent='';controls();}
  function invalidateReview(){review=null;saveConsent.checked=false;diff.replaceChildren();controls();}
  function controls(){
    for(const el of host.querySelectorAll('button,input,select'))el.disabled=busy();
    clear.disabled=busy()||!source;find.disabled=busy()||!source||!query.value.trim();
    embed.disabled=busy()||!source||!model.value||!indexConsent.checked;
    semantic.disabled=busy()||!indexed;
    prepare.disabled=busy()||!source||!chunkIds().length;
    generate.disabled=busy()||!preview||!consent.checked;
    compare.disabled=busy()||!source||!canWrite()||!documentRows(analysis,rows());
    save.disabled=busy()||!canWrite()||uncertain||!saveConsent.checked||!selectedImportRows(review,saveRows());
    for(const check of diff.querySelectorAll('input[data-import-row]'))check.disabled=busy()||uncertain||!['NEW','UPDATE'].includes(review?.entries.find(e=>e.row===Number(check.dataset.importRow))?.status);
  }
  async function work(fn){if(busy())return;setBusy(true);controls();try{await fn();}catch(ex){message(ex.message,true);}finally{setBusy(false);controls();}}
  function reset(){source=null;indexed=false;semantic.checked=false;analysis=null;uncertain=false;meta.textContent='';chunks.replaceChildren();hits.replaceChildren();summary.replaceChildren();candidates.replaceChildren();invalidatePreview();invalidateReview();}
  function renderSource(){
    meta.textContent=label('source','{0} · 원문 조각 {1}개 · 보관 만료 {2}',source.name,source.chunks.length,new Date(source.expires).toLocaleString());
    chunks.replaceChildren();for(const chunk of source.chunks){
      const pick=checkbox();pick.value=chunk.id;pick.checked=true;pick.addEventListener('change',invalidatePreview);
      const content=node('section');content.append(field(`${chunk.id} · ${chunk.location} · ${chunk.start}–${chunk.end}`,pick),details(label('original','원문 보기'),pre(chunk.text)));chunks.append(content);
    }
  }
  upload.addEventListener('change',()=>work(async()=>{
    const file=upload.files?.[0];reset();
    if(!validDocumentFile(file))throw new Error(label('limits','TXT·MD·DOCX·텍스트 PDF, 최대 4 MB·추출 40,000자·PDF 80쪽. 스캔/OCR은 지원하지 않습니다.'));
    const body=new FormData();body.append('file',file);
    source=await assistantApi('/business-glossary/document/upload',{method:'POST',headers:{[csrf.dataset.csrfHeader]:csrf.value},body});upload.value='';renderSource();
    message(label('uploaded','원문을 나누고 키워드 색인을 만들었습니다. 아직 AI에 전송하거나 사전에 저장하지 않았습니다.'));
  }));
  const clear=button(label('clear','문서 지우기'),()=>work(async()=>{await post('clear',{});reset();message(label('cleared','세션의 문서·색인·후보를 지웠습니다. 저장한 용어는 삭제하지 않습니다.'));}));
  const refreshModels=button(label('models','로컬 임베딩 모델 조회'),()=>work(async()=>{
    models=await assistantApi('/business-glossary/document/models');model.replaceChildren();const empty=node('option',label('chooseModel','모델 선택 (선택 사항)'));empty.value='';model.append(empty);
    models.forEach((m,i)=>{const option=node('option',`${m.owner}.${m.name}`);option.value=String(i);model.append(option);});
    message(models.length?label('modelNote','등록된 로컬 ONNX 모델만 사용합니다. 모델 설치나 외부 임베딩 호출은 하지 않습니다.'):label('noModel','사용 가능한 로컬 모델이 없습니다. 키워드 검색과 AI 용어 추출은 사용할 수 있습니다.'));
  }));
  const embed=button(label('embed','로컬 임베딩 색인 만들기'),()=>work(async()=>{
    indexed=false;semantic.checked=false;
    const result=await post('index',{id:source.id,model:models[Number(model.value)],consent:indexConsent.checked});indexed=true;semantic.checked=true;
    message(label('indexed','{0}개 조각을 임베딩했습니다. 벡터는 세션에만 보관합니다.',result.chunks));
  }));
  const find=button(label('find','문서 근거 검색'),()=>work(async()=>{
    const result=await post('search',{id:source.id,question:query.value,semantic:semantic.checked});hits.replaceChildren();
    for(const hit of result)hits.append(details(`${hit.chunk.id} · ${hit.chunk.location} · ${Number(hit.score).toFixed(3)}`,pre(hit.chunk.text)));
    message(label('found','검색 결과 {0}개 · 점수는 업무 정확도가 아닙니다. AI 전송 대상은 아래 원문 조각에서 선택합니다.',result.length));
  }));
  const prepare=button(label('preview','AI 전송 미리보기'),()=>work(async()=>{
    invalidatePreview();preview=await post('preview',{id:source.id,chunks:chunkIds()});
    previewInfo.textContent=`${preview.profile.selection.owner}.${preview.profile.selection.name} · ${preview.profile.provider} / ${preview.profile.model} · ${chunkIds().length}/${source.chunks.length}`;
    payload.textContent=preview.source;message(label('previewNote','선택한 원문으로 요약·용어 후보를 생성합니다. 한 번의 유료 AI 호출이며 자동 재시도하지 않습니다.'));
  }));
  const generate=button(label('generate','요약·용어 후보 생성 (AI 1회)'),()=>work(async()=>{
    const token=preview.token;invalidatePreview();analysis=null;summary.replaceChildren();candidates.replaceChildren();invalidateReview();
    message(label('running','AI가 선택한 원문을 분석 중입니다. 다시 누르지 마세요.'));
    analysis=await post('analyze',{id:source.id,token,consent:true});
    summary.append(node('h3',label('summary','선택한 원문 요약')),node('p',analysis.summary));
    for(const warning of analysis.warnings)summary.append(node('p',warning));
    analysis.candidates.forEach((candidate,i)=>{
      const card=node('section');card.className='app-card app-glossary-card';const pick=checkbox();pick.value=String(i);pick.addEventListener('change',invalidateReview);
      card.append(field(`${candidate.term} · ${candidate.level==='DOMAIN'?label('domain','업무 개념'):label('detail','지표·상세 규칙')}`,pick),node('p',candidate.definition),pre(candidate.criteria||''));
      if(candidate.aliases?.length)card.append(node('p',candidate.aliases.join(' · ')));
      for(const cite of candidate.citations){const chunk=source.chunks.find(c=>c.id===cite.chunkId);card.append(details(`${cite.chunkId} · ${chunk.location}`,pre(cite.quote)));}
      candidates.append(card);
    });message(label('drafts','후보 {0}개. 인용 일치는 확인했지만 업무 정확성은 검토가 필요합니다. 아직 저장하지 않았습니다.',analysis.candidates.length));
  }));
  const compare=button(label('compare','선택 후보 · 기존 사전과 비교'),()=>work(async()=>{
    invalidateReview();review=await post('review',{id:source.id,rows:rows(),enabled:enabled.checked});uncertain=false;
    renderImportPreview(diff,review,controls);message(label('reviewNote','새 용어·변경 내용을 확인하고 저장할 항목을 다시 선택하세요. 기존 항목은 자동 덮어쓰지 않습니다.'));
  }));
  const save=button(label('save','검토한 항목 저장'),()=>work(async()=>{
    const selection={token:review.token,rows:saveRows(),consent:saveConsent.checked};invalidateReview();
    try{const result=await post('apply',{id:source.id,selection});await reload();message(label('saved','{0}개 항목을 출처·변경 이력과 함께 저장했습니다.',result.saved));}
    catch(ex){uncertain=true;throw ex;}
  }));
  for(const input of [query,model,indexConsent,consent,saveConsent])input.addEventListener('input',controls);
  model.addEventListener('change',()=>{indexed=false;semantic.checked=false;controls();});
  enabled.addEventListener('change',invalidateReview);
  generate.className='btn app-btn app-btn-primary';save.className='btn app-btn app-btn-primary';
  const aiLink=node('a',label('assistant','AI 도우미 설정'));aiLink.href='/ai-assistant';
  const indexPanel=node('div');indexPanel.className='app-glossary-form';indexPanel.append(refreshModels,field(label('model','임베딩 모델'),model),field(label('indexConsent','문서 조각을 DB 로컬 모델로 처리하는 데 동의합니다. DB 자원을 사용합니다.'),indexConsent),embed);
  const sourcePanel=details(label('chunks','AI 전송 대상 · 원문 조각 선택'),chunks);
  host.append(node('h2',label('title','문서로 용어 사전 보강')),node('p',label('flow','원문 확인 → AI 요약·용어 후보 → 기존 사전 비교 → 선택 저장')),
    node('p',label('limits','TXT·MD·DOCX·텍스트 PDF, 최대 4 MB·추출 40,000자·PDF 80쪽. 스캔/OCR은 지원하지 않습니다.')),
    node('p',label('retention','문서·검색 색인은 로그인 세션에서 최대 30분 동안만 사용합니다. 선택 저장한 용어와 출처 인용은 사전에 남습니다. 민감정보를 제거한 문서만 올리세요. DOCX는 본문·표의 텍스트만 추출하므로 원문을 확인하세요.')),
    field(label('upload','문서 선택'),upload),clear,meta,details(label('index','임베딩 설정 (선택 사항)'),indexPanel),field(label('search','문서 검색어'),query),field(label('semantic','임베딩 검색 사용'),semantic),find,hits,sourcePanel,
    aiLink,prepare,previewInfo,details(label('payload','실제 AI 전송 내용'),payload),field(label('consent','선택한 원문의 외부 전송과 AI 사용량 발생을 확인했습니다.'),consent),generate,info,summary,candidates,
    field(label('enabled','저장 후 검색에 사용 (기본은 비활성 초안)'),enabled),compare,diff,field(label('saveConsent','선택 항목의 내용·활성 상태와 기존 사전 변경을 확인했습니다.'),saveConsent),save,node('hr'));
  controls();return {controls};
}
