import {t} from './i18n.mjs';
import {pageOf} from './table-list.mjs';
import {canMountLink,mountDialog,catalogExplorer} from './catalog-operations.mjs';

export const entryKey=entry=>JSON.stringify(entry.acl?[entry.acl,entry.mapping??null]:[entry.owner,entry.name]);
export function aclEntries(items){return items.map(acl=>({name:acl.host,owner:acl.principal,description:[acl.privilege,acl.grantType,acl.status,acl.lowerPort,acl.upperPort,acl.invertedPrincipal,acl.principalType].filter(Boolean).join(' '),acl}));}
export function schemaAclEntries(items){return items.map(mapping=>({...aclEntries([mapping.ace])[0],mapping,description:[mapping.schema,...mapping.path,mapping.ace.privilege,mapping.ace.grantType,mapping.ace.lowerPort,mapping.ace.upperPort].filter(Boolean).join(' ')}));}
export function catalogEntries(items,owner){return items.map(catalog=>({name:catalog.name,owner,description:[catalog.type,catalog.enabled,catalogEnabled(catalog.enabled)].filter(Boolean).join(' '),catalog}));}
export function catalogEnabled(value){
  const normalized=String(value??'').toUpperCase();
  return ['YES','Y','TRUE','1','ENABLED'].includes(normalized)?t('catalog.enabled','활성'):['NO','N','FALSE','0','DISABLED'].includes(normalized)?t('catalog.disabled','비활성'):value||'—';
}
export function catalogApiMessage(api){
  if(!api)return '';
  const state=api.status==='VISIBLE'?t('catalog.apiVisible','API 메타데이터 확인됨'):api.status==='NOT_VISIBLE'?t('catalog.apiUnknown','API 지원·권한 확인 필요'):resultMessage(api);
  return ['DBMS_CATALOG',state,...(api.methods??[])].filter(Boolean).join(' · ');
}
export function externalState(search){const params=new URLSearchParams(search);return {kind:['links','tables','acl','catalogs'].includes(params.get('tab'))?params.get('tab'):'links',aclView:params.get('aclView')==='all'?'all':'schema'};}
export function externalReturn(kind,aclView){return `/db/external-sources?tab=${['links','tables','acl','catalogs'].includes(kind)?kind:'links'}&aclView=${aclView==='all'?'all':'schema'}`;}
export const portRange=(lower,upper)=>lower==null||lower===''?'—':upper&&upper!==lower?`${lower}–${upper}`:lower;
export function externalPage(items,term,page){
  return pageOf(items.map(item=>({...item,description:[item.owner,item.description].filter(Boolean).join(' ')})),term,page);
}
// Used for both list/tab changes and detail selection: stale responses cannot restore old content.
export function requestGate(){
  let version=0,controller;
  return {start(){controller?.abort();controller=new AbortController();const stamp=++version;return {signal:controller.signal,current:()=>stamp===version};},cancel(){controller?.abort();version++;}};
}
export function resultMessage(data){
  const labels={ACCESS_REQUIRED:t('external.access','뷰가 없거나 조회 권한이 없습니다.'),UNSUPPORTED:t('external.unsupported','이 DB에서 필요한 메타데이터를 제공하지 않습니다.'),LIMIT:t('external.limit','조회 범위가 5,000건을 넘었습니다.'),ERROR:t('external.error','외부 데이터 소스 조회 오류'),MAPPING_UNAVAILABLE:t('external.mappingUnavailable','부여 경로를 확인할 수 없습니다. 전체 ACL에서 로그인 계정 범위를 확인하세요.'),PARTIAL:t('external.partial','일부 경로만 확인했습니다.')};
  return [labels[data.status],data.source,data.error].filter(Boolean).join(' · ');
}
const node=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text??'—';if(cls)e.className=cls;return e;};
function table(labels,rows){
  const wrap=node('div',undefined,'table-responsive'),grid=node('table',undefined,'table app-table'),head=node('thead'),tr=node('tr');
  labels.forEach(label=>{const th=node('th',label);th.scope='col';tr.append(th);});head.append(tr);grid.append(head);
  const body=node('tbody');rows.forEach(values=>{const row=node('tr');values.forEach(value=>{const cell=node('td');cell.append(typeof value==='object'&&value!==null?value:node('span',value||'—'));row.append(cell);});body.append(row);});grid.append(body);wrap.append(grid);return wrap;
}
const label=key=>t('external.'+key,key);
// Keep API terms recognizable; translate only the explanatory qualifier.
export function aclLabel(key,translate=label){
  if(key==='host')return 'Host';
  if(key==='privilege')return 'Privilege';
  if(key==='principal')return `Principal (${translate('schema')} / ${translate('route.ROLE')})`;
  return translate(key);
}
if(typeof document!=='undefined')document.querySelectorAll('[data-external]').forEach(root=>{
  const get=key=>root.querySelector(`[data-external-${key}]`),tabs=[...root.querySelectorAll('[data-external-tab]')];
  const listGate=requestGate(),detailGate=requestGate();
  let {kind,aclView}=externalState(location.search);let items=[],page=1,current=null,busy=false,disposeExplorer=()=>{};
  const register=mountDialog(root,async name=>{
    kind='catalogs';hideDetail();navigation();get('filter').value='';await list(true);
    const entry=items.find(item=>item.name===name);if(entry)await select(entry);
  });
  function navigation(){
    const path=externalReturn(kind,aclView);history.replaceState(null,'',path);
    document.querySelectorAll('input[name="returnTo"]').forEach(input=>{input.value=path;});
    tabs.forEach(tab=>tab.setAttribute('aria-pressed',String(tab.dataset.externalTab===kind)));
    get('acl-help').hidden=kind!=='acl';get('acl-mode').hidden=kind!=='acl';get('acl-mode').value=aclView;
    get('catalog-help').hidden=kind!=='catalogs';get('capabilities').hidden=kind!=='catalogs';get('capabilities').textContent='';
    get('scope').textContent=kind==='catalogs'||kind==='acl'&&aclView==='all'?get('scope').dataset.login:root.dataset.schema;
    get('filter').placeholder=kind==='catalogs'?t('catalog.filter','카탈로그명·종류·상태 검색'):kind==='acl'?'Host · Principal · Privilege':label('filter');get('filter').setAttribute('aria-label',get('filter').placeholder);
    root.querySelector('label[for="external-filter"]').textContent=get('filter').placeholder;
  }
  async function api(path,params,signal){
    const url=new URL(root.dataset.base+path,location.origin);url.searchParams.set('schema',root.dataset.schema);url.searchParams.set('kind',kind);
    Object.entries(params).forEach(([key,value])=>url.searchParams.set(key,value));
    const response=await fetch(url,{cache:'no-store',headers:{Accept:'application/json'},signal});
    if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1','로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(t('ui.1930407cf479','응답을 확인하지 못했습니다. HTTP {0}',response.status));
    const data=await response.json();if(!response.ok)throw new Error(data.error||`HTTP ${response.status}`);return data;
  }
  function render(){
    const result=externalPage(items,get('filter').value,page);page=result.page;
    const labels=kind==='catalogs'?[t('catalog.name','카탈로그명'),t('catalog.type','종류'),t('catalog.activation','활성 상태')]:kind==='acl'?(aclView==='schema'?['schema','host','ports','privilege','route','grantType']:['host','ports','principal','privilege','grantType']).map(key=>aclLabel(key)):[label('name'),label('owner'),label(kind==='links'?'username':'driver')];
    get('list').replaceChildren(table(labels,result.items.map(item=>{
      const button=node('button',item.name,'app-credential-link');button.type='button';button.title=item.name;button.disabled=busy;
      button.setAttribute('aria-pressed',String(Boolean(current&&entryKey(item)===entryKey(current))));
      button.addEventListener('click',()=>select(item));
      if(item.catalog)return [button,item.catalog.type,catalogEnabled(item.catalog.enabled)];
      if(item.mapping)return [item.mapping.schema,button,portRange(item.acl.lowerPort,item.acl.upperPort),item.acl.privilege,routeText(item.mapping),item.acl.grantType];
      if(item.acl)return [button,portRange(item.acl.lowerPort,item.acl.upperPort),item.acl.principal,item.acl.privilege,item.acl.grantType||item.acl.status];
      // Use original description, not the combined search-only projection.
      return [button,item.owner,items.find(e=>entryKey(e)===entryKey(item))?.description];
    })));
    if(!result.total&&items.length)get('list').append(node('p',label('noMatches'),'app-empty'));
    get('count').textContent=t('ui.f32c9f13d498','{0}–{1} / {2}개',result.from,result.to,result.total);
    get('page').textContent=`${result.pages?page:0} / ${result.pages}`;get('prev').disabled=page<=1;get('next').disabled=page>=result.pages;
    get('refresh').disabled=busy;
  }
  function hideDetail(){disposeExplorer();detailGate.cancel();current=null;get('detail').hidden=true;get('detail').replaceChildren();get('detail').removeAttribute('aria-busy');}
  function routeText(mapping){return label('route.'+mapping.route)+(mapping.route.includes('ROLE')?' · '+mapping.path.join(' → '):'');}
  function showReview(aces=[]){
    const host=get('review');host.replaceChildren();if(!aces.length)return;
    const details=node('details'),summary=node('summary',t('external.review','추가 판정이 필요한 규칙 ({0})',aces.length));
    details.append(summary,node('p',label('reviewHelp'),'app-filter-message'));
    const grid=node('div'),controls=node('div',undefined,'app-dds-pages'),prev=node('button',t('ui.da7e61c67cc5','이전'),'btn app-btn app-btn-quiet'),next=node('button',t('ui.aef613c6612d','다음'),'btn app-btn app-btn-quiet'),count=node('span');
    prev.type=next.type='button';let requested=1;const entries=aclEntries(aces);
    function draw(){const result=externalPage(entries,'',requested);requested=result.page;
      grid.replaceChildren(table(['host','ports','principal','privilege','grantType','invertedPrincipal'].map(key=>aclLabel(key)),result.items.map(item=>[item.acl.host,portRange(item.acl.lowerPort,item.acl.upperPort),item.acl.principal,item.acl.privilege,item.acl.grantType,item.acl.invertedPrincipal])));
      count.textContent=`${result.from}–${result.to} / ${result.total}`;prev.disabled=requested<=1;next.disabled=requested>=result.pages;
    }
    prev.addEventListener('click',()=>{requested--;draw();});next.addEventListener('click',()=>{requested++;draw();});controls.append(count,prev,next);details.append(grid,controls);
    details.addEventListener('toggle',()=>{if(details.open)draw();});host.append(details);
  }
  async function list(refresh=false){
    const request=listGate.start(),selected=refresh?current:null;hideDetail();items=[];page=1;busy=true;render();
    showReview();get('capabilities').textContent='';
    get('message').textContent=t('ui.8bf609c884ca','불러오는 중…');
    try{
      const data=await api(kind==='catalogs'?'/catalogs':kind==='acl'?(aclView==='schema'?'/acl/schema':'/acl'):'/list',{refresh},request.signal);if(!request.current())return;
      items=kind==='catalogs'?catalogEntries(data.items,get('scope').dataset.login):kind==='acl'?(aclView==='schema'?schemaAclEntries(data.items):aclEntries(data.items)):data.items;
      get('scope').textContent=kind==='catalogs'?`${get('scope').dataset.login} · ${t('catalog.loginScope','로그인 계정')}`:kind==='acl'?(aclView==='schema'?`${root.dataset.schema} · ${label('selectedSchema')}`:`${get('scope').dataset.login} · ${label(data.scope==='USER'?'userScope':'dbScope')}`):root.dataset.schema;
      get('message').textContent=data.status==='AVAILABLE'?(items.length?data.source:label(kind==='acl'&&aclView==='schema'?'noMappedRules':'empty')):resultMessage(data);
      if(kind==='catalogs'){
        get('capabilities').textContent=catalogApiMessage(data.api);
        if(data.status==='AVAILABLE')get('message').textContent=[t('catalog.readable','등록 정보 조회 가능'),items.length?data.source:t('catalog.empty','등록된 카탈로그가 없습니다.')].join(' · ');
      }
      if(kind==='acl'&&aclView==='schema'){
        showReview(data.review);
        if(data.roleStatus&&!['AVAILABLE','NOT_READ'].includes(data.roleStatus))get('message').textContent+=' · '+label('rolesUnchecked')+' · '+data.roleStatus;
      }
      busy=false;render();
      const refreshed=selected&&items.find(item=>entryKey(item)===entryKey(selected));if(refreshed)await select(refreshed);
    }catch(ex){if(request.current()&&ex.name!=='AbortError')get('message').textContent=ex.message;}
    finally{if(request.current()){busy=false;render();}}
  }
  function show(data){
    const host=get('detail');host.replaceChildren(node('h2',`${data.entry.owner}.${data.entry.name}`,'app-object-title'));
    host.append(table([label('field'),label('value')],data.fields.filter(field=>field.key!=='alias'||field.value).filter(field=>field.key!=='endpointStatus'||field.value!=='PARSED')
      .map(field=>[label(field.key),field.key==='endpointStatus'?label('endpoint.'+field.value):field.value])));
    if(kind==='links'){
      const actions=node('div',undefined,'app-assistant-actions'),button=node('button',t('catalogOps.register','카탈로그 등록'),'btn app-btn app-btn-primary');button.type='button';
      button.disabled=!canMountLink(get('scope').dataset.login,data.entry.owner);
      if(button.disabled)button.title=t('catalogOps.ownerRequired','로그인 계정 소유 또는 PUBLIC DB Link만 등록할 수 있습니다.');
      button.addEventListener('click',()=>register(data.entry));actions.append(button);host.append(actions);
    }
    if(!data.sections.length)return;
    host.append(node('h3',label('locations'),'app-attribute-title'));
    const available=data.sections.filter(section=>section.status==='AVAILABLE');
    const locations=available.flatMap(section=>section.items);
    if(locations.length)host.append(table([label('partition'),label('subpartition'),label('directory'),label('location')],locations.map(item=>[item.partition,item.subpartition,item.directory,item.location])));
    else if(available.length===data.sections.length)host.append(node('p',label('noLocations'),'app-filter-message'));
    for(const section of data.sections.filter(section=>section.status!=='AVAILABLE'))host.append(node('p',resultMessage(section),'app-alert is-warning'));
  }
  function showCatalog(data){
    const host=get('detail');host.replaceChildren(node('h2',data.entry.name,'app-object-title'));
    host.append(table([label('field'),label('value')],[
      [t('catalog.type','종류'),data.entry.type],[t('catalog.activation','활성 상태'),catalogEnabled(data.entry.enabled)],
      ...data.fields.map(field=>[t('catalog.field.'+field.key,field.key),field.value])
    ]));
    host.append(node('p',data.source,'app-filter-message'));
    disposeExplorer=catalogExplorer(host,data.entry,root.dataset.base);
  }
  async function select(entry){
    disposeExplorer();
    const request=detailGate.start();current=entry;render();const host=get('detail');host.hidden=false;host.setAttribute('aria-busy','true');host.replaceChildren(node('p',t('ui.8bf609c884ca','불러오는 중…'),'app-empty'));
    if(entry.acl){
      const pathRows=entry.mapping?[[label('schema'),entry.mapping.schema],[label('route'),routeText(entry.mapping)],[label('defaultRole'),entry.mapping.defaultRole]]:[];
      host.replaceChildren(node('h2',entry.name,'app-object-title'),table([label('field'),label('value')],[...pathRows,...Object.entries(entry.acl).map(([key,value])=>[aclLabel(key),value]) ]));host.setAttribute('aria-busy','false');return;
    }
    try{const data=await api(entry.catalog?'/catalogs/detail':'/detail',{owner:entry.owner,name:entry.name},request.signal);if(request.current()){if(entry.catalog)showCatalog(data);else show(data);}}
    catch(ex){if(request.current()&&ex.name!=='AbortError')host.replaceChildren(node('p',ex.message,'app-alert is-error'));}
    finally{if(request.current())host.setAttribute('aria-busy','false');}
  }
  tabs.forEach(tab=>tab.addEventListener('click',()=>{
    if(kind===tab.dataset.externalTab)return;kind=tab.dataset.externalTab;
    navigation();get('filter').value='';list();
  }));
  get('acl-mode').addEventListener('change',()=>{aclView=get('acl-mode').value;navigation();get('filter').value='';list();});
  get('refresh').addEventListener('click',()=>{if(!busy)list(true);});
  get('filter').addEventListener('input',()=>{page=1;render();});get('prev').addEventListener('click',()=>{page--;render();});get('next').addEventListener('click',()=>{page++;render();});
  navigation();list();
});
