import {t} from './i18n.mjs';
import {pageOf} from './table-list.mjs';
import {termsViewer} from './ontology-terms.mjs';

const label=key=>t('ontology.rdf.'+key,key);
export function compactIri(iri,data){
  if(iri===data.documentIri)return 'document';
  if(iri===data.versionIri)return 'revision';
  if(iri===data.tableIri)return 'table';
  if(iri.startsWith(data.tableIri+'/'))return 'table/'+iri.slice(data.tableIri.length+1);
  for(const [prefix,namespace] of Object.entries(data.prefixes))if(iri.startsWith(namespace))return prefix+':'+iri.slice(namespace.length);
  return iri;
}
export function triplesPage(data,query='',page=1){
  return pageOf(data.triples.map(row=>({...row,
    name:[row.subject,compactIri(row.subject,data),row.predicate,compactIri(row.predicate,data)].join(' '),
    description:[row.object.value,row.object.kind,row.object.kind==='IRI'?compactIri(row.object.value,data):''].join(' ')
  })),query,page);
}
export function meaningChanges(before,after){
  const rows=[];
  for(const field of ['concept','description'])if(before[field]!==after[field])rows.push({field,before:before[field],after:after[field]});
  for(const [column,value] of Object.entries(after.columns))if(before.columns[column]?.description!==value.description)
    rows.push({field:'column',column,before:before.columns[column]?.description??'',after:value.description});
  for(const [name,value] of Object.entries(after.relations??{}))if(before.relations?.[name]!==value)
    rows.push({field:'relation',name,before:before.relations?.[name]??'',after:value});
  return rows;
}

const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;};
const button=(text,fn)=>{const b=el('button',text,'btn app-btn app-btn-quiet');b.type='button';b.addEventListener('click',fn);return b;};
function term(value,display=value){const node=el('span',display,'app-rdf-term');node.title=value;node.tabIndex=0;return node;}

/** Render typed values as text. URNs are identifiers, not clickable remote URLs. */
export function rdfViewer(data,dirty=false){
  const root=el('div',undefined,'app-rdf-viewer'),toolbar=el('div',undefined,'app-rdf-toolbar');
  const help=el('details',undefined,'app-dds-help'),summary=el('summary','?');summary.setAttribute('aria-label',label('helpTitle'));
  const explanation=el('div');explanation.append(el('p',label('help')),el('p',label('aiHelp')));help.append(summary,explanation);
  const download=button(t('ontology.download','Turtle 다운로드'),()=>{
    const href=URL.createObjectURL(new Blob([data.text],{type:'text/turtle;charset=utf-8'})),a=el('a');
    a.href=href;a.download='ontology-'+data.documentIri.slice(-36)+'-v'+data.versionIri.split('/').at(-1)+'.ttl';a.click();
    setTimeout(()=>URL.revokeObjectURL(href),1000);
  });
  toolbar.append(el('strong',label('title')),help,download);root.append(toolbar);
  if(dirty)root.append(el('p',label('unsaved'),'app-alert'));
  const addresses=el('details',undefined,'app-rdf-addresses');addresses.append(el('summary',label('addresses')));
  for(const [key,value] of [['document',data.documentIri],['version',data.versionIri],['table',data.tableIri]]){
    const group=el('label',undefined,'app-ontology-field'),input=el('input',undefined,'form-control');input.value=value;input.readOnly=true;
    group.append(el('span',label(key)),input);addresses.append(group);
  }
  addresses.append(el('p',label('iriRule'),'app-filter-message'));root.append(addresses);
  const modes=el('div',undefined,'app-dds-tabs'),tablePanel=el('div'),turtlePanel=el('div');
  const termsPanel=termsViewer(data,row=>{selected=row;search.value='';page=1;selection.textContent=row.preferred;reset.hidden=false;draw();mode('triples');});let selected=null;
  turtlePanel.append(el('pre',data.text,'app-preview-source app-ontology-rdf'));turtlePanel.hidden=true;
  const tableButton=button(label('triples'),()=>mode('triples')),turtleButton=button('Turtle',()=>mode('turtle')),termsButton=button(t('ontology.terms.title','용어 검색'),()=>mode('terms'));
  function mode(active){for(const [key,panel,control] of [['triples',tablePanel,tableButton],['turtle',turtlePanel,turtleButton],['terms',termsPanel,termsButton]]){panel.hidden=active!==key;control.setAttribute('aria-pressed',String(active===key));}}
  modes.append(tableButton,turtleButton,termsButton);root.append(modes,tablePanel,turtlePanel,termsPanel);mode('triples');
  const search=el('input',undefined,'form-control');search.type='search';search.placeholder=label('search');search.setAttribute('aria-label',label('search'));
  const selection=el('span'),reset=button(t('ontology.terms.clear','전체 트리플'),()=>{selected=null;selection.textContent='';reset.hidden=true;page=1;draw();});reset.hidden=true;
  const searchbar=el('div',undefined,'app-rdf-toolbar');searchbar.append(search,selection,reset);tablePanel.append(searchbar);
  const wrap=el('div',undefined,'table-responsive'),table=el('table',undefined,'table app-table app-rdf-table'),head=el('thead'),hr=el('tr'),body=el('tbody');
  for(const key of ['subject','predicate','object','kind']){const cell=el('th',label(key));cell.scope='col';hr.append(cell);}head.append(hr);table.append(head,body);wrap.append(table);tablePanel.append(wrap);
  const pages=el('div',undefined,'app-dds-pages'),count=el('span'),controls=el('div'),number=el('span');let page=1;
  const prev=button(t('ui.da7e61c67cc5','이전'),()=>{page--;draw();}),next=button(t('ui.aef613c6612d','다음'),()=>{page++;draw();});
  controls.append(prev,number,next);pages.append(count,controls);tablePanel.append(pages);
  function draw(){
    const scoped=selected?{...data,triples:data.triples.filter(row=>row.subject===selected.resource||row.subject===selected.concept)}:data;
    const result=triplesPage(scoped,search.value,page);page=result.page;body.replaceChildren();
    for(const row of result.items){const tr=el('tr');
      for(const value of [term(row.subject,compactIri(row.subject,data)),term(row.predicate,compactIri(row.predicate,data)),
        term(row.object.value,row.object.kind==='IRI'?compactIri(row.object.value,data):row.object.value),el('span',label(row.object.kind))]){
        const td=el('td');td.append(value);tr.append(td);
      }body.append(tr);
    }
    if(!result.total){const tr=el('tr'),td=el('td',t('ontology.empty','항목이 없습니다.'),'app-empty');td.colSpan=4;tr.append(td);body.append(tr);}
    count.textContent=t('ui.f32c9f13d498','{0}–{1} / {2}개',result.from,result.to,result.total);
    number.textContent=`${result.pages?page:0} / ${result.pages}`;
    prev.disabled=page<=1;next.disabled=page>=result.pages;
    prev.dataset.boundDisabled=String(prev.disabled);next.dataset.boundDisabled=String(next.disabled);
  }
  search.addEventListener('input',()=>{page=1;draw();});draw();return root;
}
