import {t} from './i18n.mjs';
import {pageOf} from './table-list.mjs';

const SKOS='http://www.w3.org/2004/02/skos/core#',DBC='urn:dbcompanion:ontology:',RDF='http://www.w3.org/1999/02/22-rdf-syntax-ns#';
const normalize=value=>(value??'').normalize('NFC').trim().toLowerCase();
const label=key=>t('ontology.terms.'+key,key);

/** Read the server's typed graph, not prose or a second Turtle parser. Never merge by label. */
export function vocabulary(data){
  const nodes=new Map();
  for(const row of data.triples){if(!nodes.has(row.subject))nodes.set(row.subject,[]);nodes.get(row.subject).push(row);}
  const literal=(rows,predicate)=>rows.find(r=>r.predicate===predicate&&r.object.kind==='LITERAL')?.object.value??'';
  const result=[];
  for(const [resource,rows] of nodes){
    for(const link of rows.filter(r=>r.predicate===DBC+'concept'&&r.object.kind==='IRI')){
      const concept=link.object.value,terms=nodes.get(concept)??[];
      if(!terms.some(r=>r.predicate===RDF+'type'&&r.object.kind==='IRI'&&r.object.value===SKOS+'Concept'))continue;
      const preferred=literal(terms,SKOS+'prefLabel');if(!preferred.trim())continue;
      result.push({concept,resource,source:literal(rows,DBC+'name'),preferred,
        aliases:[...new Set(terms.filter(r=>r.predicate===SKOS+'altLabel'&&r.object.kind==='LITERAL').map(r=>r.object.value))],
        definition:literal(terms,SKOS+'definition'),state:literal(terms,DBC+'state')});
    }
  }return result;
}
export function termsPage(terms,query='',page=1){
  const search=normalize(query);
  const rows=terms.filter(row=>[row.preferred,...row.aliases,row.source].some(value=>normalize(value).includes(search)));
  return pageOf(rows,'',page);
}
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
const button=(text,fn)=>{const n=el('button',text,'btn app-btn app-btn-quiet');n.type='button';n.addEventListener('click',fn);return n;};

export function termsViewer(data,inspect){
  const terms=vocabulary(data),root=el('div'),toolbar=el('div',undefined,'app-rdf-toolbar');
  const search=el('input',undefined,'form-control');search.type='search';search.placeholder=label('search');search.setAttribute('aria-label',label('search'));
  const help=el('details',undefined,'app-dds-help'),summary=el('summary','?'),helpBody=el('div');summary.setAttribute('aria-label',label('helpTitle'));helpBody.append(el('p',label('help')));help.append(summary,helpBody);toolbar.append(search,help);root.append(toolbar);
  const wrap=el('div',undefined,'table-responsive'),table=el('table',undefined,'table app-table'),head=el('thead'),header=el('tr'),body=el('tbody');
  for(const key of ['preferred','aliases','source','definition']){const cell=el('th',label(key));cell.scope='col';header.append(cell);}head.append(header);table.append(head,body);wrap.append(table);root.append(wrap);
  const pages=el('div',undefined,'app-dds-pages'),count=el('span'),controls=el('div'),number=el('span');let page=1;
  const prev=button(t('ui.da7e61c67cc5','이전'),()=>{page--;draw();}),next=button(t('ui.aef613c6612d','다음'),()=>{page++;draw();});controls.append(prev,number,next);pages.append(count,controls);root.append(pages);
  function draw(){
    const result=termsPage(terms,search.value,page);page=result.page;body.replaceChildren();
    for(const row of result.items){
      const tr=el('tr'),preferred=el('div');preferred.append(el('strong',row.preferred),el('div',t('ontology.'+row.state,row.state),'app-filter-message'));
      const source=button(row.source||row.resource,()=>inspect(row));source.className='app-credential-link';source.title=row.resource;
      const aliases=el('div');for(const alias of row.aliases)aliases.append(el('div',alias));if(!row.aliases.length)aliases.textContent='—';
      for(const value of [preferred,aliases,source,el('span',row.definition||'—')]){const td=el('td');td.append(value);tr.append(td);}body.append(tr);
    }
    if(!result.total){const tr=el('tr'),td=el('td',terms.length?t('ontology.empty','항목이 없습니다.'):label('empty'),'app-empty');td.colSpan=4;tr.append(td);body.append(tr);}
    count.textContent=t('ui.f32c9f13d498','{0}–{1} / {2}개',result.from,result.to,result.total);number.textContent=`${result.pages?page:0} / ${result.pages}`;
    prev.disabled=page<=1;next.disabled=page>=result.pages;prev.dataset.boundDisabled=String(prev.disabled);next.dataset.boundDisabled=String(next.disabled);
  }
  search.addEventListener('input',()=>{page=1;draw();});draw();return root;
}
