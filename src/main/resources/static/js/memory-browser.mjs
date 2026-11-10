export const memoryKindLabels={memory:'장기 기억',message:'단기 문맥 · 메시지',summary:'대화 요약',actor:'사용자·에이전트 정보',conversation:'단기 문맥 · Select AI 대화'};
export function memoryLink(store){return '/ai-memory?'+new URLSearchParams({store});}
export function mountMemoryBrowser(root){
  const get=n=>root.querySelector(`[data-mb-${n}]`),base='/ai-profiles/memory-policy';
  let page=0,busy=false,next=false,selection=null;
  const dialog=get('dialog');
  get('close').addEventListener('click',()=>dialog.close());
  const node=(tag,text)=>{const el=document.createElement(tag);el.textContent=text??'미지정';return el;};
  function controls(){for(const el of get('form').elements)el.disabled=busy;get('prev').disabled=busy||!selection||page===0;get('next').disabled=busy||!next;}
  async function api(path,params){const response=await fetch(base+path+'?'+new URLSearchParams({schema:root.dataset.schema,...params}),{credentials:'same-origin',cache:'no-store'});
    if(response.redirected||response.status===401)throw Error('로그인 세션이 만료되었습니다.');
    if(!response.headers.get('content-type')?.includes('application/json'))throw Error('서버 응답을 확인하지 못했습니다.');
    const data=await response.json();if(!response.ok)throw Error(data.error||'조회 실패');return data;}
  function details(row){
    const host=get('detail');host.replaceChildren(node('h3',memoryKindLabels[row.kind]+' · 상세'));
    const dl=node('dl','');dl.className='app-config-summary';
    for(const [label,value] of [['Record / Actor ID',row.id],['Thread ID',row.threadId],['User ID',row.userId],['Agent ID',row.agentId],['Space ID',row.spaceId],['세부 유형',row.type],['상태',row.status],['생성 시각',row.created]]){const group=node('div','');group.append(node('dt',label),node('dd',value));dl.append(group);}
    const content=node('pre',row.content);content.className='app-config-output';host.append(dl,content);
  }
  async function load(requestedPage=0){
    if(busy||!get('store').value)return;busy=true;next=false;controls();get('status').textContent='저장된 메모리 조회 중…';get('rows').replaceChildren();get('detail').replaceChildren();
    const selected=requestedPage!==0&&selection?selection:{name:get('store').value,kind:get('kind').value,query:get('query').value};
    try{const result=await api('/records',{...selected,page:requestedPage});selection=selected;page=result.page;next=result.hasNext;
      history.replaceState(null,'',memoryLink(selected.name));
      get('status').textContent=`Store ID: ${result.storeId} · ${result.method}`;get('page').textContent=`${page+1}페이지 · ${result.rows.length?`${page*25+1}–${page*25+result.rows.length}건`:'0건'} · 페이지당 25건`;
      if(!result.rows.length)get('rows').append(node('p','조건에 해당하는 저장 기록이 없습니다.'));
      const table=node('table','');table.className='app-memory-table';table.append(node('caption','저장된 메모리 목록'));
      const head=node('thead',''),headRow=node('tr','');for(const label of ['유형','내용','생성 시각','Record ID','상세']){const th=node('th',label);th.scope='col';headRow.append(th);}head.append(headRow);table.append(head);
      const body=node('tbody','');table.append(body);if(result.rows.length)get('rows').append(table);
      for(const row of result.rows){const tr=node('tr','');
        const kind=node('td',`${memoryKindLabels[row.kind]} · ${row.type||'미지정'}`),content=node('td',''),preview=node('span',row.content),created=node('td',row.created),id=node('td',row.id),action=node('td','');
        preview.className='app-memory-preview';content.append(preview);id.className='app-memory-id';id.title=row.id;created.className='app-memory-date';tr.append(kind,content,created,id,action);
        const button=node('button','보기');button.type='button';button.className='btn app-btn';button.setAttribute('aria-label',`${row.id} 내용·ID 상세 보기`);
        button.addEventListener('click',async()=>{if(busy)return;busy=true;controls();try{const detail=await api('/record',{name:selected.name,kind:row.kind,id:row.id});details(detail);dialog.showModal();}catch(e){get('status').textContent=e.message;}finally{busy=false;controls();}});
        action.append(button);body.append(tr);}
    }catch(e){selection=null;get('status').textContent=e.message;get('page').textContent='';}finally{busy=false;controls();}
  }
  get('form').addEventListener('submit',e=>{e.preventDefault();load();});
  get('store').addEventListener('change',()=>load());get('kind').addEventListener('change',()=>load());
  get('prev').addEventListener('click',()=>load(page-1));get('next').addEventListener('click',()=>load(page+1));
  (async()=>{busy=true;controls();try{const data=await api('',{profile:''});const requested=new URLSearchParams(location.search).get('store');
    for(const store of data.stores){const option=node('option',store.name);option.value=store.name;get('store').append(option);}
    if(requested){if(!data.stores.some(s=>s.name===requested))throw Error('요청한 저장소가 현재 로그인 소유자에게 없습니다.');get('store').value=requested;}
    if(!data.stores.length)get('status').textContent='등록된 Memory Store가 없습니다. 프로필에서 저장소를 생성해 주세요.';
    busy=false;controls();await load();
  }catch(e){get('status').textContent=e.message;busy=false;controls();}})();
}
if(typeof document!=='undefined')document.querySelectorAll('[data-memory-browser]').forEach(mountMemoryBrowser);
