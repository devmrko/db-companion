import {t} from './i18n.mjs';
import {pageOf} from './table-list.mjs';
import {mountSourceViewer,packageFunctionIndex} from './source-viewer.mjs';
import {mountFunctionExplain} from './function-explain.mjs';
export function functionUrl(base,schema,definition){
  const quote=value=>'"'+value.replaceAll('"','""')+'"';
  const reference=[definition.owner,definition.object,...(definition.member?[definition.member]:[])].map(quote).join('.');
  return base+'?'+new URLSearchParams({schema,name:reference});
}
export async function refreshFunctions(loadList,loadDetail,reference){
  if(await loadList(true)!==true)return false;
  if(reference)await loadDetail(reference);
  return true;
}
const node=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text??'—';if(cls)e.className=cls;return e;};
if(typeof document!=='undefined')document.querySelectorAll('[data-functions]').forEach(root=>{
  const get=name=>root.querySelector(`[data-functions-${name}]`);
  const explainDialog=document.querySelector('[data-function-explain]');
  const explain=explainDialog?mountFunctionExplain(explainDialog):null;
  let items=[],page=1,current=root.dataset.reference,pending=null,generation=0,listVersion=0,listPending=null,listError='',refreshing=false;
  async function api(path,params,signal){
    const url=new URL(root.dataset.base+path,location.origin);url.searchParams.set('schema',root.dataset.schema);
    Object.entries(params).forEach(([k,v])=>url.searchParams.set(k,v));
    const response=await fetch(url,{cache:'no-store',headers:{Accept:'application/json'},signal});
    if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1','로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(t('ui.1930407cf479','응답을 확인하지 못했습니다. HTTP {0}',response.status));
    const data=await response.json();if(!response.ok)throw new Error(data.error||`HTTP ${response.status}`);return data;
  }
  function renderList(){
    const result=pageOf(items,get('filter').value,page);page=result.page;get('list').replaceChildren();
    for(const item of result.items){
      const link=node('a',undefined,'app-function-item');link.href=root.dataset.base+'?'+new URLSearchParams({schema:root.dataset.schema,name:item.reference});link.title=item.name;
      link.append(node('span',item.name),node('small',item.member?'PACKAGE':'FUNCTION'));
      if(item.reference===current)link.setAttribute('aria-current','true');
      link.addEventListener('click',event=>{if(event.ctrlKey||event.metaKey||event.shiftKey||event.altKey)return;event.preventDefault();if(!refreshing)select(item.reference);});get('list').append(link);
    }
    get('message').textContent=listError||(result.total?'':t('functions.empty','조회된 함수가 없습니다.'));
    get('count').textContent=t('ui.f32c9f13d498','{0}–{1} / {2}개',result.from,result.to,result.total);
    get('page').textContent=`${result.total?page:0} / ${result.pages}`;get('prev').disabled=page<=1;get('next').disabled=page>=result.pages;
  }
  async function list(refresh=false){
    const version=++listVersion;listPending?.abort();listPending=new AbortController();get('refresh').disabled=true;get('message').textContent=t('ui.8bf609c884ca','불러오는 중…');
    try{const data=await api('/list',{refresh},listPending.signal);if(version!==listVersion)return false;listError='';items=data;page=1;renderList();return true;}
    catch(ex){if(ex.name!=='AbortError'&&version===listVersion){listError=ex.message;items=[];renderList();}}
    finally{if(version===listVersion&&!refreshing)get('refresh').disabled=false;}
    return false;
  }
  function show(details){
    const host=get('detail');host.replaceChildren();
    if(!details.length){host.append(node('p',t('ui.266421021813','함수·프로시저가 없거나 소스 조회 권한이 없습니다. Synonym과 DB link의 자동 해석은 지원하지 않습니다.'),'app-empty'));return;}
    if(details.length>1)host.append(node('p',t('ui.ec8647cbd4fb','동일한 이름으로 해석되는 후보가 여러 개입니다. 소유자와 패키지를 확인해 주세요.'),'app-alert is-warning'));
    for(const detail of details){
      const d=detail.definition,section=node('section',undefined,'app-function-definition'),header=node('div',undefined,'app-card-heading');
      header.append(node('h2',`${d.owner}.${d.object}${d.member?'.'+d.member:''}`,'app-object-title'),node('span',d.type,'app-badge'));section.append(header);
      if(explain&&details.length===1&&d.sections.length){
        const button=node('button',t('assistant.explain','AI 설명'),'btn app-btn app-btn-secondary');button.type='button';
        const reference=new URL(functionUrl(root.dataset.base,root.dataset.schema,d),location.origin).searchParams.get('name');
        button.addEventListener('click',()=>explain(root.dataset.schema,reference));header.append(button);
      }
      const meta=node('div',undefined,'app-function-meta');
      detail.objects.forEach(object=>{const line=node('div');line.append(node('span',object.type),node('span',object.status,'app-badge'),node('span',`${t('functions.ddlTime','최종 DDL (DB)')} · ${object.modified??'—'}`));meta.append(line);});section.append(meta);
      const args=node('details',undefined,'app-function-arguments');args.open=true;args.append(node('summary',t('functions.arguments','인자·반환값')));
      if(!detail.arguments.length)args.append(node('p',t('functions.noArguments','인자 정보가 없습니다.'),'app-filter-message'));
      const groups=Map.groupBy(detail.arguments,arg=>arg.subprogramId);
      for(const [id,values] of groups){
        if(groups.size>1)args.append(node('h3',t('functions.overload','오버로드 {0} · ID {1}',values[0].overload??'—',id),'app-attribute-title'));
        const scroll=node('div',undefined,'table-responsive'),table=node('table',undefined,'table app-table'),head=node('thead'),tr=node('tr');
        for(const label of [t('functions.argument','인자'),t('functions.type','타입'),'IN / OUT',t('functions.default','기본값')]){const th=node('th',label);th.scope='col';tr.append(th);}head.append(tr);table.append(head);
        const body=node('tbody');for(const arg of values){const row=node('tr');[arg.position===0?t('functions.return','반환값'):arg.name,arg.type,arg.position===0?'—':arg.direction,arg.defaulted?t('functions.yes','있음'):'—'].forEach(value=>row.append(node('td',value)));body.append(row);}table.append(body);scroll.append(table);args.append(scroll);
      }section.append(args);
      if(d.member)section.append(node('p',t('functions.packageSource','패키지 전체 명세·본문입니다. 같은 이름의 오버로드는 소스 검색으로 확인하세요.'),'app-filter-message'));
      host.append(section);
      if(d.sections.length)mountSourceViewer(section,d.sections,d.member,{
        owner:d.owner,index:packageFunctionIndex(root.dataset.schema,items),
        href:reference=>root.dataset.base+'?'+new URLSearchParams({schema:root.dataset.schema,name:reference})
      });
      else section.append(node('p',t('ui.6d893718182f','객체는 확인되었으나 소스가 공개되지 않았거나 조회 권한이 없습니다.'),'app-empty'));
    }
  }
  async function select(reference,replace=true){
    pending?.abort();pending=new AbortController();const version=++generation;current=reference;renderList();
    get('detail').replaceChildren(node('p',t('ui.8bf609c884ca','불러오는 중…'),'app-empty'));get('detail').setAttribute('aria-busy','true');
    if(replace){
      const url=root.dataset.base+'?'+new URLSearchParams({schema:root.dataset.schema,name:reference});history.replaceState(null,'',url);
      document.querySelectorAll('[data-language-return]').forEach(field=>{field.value=url;});
    }
    try{const data=await api('/detail',{name:reference},pending.signal);if(version===generation)show(data);}
    catch(ex){if(ex.name!=='AbortError'&&version===generation)get('detail').replaceChildren(node('p',ex.message,'app-alert is-error'));}
    finally{if(version===generation)get('detail').setAttribute('aria-busy','false');}
  }
  get('refresh').addEventListener('click',async()=>{
    if(refreshing)return;refreshing=true;get('refresh').disabled=true;pending?.abort();++generation;
    get('detail').replaceChildren(node('p',t('ui.8bf609c884ca','불러오는 중…'),'app-empty'));
    get('detail').setAttribute('aria-busy','true');
    try{
      const ok=await refreshFunctions(list,select,current);
      if(!ok)get('detail').replaceChildren(node('p',listError||t('functions.loadError','함수 정보를 조회하지 못했습니다. 조회 권한을 확인해 주세요.'),'app-alert is-error'));
      else if(!current)get('detail').replaceChildren(node('p',t('functions.choose','함수를 선택하세요.'),'app-empty'));
    }finally{refreshing=false;get('refresh').disabled=false;get('detail').setAttribute('aria-busy','false');}
  });
  get('filter').addEventListener('input',()=>{page=1;renderList();});get('prev').addEventListener('click',()=>{page--;renderList();});get('next').addEventListener('click',()=>{page++;renderList();});
  const initial=current;
  list().then(()=>{if(initial&&generation===0)select(initial,false);});
});
