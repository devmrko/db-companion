import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {renderQueryData} from './query-result-view.mjs';

/** Only a server-held execution ID is submitted. Displayed rows are never trusted input. */
export function resultArchiveAction(host,resultId,csrf,doc=host.ownerDocument||document){
  const panel=doc.createElement('section');panel.className='app-inspection';
  const note=doc.createElement('p');note.className='app-filter-message';note.textContent=t('resultArchive.saveHelp','표시한 질문·SQL·조회 행을 내 DB에 보관합니다. 최대 200행과 셀별 표시 한도가 그대로 저장됩니다.');
  const button=doc.createElement('button');button.type='button';button.className='btn app-btn app-btn-secondary';button.textContent=t('resultArchive.save','결과 보관');
  const link=doc.createElement('a');link.className='btn app-btn app-btn-quiet';link.href='/ai-executions/results';link.textContent=t('resultArchive.title','보관한 조회 결과');
  const status=doc.createElement('p');status.setAttribute('role','status');
  button.addEventListener('click',async()=>{
    button.disabled=true;status.textContent=t('resultArchive.saving','보관 중…');
    try{const saved=await assistantApi('/ai-test/results/save',assistantPost(csrf,{resultId,consent:true}));link.href='/ai-executions/results?id='+encodeURIComponent(saved.id);status.textContent=t('resultArchive.saved','보관 완료 · 이력에서 당시 결과를 다시 볼 수 있습니다.');}
    catch(error){status.textContent=error.message;button.disabled=false;}
  });
  panel.append(note,button,link,status);host.append(panel);return panel;
}

if(typeof document!=='undefined')document.querySelectorAll('[data-result-archive]').forEach(root=>{
  const find=name=>root.querySelector(`[data-ra-${name}]`),form=find('filters'),message=find('message'),list=find('list'),dialog=find('dialog'),detail=find('detail');
  let page=1,loading=false,detailVersion=0;
  function error(value){message.textContent=value;}
  async function open(id){
    const version=++detailVersion;detail.replaceChildren();find('detail-message').textContent=t('resultArchive.loading','불러오는 중…');if(!dialog.open)dialog.showModal();
    try{const value=await assistantApi('/ai-executions/results/detail?'+new URLSearchParams({id}));if(version!==detailVersion||!dialog.open)return;
      const heading=document.createElement('p');heading.textContent=`${value.profile} · ${value.question}`;
      const sql=document.createElement('pre');sql.className='app-preview-value';sql.textContent=value.data.sql;
      const note=document.createElement('p');note.className='app-filter-message';note.textContent=t('resultArchive.snapshot','보관 당시 결과 · 현재 데이터를 다시 조회한 결과가 아닙니다.');
      const rows=document.createElement('div');detail.append(heading,note,sql,rows);renderQueryData(rows,value.data);find('detail-message').textContent='';
    }catch(ex){if(version===detailVersion&&dialog.open)find('detail-message').textContent=ex.message;}
  }
  dialog.addEventListener('close',()=>{detailVersion++;detail.replaceChildren();});find('close').addEventListener('click',()=>dialog.close());
  async function load(){
    if(loading)return;loading=true;find('previous').disabled=true;find('next').disabled=true;list.replaceChildren();error(t('resultArchive.loading','불러오는 중…'));
    try{const query=new URLSearchParams(new FormData(form));query.set('page',String(page));const value=await assistantApi('/ai-executions/results/list?'+query);
      for(const item of value.items){const tr=document.createElement('tr');for(const text of [new Date(item.executedAt).toLocaleString(),item.profile,item.rowCount+(item.truncated?'+':'' )]){const td=document.createElement('td');td.textContent=text;tr.append(td);}const td=document.createElement('td'),button=document.createElement('button');button.type='button';button.className='app-inline-link app-table-description';button.textContent=item.question;button.addEventListener('click',()=>open(item.id));td.append(button);tr.append(td);list.append(tr);}
      error(value.items.length?'':t('resultArchive.empty','보관한 결과가 없습니다. 자연어 데이터 조회에서 SQL 실행 후 결과 보관을 선택하세요.'));
      find('page').textContent=String(page);find('previous').disabled=page===1;find('next').disabled=!value.hasNext||page>=1000;
    }catch(ex){error(ex.message);}finally{loading=false;}
  }
  form.addEventListener('submit',event=>{event.preventDefault();page=1;load();});
  find('previous').addEventListener('click',()=>{if(!loading&&page>1){page--;load();}});find('next').addEventListener('click',()=>{if(!loading&&page<1000){page++;load();}});
  load();const id=new URLSearchParams(location.search).get('id');if(id)open(id);
});
