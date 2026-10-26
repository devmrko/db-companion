import {t} from './i18n.mjs';
export function callbackLink(name,schema) {
  return '/db/functions?'+new URLSearchParams({schema,name});
}
export function sourceDetails(host,source,schema) {
  const info=document.createElement('div');info.className='app-muted';
  for(const [key,name] of Object.entries(source.callbacks??{})) {
    const row=document.createElement('div');row.append(`${key}: `);
    const value=document.createElement(key==='scope'?'span':'a');value.textContent=name;
    if(key!=='scope'){value.href=callbackLink(name,schema);value.target='_blank';value.rel='noopener';}
    row.append(value);info.append(row);
  }
  host.append(info);
}
export function semanticPicker(host,value,data,read,count) {
  const controller=new AbortController();
  const status=document.createElement('p');host.append(status);
  status.textContent=t('ui.8bf609c884ca','불러오는 중…');
  read('/ai-profiles/semantic?'+new URLSearchParams({schema:data.target.schema}),{signal:controller.signal}).then(result=>{
    const selected=()=>{try{return JSON.parse(value.value);}catch{return [];}};
    const boxes=[];
    status.textContent=t('semanticContext.pickerHelp','소스를 선택하면 연결된 Function이 함께 표시됩니다. 모두 해제하면 프로필 연결을 제거합니다.');
    for(const source of result.sources){
      const row=document.createElement('div');row.className='border rounded p-2 mb-2';
      const label=document.createElement('label'),box=document.createElement('input');box.type='checkbox';box.value=source.name;
      box.checked=selected().includes(source.name);box.disabled=source.status!=='ENABLED'&&!box.checked;
      label.append(box,` ${source.name} · ${source.status}`);row.append(label);sourceDetails(row,source,data.target.schema);host.append(row);boxes.push(box);
      box.addEventListener('change',()=>{const names=new Set(selected());if(box.checked)names.add(box.value);else names.delete(box.value);value.value=JSON.stringify([...names]);count();});
    }
    const sync=()=>boxes.forEach(box=>{box.checked=selected().includes(box.value);});
    value.addEventListener('input',sync,{signal:controller.signal});
  }).catch(error=>{if(error.name!=='AbortError')status.textContent=error.message;});
  return ()=>controller.abort();
}
