import {t} from './i18n.mjs';
import {pageOf} from './table-list.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';

const label=key=>t('catalogOps.'+key,key);
const el=(tag,text,cls)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text??'—';if(cls)node.className=cls;return node;};
const button=(text,fn,cls='btn app-btn app-btn-quiet')=>{const node=el('button',text,cls);node.type='button';node.addEventListener('click',fn);return node;};
export const canMountLink=(user,owner)=>user===owner||owner==='PUBLIC';
export const catalogSuggestion=link=>('CAT_'+link.toUpperCase().replace(/[^A-Z0-9_]/g,'_')).slice(0,128);
export const validCatalogName=value=>/^[A-Z][A-Z0-9_]{0,127}$/.test(value.trim().toUpperCase())&&value.trim().toUpperCase()!=='LOCAL';
export function metadataPage(grid,term,page){return pageOf(grid.rows.map(row=>({name:Object.values(row).filter(v=>v!=null).join(' '),row})),term,page);}
export function stageGate(){let version=0;return {start:()=>++version,current:v=>v===version,cancel:()=>{version++;}};}
export const isCommentField=field=>['COMMENTS','DESCRIPTION','SCHEMA_DESCRIPTION','TABLE_DESCRIPTION'].includes(field);
export function metadataFields(data,level){
  return level==='columns'&&!data.fields.some(isCommentField)?[...data.fields,'COMMENTS']:[...data.fields];
}
export function metadataHelpKeys(data,level){
  if(level!=='columns')return ['commentHelp'];
  return ['lengthHelp','precisionHelp','scaleHelp',data.fields.some(isCommentField)?'commentHelp':'commentUnavailable'];
}

export function mountDialog(root,onRegistered){
  const get=key=>root.querySelector(`[data-catalog-${key}]`),dialog=get('dialog');
  let entry=null,preview=null,busy=false,attempted=false;
  const note=text=>{get('message').textContent=text;};
  function controls(){
    get('name').disabled=busy||attempted;get('preview').disabled=busy||attempted||!validCatalogName(get('name').value);
    get('mount').disabled=busy||attempted||!preview;get('close').disabled=busy;
  }
  function invalidate(){preview=null;get('sql').hidden=true;get('sql').textContent='';controls();}
  get('name').addEventListener('input',invalidate);
  get('close').addEventListener('click',()=>{if(!busy)dialog.close();});
  dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
  get('preview').addEventListener('click',async()=>{
    if(busy||attempted||!entry)return;preview=null;busy=true;controls();note(t('ui.8bf609c884ca','불러오는 중…'));
    try{preview=await assistantApi(root.dataset.base+'/catalogs/preview',assistantPost(get('csrf'),{owner:entry.owner,link:entry.name,catalog:get('name').value}));
      get('name').value=preview.catalog;get('sql').textContent=preview.sql;get('sql').hidden=false;note(label('confirm'));
    }catch(ex){get('sql').hidden=true;note(ex.message);}finally{busy=false;controls();}
  });
  get('mount').addEventListener('click',async()=>{
    if(busy||attempted||!preview)return;busy=true;attempted=true;controls();note(t('ui.8bf609c884ca','불러오는 중…'));
    try{
      const receipt=await assistantApi(root.dataset.base+'/catalogs/mount',assistantPost(get('csrf'),{token:preview.token}));
      // This callback refreshes registrations only. Remote metadata is another explicit action.
      if(receipt.status==='REGISTERED'){dialog.close();await onRegistered(receipt.catalog);}
      else note([label('uncertain'),receipt.error].filter(Boolean).join(' · '));
    }catch(ex){note(ex.message+' '+label('checkList'));}finally{busy=false;controls();}
  });
  return entryValue=>{
    if(busy)return;entry=entryValue;attempted=false;get('target').textContent=`${entry.owner}.${entry.name}`;
    get('name').value=catalogSuggestion(entry.name);note('');invalidate();dialog.showModal();get('name').focus();
  };
}

function metadataGrid(host,data,level,onSelect){
  host.replaceChildren();let page=1,selected=null;const fields=metadataFields(data,level);
  const filter=el('input',undefined,'form-control');filter.type='search';filter.placeholder=label('search');filter.setAttribute('aria-label',label(level)+' '+label('search'));
  const grid=el('div',undefined,'table-responsive'),controls=el('div',undefined,'app-dds-pages'),count=el('span'),pages=el('span');
  const prev=button(t('ui.da7e61c67cc5','이전'),()=>{page--;draw();}),next=button(t('ui.aef613c6612d','다음'),()=>{page++;draw();});
  const group=el('div');group.append(prev,pages,next);controls.append(count,group);
  const heading=el('div',undefined,'app-catalog-heading'),help=el('details',undefined,'app-dds-help'),summary=el('summary','?'),helpBody=el('div');
  summary.setAttribute('aria-label',t('catalogOps.fieldHelpTitle','{0} 항목 도움말',label(level)));
  metadataHelpKeys(data,level).forEach(key=>helpBody.append(el('p',label(key))));help.append(summary,helpBody);
  heading.append(el('h3',label(level),'app-attribute-title'),help);host.append(heading,filter,grid,controls);
  const identity=level==='schemas'?'SCHEMA_NAME':level==='tables'?'TABLE_NAME':'COLUMN_NAME';
  function draw(){
    const result=metadataPage(data,filter.value,page);page=result.page;
    const table=el('table',undefined,'table app-table'),head=el('thead'),tr=el('tr'),body=el('tbody');
    fields.forEach(field=>{const th=el('th',label('field.'+field));th.scope='col';tr.append(th);});head.append(tr);
    for(const {row} of result.items){
      const line=el('tr');for(const field of fields){
        const cell=el('td');if(field===identity&&onSelect){
          const b=button(row[field],()=>{selected=row[field];draw();onSelect(row[field]);},'app-credential-link');b.setAttribute('aria-pressed',String(selected===row[field]));cell.append(b);
        }else{const missingComment=field==='COMMENTS'&&!data.fields.includes(field);
          const value=el('span',row[field]??'—',isCommentField(field)?'app-catalog-comment':'');
          value.title=missingComment?label('commentUnavailable'):row[field]??'';cell.append(value);}line.append(cell);
      }body.append(line);
    }
    table.append(head,body);grid.replaceChildren(table);
    count.textContent=t('ui.f32c9f13d498','{0}–{1} / {2}개',result.from,result.to,result.total);pages.textContent=`${result.pages?page:0} / ${result.pages}`;
    prev.disabled=page<=1;next.disabled=page>=result.pages;
  }
  filter.addEventListener('input',()=>{page=1;draw();});draw();
}
export function catalogExplorer(host,entry,base){
  const gate=stageGate(),stages=el('div',undefined,'app-catalog-stages'),status=el('p',undefined,'app-filter-message');status.setAttribute('role','status');
  const schemas=el('section'),tables=el('section'),columns=el('section');stages.append(schemas,tables,columns);
  const load=button(label('browse'),()=>read('schemas'));
  load.disabled=!['YES','Y','TRUE','1','ENABLED'].includes(String(entry.enabled).toUpperCase());
  const toolbar=el('div',undefined,'app-assistant-actions'),help=el('details',undefined,'app-dds-help'),summary=el('summary','?');summary.setAttribute('aria-label',label('browseHelpTitle'));
  const helpBody=el('div');helpBody.append(el('p',label('browseHelp')),
    el('pre',"SELECT schema_name FROM DBMS_CATALOG.GET_SCHEMAS(:catalog);\nSELECT table_name FROM DBMS_CATALOG.GET_TABLES(\n  catalog_name => :catalog, schema_name => :schema);\nSELECT column_name FROM DBMS_CATALOG.GET_COLUMNS(\n  catalog_name => :catalog, schema_name => :schema,\n  table_name => :table);"));help.append(summary,helpBody);toolbar.append(load,help);host.append(toolbar,status,stages);
  async function read(level,schemaName='',tableName=''){
    const stamp=gate.start();load.disabled=true;
    if(level==='schemas')schemas.replaceChildren();if(level!=='columns')tables.replaceChildren();columns.replaceChildren();
    status.textContent=t('ui.8bf609c884ca','불러오는 중…');status.className='app-filter-message';
    try{
      const url=base+'/catalogs/browse?'+new URLSearchParams({name:entry.name,level,schemaName,tableName});
      const data=await assistantApi(url);if(!gate.current(stamp))return;
      const target=level==='schemas'?schemas:level==='tables'?tables:columns;
      metadataGrid(target,data,level,level==='schemas'?value=>read('tables',value):level==='tables'?value=>read('columns',schemaName,value):null);
      status.textContent=[entry.name,schemaName,tableName].filter(Boolean).join(' · ');
    }catch(ex){if(gate.current(stamp)){status.textContent=ex.message;status.className='app-alert is-error';}}
    finally{if(gate.current(stamp))load.disabled=false;}
  }
  return ()=>gate.cancel();
}
