import {t} from './i18n.mjs';
import {pageOf} from './table-list.mjs';

export function credentialPage(items,term,page){
  return pageOf(items.map(item=>({...item,description:item.owner})),term,page);
}
export async function refreshCredentials(loadList,loadDetail,name){
  const data=await loadList(true);
  if(!data||data.status!=='AVAILABLE')return false;
  if(name&&data.items.some(item=>item.name===name))await loadDetail(name);
  return true;
}
const node=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text??'—';if(cls)e.className=cls;return e;};
const enabled=value=>value==='TRUE'?t('credentials.enabled','활성'):value==='FALSE'?t('credentials.disabled','비활성'):(value||'—');
export function resultMessage(data){
  const labels={ACCESS_REQUIRED:t('credentials.access','뷰가 없거나 조회 권한이 없습니다.'),UNSUPPORTED:t('credentials.unsupported','이 DB에서는 필요한 조회 컬럼을 제공하지 않습니다.'),LIMIT:t('credentials.limit','조회 범위가 5,000건을 넘었습니다. 일부 결과를 전체로 표시하지 않습니다.'),ERROR:t('credentials.error','Credential 조회 오류')};
  return [labels[data.status],data.source,data.error].filter(Boolean).join(' · ');
}
function table(labels,rows){
  const wrap=node('div',undefined,'table-responsive'),table=node('table',undefined,'table app-table'),head=node('thead'),tr=node('tr');
  labels.forEach(label=>{const th=node('th',label);th.scope='col';tr.append(th);});head.append(tr);table.append(head);
  const body=node('tbody');rows.forEach(values=>{const row=node('tr');values.forEach(value=>{const cell=node('td');cell.append(typeof value==='object'&&value!==null?value:node('span',value));row.append(cell);});body.append(row);});table.append(body);wrap.append(table);return wrap;
}
if(typeof document!=='undefined')document.querySelectorAll('[data-credentials]').forEach(root=>{
  const get=key=>root.querySelector(`[data-credentials-${key}]`);
  let items=[],page=1,current='',version=0,pending=null,refreshing=false;
  async function api(path,params,signal){
    const url=new URL(root.dataset.base+path,location.origin);url.searchParams.set('schema',root.dataset.schema);
    Object.entries(params).forEach(([key,value])=>url.searchParams.set(key,value));
    const response=await fetch(url,{cache:'no-store',headers:{Accept:'application/json'},signal});
    if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1','로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(t('ui.1930407cf479','응답을 확인하지 못했습니다. HTTP {0}',response.status));
    const data=await response.json();if(!response.ok)throw new Error(data.error||`HTTP ${response.status}`);return data;
  }
  function render(){
    const result=credentialPage(items,get('filter').value,page);page=result.page;
    const rows=result.items.map(item=>{
      const button=node('button',item.name,'app-credential-link');button.type='button';button.title=item.name;button.disabled=refreshing;
      button.setAttribute('aria-pressed',String(item.name===current));button.addEventListener('click',()=>select(item.name));
      return [button,item.owner,enabled(item.enabled)];
    });
    get('list').replaceChildren(table([t('credentials.name','이름'),t('credentials.owner','소유자'),t('credentials.status','상태')],rows));
    if(!result.total&&items.length)get('list').append(node('p',t('credentials.noMatches','검색 결과가 없습니다.'),'app-empty'));
    get('count').textContent=t('ui.f32c9f13d498','{0}–{1} / {2}개',result.from,result.to,result.total);
    get('page').textContent=`${result.pages?page:0} / ${result.pages}`;get('prev').disabled=page<=1;get('next').disabled=page>=result.pages;
  }
  async function list(refresh=false){
    get('message').textContent=t('ui.8bf609c884ca','불러오는 중…');get('refresh').disabled=true;
    try{
      const data=await api('/list',{refresh});items=data.items;page=1;render();
      get('message').textContent=data.status==='AVAILABLE'?(items.length?data.source:t('credentials.empty','조회된 Credential이 없습니다.')):resultMessage(data);
      return data;
    }catch(ex){items=[];render();get('message').textContent=ex.message;return null;}
    finally{if(!refreshing)get('refresh').disabled=false;}
  }
  function show(data){
    const host=get('detail');host.replaceChildren();const entry=data.credential;
    host.append(node('h2',`${entry.owner}.${entry.name}`,'app-object-title'));
    const format=value=>value==='EMPTY'?t('credentials.formatEmpty','없음'):value==='TEXT'?t('credentials.formatText','일반 텍스트'):value;
    host.append(table([t('credentials.field','항목'),t('credentials.value','값')],[
      [t('credentials.owner','소유자'),entry.owner],[t('credentials.status','상태'),enabled(entry.enabled)],
      [t('credentials.usernameFormat','사용자 정보 형식'),format(entry.usernameFormat)],
      [t('credentials.commentsFormat','설명 정보 형식'),format(entry.commentsFormat)]
    ]));
    host.append(node('h3',t('credentials.usages','사용처'),'app-attribute-title'));
    const grid=node('div',undefined,'app-credential-uses');
    for(const section of data.sections){
      const block=node('section');block.append(node('h4',t('credentials.'+section.kind,section.kind),'app-attribute-title'));
      if(section.status!=='AVAILABLE')block.append(node('p',resultMessage(section),'app-alert is-warning'));
      else if(!section.items.length)block.append(node('p',t('credentials.noReferences','이 범위에서 확인된 참조가 없습니다.'),'app-filter-message'));
      else block.append(table([t('credentials.name','이름'),t('credentials.reference','참조 항목')],section.items.map(item=>[`${item.owner}.${item.name}`,item.reference])));
      block.append(node('small',section.source,'app-filter-message'));grid.append(block);
    }
    host.append(grid);
  }
  async function select(name){
    pending?.abort();pending=new AbortController();const stamp=++version;current=name;render();
    const host=get('detail');host.hidden=false;host.setAttribute('aria-busy','true');host.replaceChildren(node('p',t('ui.8bf609c884ca','불러오는 중…'),'app-empty'));
    try{const data=await api('/detail',{name},pending.signal);if(stamp===version)show(data);}
    catch(ex){if(ex.name!=='AbortError'&&stamp===version)host.replaceChildren(node('p',ex.message,'app-alert is-error'));}
    finally{if(stamp===version)host.setAttribute('aria-busy','false');}
  }
  get('refresh').addEventListener('click',async()=>{
    if(refreshing)return;refreshing=true;pending?.abort();++version;
    const name=current;current='';get('detail').hidden=true;get('detail').replaceChildren();render();
    try{await refreshCredentials(list,select,name);}
    finally{refreshing=false;get('refresh').disabled=false;render();}
  });
  get('filter').addEventListener('input',()=>{page=1;render();});get('prev').addEventListener('click',()=>{page--;render();});get('next').addEventListener('click',()=>{page++;render();});
  list();
});
