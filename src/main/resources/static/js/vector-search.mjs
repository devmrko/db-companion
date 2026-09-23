import {t} from './i18n.mjs';
import {pageOf} from './table-list.mjs';
import {publicCatalog,publicModelState,MANUAL_MODEL,publicRegions,publicRegionState,MANUAL_REGION} from './embedding-catalog.mjs';

export function embeddingOptions(provider) {
  return {database:provider==='database',remote:['ocigenai','cohere','openai'].includes(provider),
    oci:provider==='ocigenai',input:['ocigenai','cohere'].includes(provider)};
}
export function searchInput(selection,values) {
  if(!selection.vector)throw new Error(t('ui.d38f872a0008', "벡터 컬럼을 선택해 주세요."));
  if(!values.provider)throw new Error(t('ui.4de4330db3b4', "임베딩 방식을 선택해 주세요."));
  if(!values.metric)throw new Error(t('ui.e112671c115e', "거리 방식을 선택해 주세요."));
  if(!values.text?.trim()||new TextEncoder().encode(values.text).length>4000)throw new Error(t('ui.3b1206b60d82', "검색어는 UTF-8 기준 4,000바이트 이내로 입력해 주세요."));
  if(!Number.isInteger(values.k)||values.k<1||values.k>100)throw new Error(t('ui.7516f84cee1e', "K는 1~100으로 입력해 주세요."));
  const options=embeddingOptions(values.provider);
  if(!values.model||(options.database&&!values.modelOwner))throw new Error(t('ui.831b1b0d198a', "임베딩 모델을 선택하거나 입력해 주세요."));
  if(options.remote&&(!values.credential||!values.externalConsent))throw new Error(t('ui.595aadd20a5e', "Credential과 외부 전송·비용 확인이 필요합니다."));
  if(options.oci&&!/^[a-z]{2}-[a-z]+-[1-9][0-9]?$/.test(values.region))throw new Error(t('ui.1b3dd86a1df6', "OCI 리전 이름을 확인해 주세요."));
  return {selection,...values,inputType:options.input?values.inputType:''};
}
const examples={cohere:['embed-multilingual-v3.0','embed-english-v3.0'],openai:['text-embedding-3-small','text-embedding-3-large','text-embedding-ada-002']};
// Syntax examples, not a live catalogue or a guarantee of regional/DB adapter support.
export function modelExamples(provider) { return publicCatalog(provider)?.models.map(model=>model.name)??[...(examples[provider]??[])]; }

if(typeof document!=='undefined') {
  const list=document.querySelector('[data-vector-list]');
  if(list) {
    const items=[...list.querySelectorAll('[data-vector-row]')].map(row=>({row,name:row.dataset.name,description:row.dataset.description}));
    let page=1;
    const render=()=>{
      const result=pageOf(items,list.querySelector('[data-filter]').value,page);page=result.page;
      const visible=new Set(result.items);items.forEach(item=>item.row.hidden=!visible.has(item));
      list.querySelector('[data-empty]').hidden=result.total!==0;
      list.querySelector('[data-summary]').textContent=t('ui.f32c9f13d498', "{0}–{1} / {2}개", result.from, result.to, result.total);
      list.querySelector('[data-page]').textContent=`${result.pages?page:0} / ${result.pages}`;
      list.querySelector('[data-prev]').disabled=page<=1;list.querySelector('[data-next]').disabled=page>=result.pages;
    };
    list.querySelector('[data-filter]').addEventListener('input',()=>{page=1;render();});
    list.querySelector('[data-prev]').addEventListener('click',()=>{page--;render();});
    list.querySelector('[data-next]').addEventListener('click',()=>{page++;render();});render();
  }
  const root=document.querySelector('[data-vector-explorer]');
  if(root) {
    // Also remove markup cached before LOCAL-043 without ending active login sessions.
    root.querySelector('.app-oci-catalog')?.remove();
    const find=name=>root.querySelector(`[data-${name}]`);
    const node=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text??'NULL';if(cls)n.className=cls;return n;};
    const positionHelp=(button,tip)=>tip.addEventListener('toggle',event=>{
      const open=event.newState==='open';button.setAttribute('aria-expanded',String(open));
      if(!open){button.removeAttribute('aria-describedby');return;}
      button.setAttribute('aria-describedby',tip.id);
      const rect=button.getBoundingClientRect();
      tip.style.left=`${Math.max(10,Math.min(rect.left,window.innerWidth-tip.offsetWidth-10))}px`;
      tip.style.top=`${Math.max(10,Math.min(rect.bottom+8,window.innerHeight-tip.offsetHeight-10))}px`;
    });
    positionHelp(find('input-type-help'),root.querySelector('#embedding-input-type-help'));
    positionHelp(find('public-model-help'),root.querySelector('#embedding-public-model-help'));
    positionHelp(find('region-help'),root.querySelector('#embedding-region-help'));
    let metadata=null,results=null,resultSelection=null,localPage=1,busy=false,detailVersion=0;
    let manualModel=false,manualRegion=false;
    const regions=publicRegions(),regionSelect=find('region-select');
    find('region-source').href=regions.source;
    find('region-date').textContent=t('vector.public.reviewed','공개 문서 확인: {0}',regions.reviewedAt);
    const regionInitial=node('option',t('ui.8d1a750c9351','선택'));regionInitial.value='';
    const regionManual=node('option',t('vector.public.manual','직접 입력'));regionManual.value=MANUAL_REGION;
    regionSelect.replaceChildren(regionInitial,...regions.regions.map(region=>{
      const option=node('option',`${region.id} · ${region.city}`);option.value=region.id;return option;
    }),regionManual);
    function syncRegion() {
      const state=publicRegionState(find('region').value,manualRegion);
      regionSelect.value=state.choice;find('region').hidden=!state.manual;
    }
    const catalog=publicCatalog('ocigenai');
    find('public-catalog-source').href=catalog.source;
    find('public-catalog-date').textContent=t('vector.public.reviewed','공개 문서 확인: {0}',catalog.reviewedAt);
    const publicSelect=find('public-model');
    const initial=node('option',t('ui.8d1a750c9351','선택'));initial.value='';
    const manualOption=node('option',t('vector.public.manual','직접 입력'));manualOption.value=MANUAL_MODEL;
    publicSelect.replaceChildren(initial,...catalog.models.map(model=>{
      const option=node('option',model.name+(model.deprecated?' · '+t('vector.public.deprecated','Deprecated'):''));
      option.value=model.name;return option;
    }),manualOption);
    function syncPublicModel() {
      const provider=find('provider').value,state=publicModelState(provider,find('model').value,manualModel);
      publicSelect.hidden=provider!=='ocigenai';publicSelect.value=state.choice;
      find('model').hidden=provider==='ocigenai'&&!state.manual;
      find('model-label').htmlFor=provider==='ocigenai'?'embedding-public-model':'embedding-model';
      find('public-model-source').hidden=provider!=='ocigenai'||!state.model;
      if(state.model)find('public-model-source').href=state.model.source;
    }
    const message=text=>{find('error').textContent=text;find('error').hidden=!text;};
    const selection=()=>({schema:root.dataset.schema,table:root.dataset.table,vector:find('vector').value,content:find('content').value});
    const setBusy=value=>{busy=value;find('fields').disabled=value||!metadata;find('options-refresh').disabled=value;root.setAttribute('aria-busy',String(value));renderPager();};
    const api=async(path,params,body)=>{
      const url=new URL(root.dataset.base+path,window.location.href);
      Object.entries(params??{}).forEach(([key,value])=>url.searchParams.set(key,value));
      const headers={Accept:'application/json'};
      if(body){headers['Content-Type']='application/json';headers['X-CSRF-TOKEN']=find('search-form').querySelector('[name="_csrf"]')?.value??'';}
      const response=await fetch(url,{method:body?'POST':'GET',headers,cache:'no-store',...(body?{body:JSON.stringify(body)}:{})});
      if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1', "로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
      const data=await response.json();
      if(!response.ok)throw new Error(data.error||t('ui.9eb9ea16ec49', "요청을 처리하지 못했습니다. 입력값과 로그인 상태를 확인해 주세요."));
      return data;
    };
    const options=()=>{
      const state=embeddingOptions(find('provider').value);
      for(const [selector,visible] of [['db-option',state.database],['remote-option',state.remote],['oci-option',state.oci],['input-option',state.input]])root.querySelectorAll(`[data-${selector}]`).forEach(n=>n.hidden=!visible);
      const notes=[];
      if(state.database)notes.push(metadata?.options.modelError||(metadata?.options.models.length?'':t('ui.7851af2116be', "조회 가능한 DB 임베딩 모델이 없습니다.")));
      if(state.remote)notes.push(metadata?.options.credentialError||(metadata?.options.credentials.length?'':t('ui.aba766fd1bc9', "활성 Credential이 없습니다.")));
      find('option-message').textContent=notes.filter(Boolean).join('\n');find('option-message').hidden=!find('option-message').textContent;
      const suggestions=modelExamples(find('provider').value);
      root.querySelector('#embedding-examples').replaceChildren(...suggestions.map(value=>{const n=node('option');n.value=value;return n;}));
      find('model').placeholder=state.oci?t('vector.public.modelName','모델명'):(suggestions[0]??'');
      syncPublicModel();
      syncRegion();
    };
    const fill=(select,items,empty)=>{const initial=node('option',empty);initial.value='';select.replaceChildren(initial,...items.map(([value,label])=>{const n=node('option',label);n.value=value;return n;}));};
    async function loadMetadata(refresh=false) {
      setBusy(true);message('');find('status').textContent=t('ui.e8da75a51a4e', "컬럼·옵션 조회 중…");find('results').hidden=true;results=null;
      try {
        metadata=await api('/metadata',{schema:root.dataset.schema,table:root.dataset.table,refresh});
        const vectors=metadata.columns.filter(c=>c.type==='VECTOR');
        fill(find('vector'),vectors.map(c=>[c.name,c.name]),t('ui.8d1a750c9351', "선택"));if(vectors.length===1)find('vector').value=vectors[0].name;
        // The server provides supported flags as methods only in templates, so keep this UI whitelist aligned.
        const available=metadata.columns.filter(c=>c.type!=='VECTOR'&&/^(CHAR|VARCHAR2|NCHAR|NVARCHAR2|CLOB|NCLOB|JSON|NUMBER|FLOAT|BINARY_FLOAT|BINARY_DOUBLE|DATE|RAW|ROWID|UROWID|BOOLEAN|TIMESTAMP.*|INTERVAL.*)$/.test(c.type));
        fill(find('content'),available.map(c=>[c.name,c.name]),t('ui.f1be19b8bab6', "선택 안 함"));
        find('content').value=(available.find(c=>/^(CLOB|VARCHAR2|NVARCHAR2|JSON)$/.test(c.type))??available[0])?.name??'';
        fill(find('db-model'),metadata.options.models.map((m,i)=>[String(i),`${m.owner}.${m.name}`]),t('ui.8d1a750c9351', "선택"));
        fill(find('credential'),metadata.options.credentials.map(c=>[c,c]),t('ui.8d1a750c9351', "선택"));
        options();find('status').textContent='';
      }catch(error){metadata=null;message(error.message);find('status').textContent='';}
      finally{setBusy(false);}
    }
    function renderPager() {
      const page=results?.search?localPage:(results?.page??1),pages=results?.search?Math.max(1,Math.ceil(results.items.length/10)):null;
      find('result-prev').disabled=busy||!results||page<=1;
      find('result-next').disabled=busy||!results||(results.search?page>=pages:!results.hasNext||page>=1000);
      find('result-page').textContent=results?.search?`${page} / ${pages}`:t('ui.25be58ceeac8', "{0} 페이지", page);
    }
    function renderResults() {
      find('rows').replaceChildren();find('results').hidden=!results;if(!results)return;
      const page=results.search?localPage:results.page,items=results.search?results.items.slice((page-1)*10,page*10):results.items;
      find('result-title').textContent=results.search?t('ui.b8f9f19a7008', "Top-K 검색 결과"):t('ui.e921be8e69cd', "데이터");find('content-title').textContent=resultSelection.content||t('ui.fc48bc81ccea', "행");
      find('result-summary').textContent=results.search?t('ui.45097807fa79', "{0}건 · 거리 오름차순", results.items.length):t('ui.efd506fcc7af', "{0}건", items.length);
      items.forEach((row,i)=>{
        const tr=node('tr'),cell=node('td'),button=node('button',row.preview??t('ui.bc63a54b1812', "상세 보기"),'app-inline-link app-table-description');button.type='button';
        if(row.previewTruncated)button.textContent+='…';button.title=button.textContent;button.addEventListener('click',()=>detail(row.id));cell.append(button);
        tr.append(node('td',String((page-1)*10+i+1)),cell,node('td',row.dimensions===null?'NULL':`${row.dimensions} · ${row.format}`),node('td',row.distance===null?'—':String(row.distance)));
        find('rows').append(tr);
      });
      if(!items.length){const tr=node('tr'),td=node('td',t('ui.9deb69ea1e78', "조회된 데이터가 없습니다."),'app-empty');td.colSpan=4;tr.append(td);find('rows').append(tr);}renderPager();
    }
    async function browse(page=1) {
      if(busy)return;const target=selection();if(!target.vector){message(t('ui.d38f872a0008', "벡터 컬럼을 선택해 주세요."));return;}
      setBusy(true);message('');results=null;renderResults();find('status').textContent=t('ui.a881c137a219', "데이터 조회 중…");
      try{results=await api('/rows',{...target,page});resultSelection=target;localPage=1;renderResults();}
      catch(error){message(error.message);}finally{find('status').textContent='';setBusy(false);}
    }
    async function detail(id) {
      const version=++detailVersion,dialog=find('detail'),body=find('detail-body');body.replaceChildren(node('p',t('ui.285ad9c90cec', "조회 중…")));if(!dialog.open)dialog.showModal();
      try{const values=await api('/detail',{...resultSelection,id});if(version!==detailVersion||!dialog.open)return;body.replaceChildren();
        values.forEach(value=>{const section=node('section',undefined,'app-execution-content');section.append(node('h3',`${value.name} · ${value.type}`),node('pre',value.supported?(value.value??'NULL'):t('ui.6f888a3fe3f3', "이 데이터 타입의 원문 조회는 지원하지 않습니다.")));
          if(value.truncated)section.append(node('p',t('ui.b2d2e7f31f9f', "100,000자를 초과한 뒷부분은 생략했습니다."),'app-muted'));body.append(section);});
      }catch(error){if(version===detailVersion&&dialog.open)body.replaceChildren(node('p',error.message,'app-alert is-error'));}
    }
    find('search-form').addEventListener('submit',async event=>{
      event.preventDefault();if(busy||!metadata)return;
      try{
        const db=metadata.options.models[Number.parseInt(find('db-model').value,10)];
        const body=searchInput(selection(),{text:find('question').value,provider:find('provider').value,modelOwner:db?.owner??'',model:find('provider').value==='database'?(db?.name??''):find('model').value.trim(),credential:find('credential').value,region:find('region').value.trim(),inputType:find('input-type').value,metric:find('metric').value,k:Number(find('k').value),externalConsent:find('consent').checked});
        setBusy(true);message('');results=null;renderResults();find('status').textContent=t('ui.1afebe290c97', "임베딩·벡터 검색 중…");
        results=await api('/search',null,body);resultSelection=body.selection;localPage=1;renderResults();
      }catch(error){message(error.message);}finally{find('status').textContent='';setBusy(false);}
    });
    find('provider').addEventListener('change',()=>{manualModel=false;find('consent').checked=false;find('model').value='';find('input-type').value='';options();});
    find('region').addEventListener('input',syncRegion);
    regionSelect.addEventListener('change',()=>{
      manualRegion=regionSelect.value===MANUAL_REGION;
      if(!manualRegion)find('region').value=regionSelect.value;
      syncRegion();
      if(manualRegion)find('region').focus();
    });
    find('model').addEventListener('input',syncPublicModel);
    publicSelect.addEventListener('change',()=>{
      manualModel=publicSelect.value===MANUAL_MODEL;
      if(!manualModel)find('model').value=publicSelect.value;
      syncPublicModel();
      if(manualModel)find('model').focus();
    });
    for(const name of ['vector','content'])find(name).addEventListener('change',()=>{results=null;renderResults();});
    find('browse').addEventListener('click',()=>browse());find('options-refresh').addEventListener('click',()=>loadMetadata(true));
    find('result-prev').addEventListener('click',()=>{if(results?.search){localPage--;renderResults();}else if(results)browse(results.page-1);});
    find('result-next').addEventListener('click',()=>{if(results?.search){localPage++;renderResults();}else if(results)browse(results.page+1);});
    find('close').addEventListener('click',()=>find('detail').close());find('detail').addEventListener('close',()=>{++detailVersion;});
    loadMetadata();
  }
}
