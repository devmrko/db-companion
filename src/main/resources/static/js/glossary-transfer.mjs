import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
const label=(key,fallback,...args)=>t('glossaryTransfer.'+key,fallback,...args);
const node=(tag,text)=>{const el=document.createElement(tag);if(text!==undefined)el.textContent=text;return el;};
export const MAX_IMPORT_BYTES=4_000_000;
export function selectedImportRows(preview,rows){
  return !!preview&&rows.length>0&&new Set(rows).size===rows.length&&rows.every(row=>preview.entries.some(e=>e.row===row&&['NEW','UPDATE'].includes(e.status)));
}
export function renderImportPreview(host,preview,onChange){
  host.replaceChildren();
  for(const entry of preview.entries){
    const card=node('section'),pick=node('label'),check=node('input');check.type='checkbox';check.dataset.importRow=String(entry.row);
    card.className='app-glossary-hit';pick.className='app-glossary-pick';
    check.disabled=!['NEW','UPDATE'].includes(entry.status);check.checked=false;
    check.addEventListener('change',onChange);
    const status={NEW:label('new','새 용어'),UPDATE:label('update','기존 용어 변경'),UNCHANGED:label('unchanged','동일 · 건너뜀'),CONFLICT:label('conflict','기존 중복 · 선택 불가')}[entry.status]||entry.status;
    pick.append(check,node('strong',`${entry.value.term} · ${status}`));card.append(pick);
    const details=node('details');details.className='app-disclosure';details.append(node('summary',label('content','내용 비교')));
    if(entry.previous)details.append(node('h4',label('before','현재 내용')),node('pre',JSON.stringify(entry.previous,null,2)));
    details.append(node('h4',label('after','가져올 내용')),node('pre',JSON.stringify(entry.value,null,2)));card.append(details);host.append(card);
  }
}
export function mountGlossaryTransfer(root,{csrf,busy,setBusy,canRead,canWrite,reload}){
  const get=key=>root.querySelector(`[data-transfer-${key}]`);let preview=null,uncertain=false;
  const message=(text,error=false)=>{get('message').textContent=text;get('message').className=error?'app-alert is-error':'app-filter-message';};
  const rows=()=>[...get('preview').querySelectorAll('input[data-import-row]:checked')].map(e=>Number(e.dataset.importRow));
  function controls(){
    const footer=get('footer');if(footer)footer.hidden=!preview;
    get('export').disabled=busy()||!canRead();get('file').disabled=busy()||!canRead();
    get('consent').disabled=busy()||!canWrite()||uncertain;
    get('apply').disabled=busy()||!canWrite()||uncertain||!get('consent').checked||!selectedImportRows(preview,rows());
    for(const check of get('preview').querySelectorAll('input[data-import-row]'))check.disabled=busy()||uncertain||!['NEW','UPDATE'].includes(preview.entries.find(e=>e.row===Number(check.dataset.importRow))?.status);
  }
  get('export').addEventListener('click',async()=>{
    if(busy()||!canRead())return;setBusy(true);
    try{const data=await assistantApi('/business-glossary/transfer/export');const url=URL.createObjectURL(new Blob([JSON.stringify(data)],{type:'application/json;charset=utf-8'}));
      const a=node('a');a.href=url;a.download='business-glossary.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
      message(label('exported','JSON 파일을 내보냈습니다. 파일에 업무 정의가 포함됩니다.'));
    }catch(ex){message(ex.message,true);}finally{setBusy(false);controls();}
  });
  get('file').addEventListener('change',async()=>{
    if(busy())return;preview=null;uncertain=false;get('consent').checked=false;get('preview').replaceChildren();
    const file=get('file').files?.[0];if(!file){controls();return;}
    setBusy(true);try{
      if(file.size>MAX_IMPORT_BYTES)throw new Error(label('limit','JSON 파일 한도는 4 MB, 용어는 500개입니다.'));
      const content=await file.text();JSON.parse(content);
      const options=assistantPost(csrf,{});options.body=content;
      preview=await assistantApi('/business-glossary/transfer/preview',options);
      renderImportPreview(get('preview'),preview,controls);message(label('review','가져올 항목을 선택하고 변경 내용을 확인하세요. 아직 저장하지 않았습니다.'));
    }catch(ex){message(ex.message,true);}finally{get('file').value='';setBusy(false);controls();}
  });
  get('consent').addEventListener('change',controls);
  get('apply').addEventListener('click',async()=>{
    const selected=rows();if(busy()||!canWrite()||uncertain||!get('consent').checked||!selectedImportRows(preview,selected))return;
    const token=preview.token;preview=null;setBusy(true);get('consent').checked=false;get('preview').replaceChildren();
    try{const result=await assistantApi('/business-glossary/transfer/apply',assistantPost(csrf,{token,rows:selected,consent:true}));await reload();message(label('saved','{0}개 항목을 저장했습니다.',result.saved));}
    catch(ex){uncertain=true;message(ex.message,true);}finally{setBusy(false);controls();}
  });
  return {controls};
}
