import {t} from './i18n.mjs';
/** Shared observed SQL results; values are text, including NULL and clipping markers. */
export function renderQueryData(host,data,doc=host.ownerDocument||document){
  host.replaceChildren();let page=0;
  const stamp=doc.createElement('p');stamp.className='app-filter-message';stamp.textContent=`${data.actor} · ${new Date(data.executedAt).toLocaleString()}`;host.append(stamp);
  if(data.truncated){const note=doc.createElement('p');note.textContent=t('aitest.moreRows','200행까지만 표시합니다. 추가 행이 있습니다.');host.append(note);}
  const wrapper=doc.createElement('div');wrapper.className='table-responsive';const table=doc.createElement('table');table.className='app-table';
  const head=doc.createElement('thead'),header=doc.createElement('tr'),body=doc.createElement('tbody');
  for(const name of data.columns){const cell=doc.createElement('th');cell.textContent=name;header.append(cell);}head.append(header);table.append(head,body);wrapper.append(table);host.append(wrapper);
  const paging=doc.createElement('div');paging.className='app-assistant-actions';const previous=doc.createElement('button'),next=doc.createElement('button'),count=doc.createElement('span');
  for(const button of [previous,next]){button.type='button';button.className='btn app-btn app-btn-quiet';}
  previous.textContent=t('ui.da7e61c67cc5','이전');next.textContent=t('ui.aef613c6612d','다음');paging.append(previous,count,next);host.append(paging);
  const paint=()=>{body.replaceChildren();for(const row of data.rows.slice(page*10,page*10+10)){const tr=doc.createElement('tr');for(const cell of row){const td=doc.createElement('td');td.textContent=cell.value===null?'NULL':cell.value;if(cell.truncated){td.append(doc.createTextNode('…'));td.title=t('aitest.cellClipped','2,000자 표시 한도');}tr.append(td);}body.append(tr);}count.textContent=`${page+1} / ${Math.max(1,Math.ceil(data.rows.length/10))}`;previous.disabled=page===0;next.disabled=(page+1)*10>=data.rows.length;};
  previous.addEventListener('click',()=>{if(page>0){page--;paint();}});next.addEventListener('click',()=>{if((page+1)*10<data.rows.length){page++;paint();}});paint();
}
