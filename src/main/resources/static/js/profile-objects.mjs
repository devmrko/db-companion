import {t} from './i18n.mjs';
import {objectEntries,addObject,removeObject,distinctObjectChoices} from './profile-fields.mjs';

export function objectPicker(container,raw,state,read,onInput,baseUrl) {
  let active=true, supported=true, pending=null, generation=0, choices=[];
  const make=(tag,text,className)=>{const node=document.createElement(tag);if(text)node.textContent=text;if(className)node.className=className;return node;};
  const list=make('div',null,'app-profile-objects'), note=make('p',null,'app-muted'), controls=make('div',null,'app-object-picker');
  note.setAttribute('role','status');
  const owner=make('select',null,'form-select app-select'), search=make('input',null,'form-control'), load=make('button',t('ui.f8d240bf18a6', "조회"),'btn app-btn app-btn-secondary');
  owner.setAttribute('aria-label',t('ui.1e62cb6d7dec', "객체 스키마"));search.setAttribute('aria-label',t('ui.aa9cbbb6cf11', "객체명 포함 검색"));search.placeholder=t('ui.aa9cbbb6cf11', "객체명 포함 검색");search.maxLength=128;
  for(const schema of state.schemas){const option=make('option',schema);option.value=schema;owner.append(option);}
  owner.value=state.target.schema;load.type='button';
  const select=make('select',null,'form-select app-select'), add=make('button',t('ui.b73accca8a4a', "추가"),'btn app-btn app-btn-secondary');
  select.setAttribute('aria-label',t('ui.b7aefed103c7', "추가할 객체"));add.type='button';add.disabled=true;select.disabled=true;
  controls.append(owner,search,load,select,add);container.append(list,controls,note);
  function render() {
    list.replaceChildren();
    try {
      const rows=objectEntries(raw.value);
      if(!rows.length)list.append(make('p',t('ui.31616a384de4', "선택한 객체가 없습니다."),'app-muted'));
      rows.forEach((entry,index)=>{
        const row=make('div',null,'app-profile-object-row'), label=make('span',entry.owner+'.'+(entry.name??t('ui.6ca61a610db5', "(스키마 전체)")));
        const extra=Object.keys(entry).filter(k=>k!=='owner'&&k!=='name');
        if(extra.length)label.append(make('small',` · ${extra.map(k=>`${k}: ${entry[k]}`).join(', ')}`));
        const remove=make('button',t('ui.ab6625092d13', "제거"),'btn app-btn app-btn-quiet');remove.type='button';remove.setAttribute('aria-label',t('ui.2f1e99ab48f7', "{0}.{1} 제거", entry.owner, entry.name??t('ui.e561c26c387a', "스키마 전체")));
        remove.addEventListener('click',()=>{raw.value=removeObject(raw.value,index);onInput();render();});row.append(label,remove);list.append(row);
      });
      supported=true;load.disabled=false;
    }catch(error){supported=false;note.textContent=error.message;load.disabled=true;add.disabled=true;}
  }
  function resetChoices(){pending?.abort();generation++;choices=[];select.replaceChildren();select.disabled=true;add.disabled=true;load.disabled=!supported;note.textContent='';}
  owner.addEventListener('change',resetChoices);
  search.addEventListener('input',resetChoices);
  search.addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();if(!load.disabled)load.click();}});
  select.addEventListener('change',()=>{add.disabled=!select.value;});
  load.addEventListener('click',async()=>{
    resetChoices();pending=new AbortController();const id=generation, selectedOwner=owner.value;load.disabled=true;note.textContent=t('ui.cc961fffa211', "객체 조회 중…");
    const url=new URL(baseUrl+'/objects',location.origin);
    for(const [key,value] of Object.entries({schema:state.target.schema,profile:state.target.profile,owner:selectedOwner,filter:search.value}))url.searchParams.set(key,value);
    try {
      const result=await read(url,{signal:pending.signal});if(!active||id!==generation)return;
      choices=distinctObjectChoices(result.objects);const placeholder=make('option',t('ui.38b3085cd318', "객체 선택"));placeholder.value='';select.append(placeholder);
      const all=make('option',t('ui.e561c26c387a', "스키마 전체"));all.value='all';select.append(all);
      choices.forEach((item,index)=>{const option=make('option',`${item.name} · ${item.type}`);option.value=String(index);select.append(option);});
      select.disabled=false;note.textContent=result.more?t('ui.c3f3d8812549', "{0}개 표시 · 검색어로 범위를 좁혀 주세요.", choices.length):t('ui.24f720a58a81', "{0}개", choices.length);
    }catch(error){if(error.name!=='AbortError'&&active&&id===generation)note.textContent=error.message;}
    finally{if(active&&id===generation)load.disabled=false;}
  });
  add.addEventListener('click',()=>{
    if(!select.value)return;
    try{raw.value=addObject(raw.value,owner.value,select.value==='all'?null:choices[Number(select.value)].name);onInput();render();}
    catch(error){note.textContent=error.message;}
  });
  const rawChanged=()=>{resetChoices();render();};raw.addEventListener('input',rawChanged);resetChoices();render();
  return ()=>{active=false;pending?.abort();raw.removeEventListener('input',rawChanged);};
}
