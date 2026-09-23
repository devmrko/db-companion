import {t} from './i18n.mjs';

const labels={
  DATA_ROLE:['dds.role','역할'],MAPPED_TO:['dds.mapping','외부 매핑'],ENABLED_BY_DEFAULT:['dds.default','기본 활성화'],
  ROLE_TYPE:['dds.roleType','역할 종류'],GRANTEE:['dds.grantee','부여 대상'],GRANTEE_TYPE:['dds.granteeType','대상 종류'],
  START_TIME:['dds.start','시작 시각'],END_TIME:['dds.end','종료 시각'],OWNER:['dds.owner','Grant 소유자'],
  GRANT_NAME:['dds.grant','Grant 이름'],OBJECT_OWNER:['dds.objectOwner','대상 스키마'],OBJECT_NAME:['dds.object','대상 객체'],
  OBJECT_TYPE:['dds.objectType','객체 종류'],PRIVILEGE:['dds.privilege','권한'],COLUMN_NAME:['dds.column','컬럼'],
  GRANTED_WITH_ALL_COLUMNS_EXCEPT:['dds.except','제외 컬럼'],PREDICATE:['dds.predicate','조건식'],
  USE_DATA_GRANTS_ONLY:['dds.only','Data Grant만 적용'],CROSS_TABLE_DATA_GRANT:['dds.cross','Cross-table Grant'],
  INVALID_COLUMN_NAME:['dds.invalidColumn','무효 컬럼'],APPLICATION_NAME:['dds.application','애플리케이션'],
  APPLICATION_ID:['dds.applicationId','애플리케이션 ID'],CROSS_TABLE_OBJECT_OWNER:['dds.parentOwner','부모 스키마'],
  CROSS_TABLE_OBJECT_NAME:['dds.parentObject','부모 객체'],CROSS_TABLE_OBJECT_TYPE:['dds.parentType','부모 객체 종류']
};
export const fieldLabel=key=>labels[key]?t(...labels[key]):key;
export function rowsPage(rows,query='',requestedPage=1){
  const term=query.trim().toLowerCase(),filtered=rows.filter(row=>Object.values(row).some(value=>String(value??'').toLowerCase().includes(term)));
  const pages=Math.ceil(filtered.length/10),page=Math.max(1,Math.min(requestedPage,pages||1)),start=(page-1)*10;
  return {rows:filtered.slice(start,start+10),page,pages,total:filtered.length,from:filtered.length?start+1:0,to:Math.min(start+10,filtered.length)};
}
export const rowKey=row=>JSON.stringify(Object.entries(row));
export function selection(kind,row){
  if(kind==='roles')return {kind,name:row.DATA_ROLE};
  if(kind==='grants')return {kind,name:row.GRANT_NAME,owner:row.OWNER};
  if(kind==='applications')return {kind,name:row.APPLICATION_NAME};
  return null;
}
export function stateMessage(data){
  if(data.status==='AVAILABLE')return '';
  if(data.status==='ACCESS_REQUIRED')return t('dds.access','조회 권한 또는 DB 지원 여부를 확인해 주세요.');
  if(data.status==='LIMIT')return t('dds.limit','조회 범위가 5,000행을 초과했습니다. 부분 목록은 표시하지 않습니다.');
  return t('dds.error','보안 정보를 조회하지 못했습니다.');
}
const node=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text??'—';if(cls)e.className=cls;return e;};
const display=value=>value===null||value===undefined?'—':String(value);
const sectionTitle=kind=>({properties:t('dds.properties','속성'),assignments:t('dds.assignments','역할 부여'),grants:'Data Grant',parents:t('dds.parents','부모 객체 필요 권한')})[kind]??kind;

if(typeof document!=='undefined')document.querySelectorAll('[data-dds]').forEach(root=>{
  const get=name=>root.querySelector(`[data-dds-${name}]`),tabs=[...root.querySelectorAll('[data-dds-tab]')];
  let kind='roles',data=null,page=1,current='',listVersion=0,detailVersion=0,listPending,detailPending;
  const cache=new Map();
  async function api(path,params,signal){
    const url=new URL(root.dataset.base+path,location.origin);url.searchParams.set('schema',root.dataset.schema);
    for(const [key,value] of Object.entries(params))url.searchParams.set(key,value);
    const response=await fetch(url,{cache:'no-store',headers:{Accept:'application/json'},signal});
    if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1','로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(t('ui.1930407cf479','응답을 확인하지 못했습니다. HTTP {0}',response.status));
    const body=await response.json();if(!response.ok)throw new Error(body.error||`HTTP ${response.status}`);return body;
  }
  function status(host,value){
    const message=stateMessage(value);host.replaceChildren();
    host.append(node('span',value.source,'app-dds-source'));
    if(message)host.append(node('p',message,'app-alert is-error'));
    if(value.error)host.append(node('pre',value.error,'app-dds-error'));
    if(value.status==='ACCESS_REQUIRED'){
      const help=node('details',undefined,'app-dds-access');help.append(node('summary',t('dds.accessHelp','조회 권한 ?')),
        node('p',t('dds.accessText','DB 관리자에게 해당 SYS 뷰의 조회 가능 여부를 확인하세요. ORA-00942는 뷰 미지원과 권한 부족 모두에서 발생할 수 있습니다. 권한은 자동 부여하지 않습니다.')));host.append(help);
    }
  }
  function table(columns,rows,onSelect){
    const result=node('table',undefined,'table app-table app-dds-table'),head=node('thead'),heading=node('tr');
    columns.forEach(key=>{const th=node('th',fieldLabel(key));th.scope='col';th.title=key;heading.append(th);});head.append(heading);result.append(head);
    const body=node('tbody');rows.forEach(row=>{
      const tr=node('tr');if(rowKey(row)===current)tr.className='is-selected';
      columns.forEach((key,index)=>{
        const cell=node('td'),text=display(row[key]);
        if(index===0&&onSelect){const button=node('button',text,'app-dds-link');button.type='button';button.title=text;button.addEventListener('click',()=>onSelect(row));cell.append(button);}
        else {const span=node('span',text,'app-dds-cell');span.title=text;cell.append(span);}tr.append(cell);
      });body.append(tr);
    });result.append(body);return result;
  }
  function render(){
    const result=rowsPage(data?.rows??[],get('filter').value,page);page=result.page;
    get('list').replaceChildren();if(data?.status==='AVAILABLE'){
      get('list').append(table(data.columns,result.rows,select));
      if(!result.total)get('list').append(node('p',t('dds.empty','조회된 항목이 없습니다.'),'app-empty'));
    }
    get('count').textContent=t('ui.f32c9f13d498','{0}–{1} / {2}개',result.from,result.to,result.total);
    get('page').textContent=`${result.total?page:0} / ${result.pages}`;
    get('prev').disabled=page<=1;get('next').disabled=page>=result.pages;
  }
  function details(sections,title){
    const host=get('detail');host.replaceChildren(node('h2',title,'app-object-title'));
    for(const section of sections){
      const part=node('section',undefined,'app-dds-section');part.append(node('h3',sectionTitle(section.kind)));
      if(section.kind==='grants')part.append(node('p',t('dds.schemaScope','{0} · 대상 스키마',root.dataset.schema),'app-filter-message'));
      const state=node('div');status(state,section.data);part.append(state);
      if(section.data.status==='AVAILABLE'){
        if(!section.data.rows.length)part.append(node('p',t('dds.empty','조회된 항목이 없습니다.'),'app-filter-message'));
        section.data.rows.forEach((row,index)=>{
          const record=node('details',undefined,'app-dds-record');record.open=section.data.rows.length===1;
          record.append(node('summary',[row.GRANT_NAME??row.DATA_ROLE??row.APPLICATION_NAME??`${index+1}`,row.PRIVILEGE,row.COLUMN_NAME,row.GRANTEE].filter(Boolean).join(' · ')));
          const values=node('dl');section.data.columns.forEach(key=>{const dt=node('dt',fieldLabel(key));dt.title=key;values.append(dt,node('dd',display(row[key])));});record.append(values);part.append(record);
        });
      }host.append(part);
    }
  }
  async function select(row){
    current=rowKey(row);render();detailPending?.abort();detailPending=new AbortController();const version=++detailVersion;
    const host=get('detail');host.hidden=false;host.setAttribute('aria-busy','true');host.replaceChildren(node('p',t('ui.8bf609c884ca','불러오는 중…'),'app-empty'));
    const params=selection(kind,row),title=row.GRANT_NAME??row.DATA_ROLE??row.APPLICATION_NAME??'';
    try{
      if(!params){details([{kind:'assignments',data:{...data,rows:[row]}}],title);return;}
      const body=await api('/detail',params,detailPending.signal);if(version===detailVersion)details(body.sections,title);
    }catch(ex){if(ex.name!=='AbortError'&&version===detailVersion)host.replaceChildren(node('p',ex.message,'app-alert is-error'));}
    finally{if(version===detailVersion)host.setAttribute('aria-busy','false');}
  }
  async function list(refresh=false){
    const version=++listVersion,listKind=kind;listPending?.abort();listPending=new AbortController();
    detailPending?.abort();++detailVersion;current='';get('detail').hidden=true;
    data=null;page=1;render();get('refresh').disabled=true;get('status').replaceChildren(node('span',t('ui.8bf609c884ca','불러오는 중…')));
    get('scope').textContent=kind==='grants'?t('dds.schemaScope','{0} · 대상 스키마',root.dataset.schema):t('dds.globalScope','DB 공통 · 로그인 계정 조회 범위');
    if(refresh)cache.clear();
    try{
      const value=cache.get(listKind)??await api('/list',{kind:listKind,refresh},listPending.signal);if(version!==listVersion)return;
      if(['AVAILABLE','ACCESS_REQUIRED'].includes(value.status))cache.set(listKind,value);data=value;status(get('status'),data);render();
    }catch(ex){if(ex.name!=='AbortError'&&version===listVersion)get('status').replaceChildren(node('p',ex.message,'app-alert is-error'));}
    finally{if(version===listVersion)get('refresh').disabled=false;}
  }
  tabs.forEach(button=>button.addEventListener('click',()=>{
    if(kind===button.dataset.ddsTab)return;kind=button.dataset.ddsTab;get('filter').value='';
    tabs.forEach(tab=>tab.setAttribute('aria-pressed',String(tab===button)));list();
  }));
  get('filter').addEventListener('input',()=>{page=1;render();});get('prev').addEventListener('click',()=>{page--;render();});get('next').addEventListener('click',()=>{page++;render();});
  get('refresh').addEventListener('click',()=>list(true));list();
});
