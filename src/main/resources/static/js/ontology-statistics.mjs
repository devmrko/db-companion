import {t} from './i18n.mjs';
const text=k=>t('ontology.statistics.'+k,k);
const node=(tag,value)=>{const n=document.createElement(tag);if(value!==undefined)n.textContent=value;return n;};
export function renderStatistics(host,report){
  host.replaceChildren(node('p',text('bounded')),node('p',report.checkedAt));
  for(const c of report.columns){
    const box=node('section');box.append(node('strong',c.name+' · '+c.dataType));
    const table=node('table');table.className='table app-table';
    for(const [key,value] of [['observed',c.observed],['nulls',c.nulls],['nullRatio',c.observed?(100*c.nulls/c.observed).toFixed(2)+'%':'—'],['withheld',c.withheld],['distinct',c.distinctObserved],['minimum',c.minimum??'—'],['maximum',c.maximum??'—']]){const row=node('tr');row.append(node('th',text(key)),node('td',String(value)));table.append(row);}box.append(table,node('h4',text('frequency')));
    for(const v of c.topValues){const row=node('p');row.append(node('code',JSON.stringify(v.value)),node('span',' · '+v.count));box.append(row);}host.append(box);
  }
}
export function statisticsEditor(host,{entry,meaning,statistics,run}){
  const panel=node('details');panel.append(node('summary',text('title')),node('p',text('bounded')));
  const select=node('select');select.className='form-select app-select';select.setAttribute('aria-label',text('column'));
  for(const c of entry.document.source.columns.filter(c=>/^(VARCHAR2|NVARCHAR2|CHAR|NCHAR|NUMBER|FLOAT|DATE|TIMESTAMP)(\(|$)/i.test(c.dataType)&&meaning.columns[c.name]?.sensitivity!=='SENSITIVE'))select.add(new Option(c.name,c.name));
  const check=node('input');check.type='checkbox';const consent=node('label');consent.className='app-ontology-consent';consent.append(check,node('span',text('consent')));
  const button=node('button',text('analyze'));button.type='button';button.className='btn app-btn app-btn-secondary';const output=node('div');
  const lock=()=>{button.disabled=!check.checked||!select.value;button.dataset.boundDisabled=String(button.disabled);};
  select.addEventListener('change',()=>{check.checked=false;output.replaceChildren();lock();});check.addEventListener('change',lock);
  button.addEventListener('click',()=>run(async()=>{if(!check.checked||!select.value)return;output.replaceChildren();const report=await statistics({schema:entry.document.source.schema,table:entry.document.source.table,revision:entry.revision,column:select.value,confirmed:true});renderStatistics(output,report);}));
  lock();panel.append(select,consent,button,output);host.append(panel);
}
