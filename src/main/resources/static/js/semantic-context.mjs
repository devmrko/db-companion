import {t} from './i18n.mjs';
import {sourceDetails,callbackLink} from './semantic-picker.mjs';
export function sourceTemplate(type,schema='APP') {
  return JSON.stringify(type==='mcp'?{scope:'global',source_endpoint:'https://example.com/mcp',credential_name:'MCP_CREDENTIAL',global_tool:{name:'search_business_glossary',arguments:{},response_mapping:{items:'$.results[*]',term:'$.term',definition:'$.definition'}}}
    :{scope:'global',global_callback_func:`${schema}.DBC_GLOSSARY_CONTEXT`},null,2);
}
export function validSources(value) {
  try { const a=JSON.parse(value);return Array.isArray(a)&&a.length<=100&&a.every(v=>typeof v==='string'&&/^[A-Z][A-Z0-9_$#]{0,124}$/.test(v))&&new Set(a).size===a.length; }
  catch { return false; }
}
export function mountSemantic(root) {
  const get=n=>root.querySelector(`[data-sem-${n}]`),label=k=>t('semanticContext.'+k,k);
  let busy=false,locked=false,ready=false,historyReady=false;
  const message=text=>{get('message').textContent=text;};
  const controls=()=>{for(const n of get('enabled').querySelectorAll('input,select,textarea,button'))n.disabled=busy||!ready;get('refresh').disabled=busy;get('save').disabled=busy||locked||!ready||!historyReady;};
  async function api(options={}) {
    const response=await fetch('/ai-profiles/semantic?'+new URLSearchParams({schema:root.dataset.schema}),{credentials:'same-origin',cache:'no-store',...options});
    if(response.redirected||response.status===401)throw new Error(label('session'));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(label('unavailable'));
    const result=await response.json();if(!response.ok)throw new Error(result.error||label('unavailable'));return result;
  }
  async function refresh() {
    if(busy)return;busy=true;controls();
    try {
      const s=await api();ready=s.supported&&s.editable;get('enabled').hidden=!ready;
      historyReady=false;
      if(ready){const h=await fetch('/semantic-history/status?'+new URLSearchParams({schema:root.dataset.schema,kind:'SOURCE'}),{credentials:'same-origin',cache:'no-store'});if(h.ok)historyReady=(await h.json()).storage==='READY';}
      get('status').textContent=`${s.version||'?'} · ${label(s.supported?s.editable?'supported':'ownerOnly':s.reason)}`;
      get('sources').replaceChildren();
      for(const source of s.sources){const tr=document.createElement('tr');for(const text of [source.name,source.type,source.status]){const td=document.createElement('td');td.textContent=text;tr.append(td);}const td=document.createElement('td');sourceDetails(td,source,root.dataset.schema);
        for(const [key,action] of [['edit',()=>openEdit(source.name)],['title',()=>document.dispatchEvent(new CustomEvent('semantic-history-select',{detail:{kind:'SOURCE',name:source.name}}))]]){
          const button=document.createElement('button');button.type='button';button.className='btn app-btn app-btn-quiet';button.textContent=t('semanticHistory.'+key,key);button.addEventListener('click',action);td.append(button);
        }tr.append(td);get('sources').append(tr);}
      if(!s.sources.length){const tr=document.createElement('tr'),td=document.createElement('td');td.colSpan=3;td.textContent=label('empty');tr.append(td);get('sources').append(tr);}
    } catch(e){ready=false;get('enabled').hidden=true;message(e.message);}finally{busy=false;controls();}
  }
  get('name').placeholder='DBC_GLOSSARY_SOURCE';
  get('callback').value=`${root.dataset.schema}.DBC_GLOSSARY_CONTEXT`;
  const configure=()=>{
    const custom=get('type').value==='custom';get('custom').hidden=!custom;get('callback').required=custom;
    get('attributes').readOnly=custom;get('json').open=!custom;
    get('attributes').value=custom?JSON.stringify({scope:'global',global_callback_func:get('callback').value.trim()},null,2):sourceTemplate('mcp',root.dataset.schema);
    get('function-link').href=callbackLink(get('callback').value.trim(),root.dataset.schema);get('confirm').checked=false;
  };
  configure();get('callback').addEventListener('input',configure);get('type').addEventListener('change',configure);
  fetch('/db/functions/list?'+new URLSearchParams({schema:root.dataset.schema}),{credentials:'same-origin',cache:'no-store'}).then(r=>r.ok?r.json():null).then(data=>{
    for(const item of Array.isArray(data)?data:[]){const name=item.reference.replace(/"([A-Z][A-Z0-9_$#]*)"/g,'$1');if(!/^[A-Z][A-Z0-9_$#]*(?:\.[A-Z][A-Z0-9_$#]*){1,2}$/.test(name))continue;const option=document.createElement('option');option.value=name;get('functions').append(option);}
  }).catch(()=>{});
  get('refresh').addEventListener('click',refresh);
  get('form').addEventListener('submit',async event=>{
    event.preventDefault();if(busy||locked||!ready||!historyReady||!get('confirm').checked)return;
    const body={schema:root.dataset.schema,name:get('name').value,type:get('type').value,attributes:get('attributes').value,description:get('description').value,confirmed:true};
    try{const parsed=JSON.parse(body.attributes);if(!parsed||Array.isArray(parsed)||typeof parsed!=='object')throw new Error();}catch{message(label('invalid'));return;}
    busy=true;controls();message(label('running'));
    try{const s=await api({method:'POST',headers:{'Content-Type':'application/json',[get('csrf').dataset.csrfHeader]:get('csrf').value},body:JSON.stringify(body)});locked=true;message(s.message);document.dispatchEvent(new CustomEvent('semantic-history-changed'));}
    catch(e){locked=true;message(e.message+' '+label('uncertain'));}
    finally{busy=false;controls();await refresh();}
  });
  let edited=null,plan=null,editBusy=false;
  const post=(path,body)=>fetch('/ai-profiles/semantic/'+path,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json',[get('csrf').dataset.csrfHeader]:get('csrf').value},body:JSON.stringify(body)}).then(async r=>{const value=await r.json();if(!r.ok)throw new Error(value.error||label('unavailable'));return value;});
  const invalidateEdit=()=>{plan=null;get('edit-plan').textContent='';get('edit-consent').checked=false;get('edit-apply').disabled=true;};
  async function openEdit(name){
    if(editBusy)return;if(!historyReady){message(t('semanticHistory.missing','Prepare record storage first.'));return;}editBusy=true;
    try{const r=await fetch('/ai-profiles/semantic/edit?'+new URLSearchParams({schema:root.dataset.schema,name}),{credentials:'same-origin',cache:'no-store'});const v=await r.json();if(!r.ok)throw new Error(v.error||label('unavailable'));
      edited={...v,name};invalidateEdit();get('edit-name').textContent=name;get('edit-attributes').value=v.attributes;get('edit-message').textContent='';
      const canEdit=String(v.current.type).toLowerCase()==='custom'&&JSON.parse(v.attributes).scope==='global';
      get('edit-operation').querySelector('option[value="ATTRIBUTES"]').disabled=!canEdit;
      get('edit-operation').value=canEdit?'ATTRIBUTES':String(v.current.status).toUpperCase()==='ENABLED'?'DISABLE':'ENABLE';
      get('edit-attributes').hidden=!canEdit;get('editor').showModal();
    }catch(e){message(e.message);}finally{editBusy=false;}
  }
  get('edit-close').addEventListener('click',()=>get('editor').close());
  get('edit-attributes').addEventListener('input',invalidateEdit);get('edit-operation').addEventListener('change',()=>{invalidateEdit();get('edit-attributes').hidden=get('edit-operation').value!=='ATTRIBUTES';});
  get('edit-consent').addEventListener('change',()=>{get('edit-apply').disabled=!plan||editBusy||Date.now()>=Date.parse(plan.expires)||!get('edit-consent').checked;});
  get('edit-preview').addEventListener('click',async()=>{if(editBusy||!edited)return;invalidateEdit();editBusy=true;get('edit-preview').disabled=true;
    try{plan=await post('edit-preview',{schema:root.dataset.schema,name:edited.name,operation:get('edit-operation').value,attributes:get('edit-attributes').value,version:edited.version});
      get('edit-plan').textContent=JSON.stringify({before:edited.current,request:plan.input,expires:plan.expires},null,2);get('edit-message').textContent='';
    }catch(e){get('edit-message').textContent=e.message;}finally{editBusy=false;get('edit-preview').disabled=false;}
  });
  get('edit-apply').addEventListener('click',async()=>{if(editBusy||!plan||!get('edit-consent').checked||Date.now()>=Date.parse(plan.expires))return;
    const token=plan.token;invalidateEdit();editBusy=true;get('edit-preview').disabled=true;
    try{const result=await post('edit-apply',{token,consent:true});get('edit-message').textContent=result.message;edited=null;document.dispatchEvent(new CustomEvent('semantic-history-changed'));await refresh();}
    catch(e){edited=null;get('edit-message').textContent=e.message+' '+label('uncertain');}
    finally{editBusy=false;get('edit-preview').disabled=false;}
  });
  document.addEventListener('semantic-history-storage-ready',()=>refresh());refresh();
}
if(typeof document!=='undefined')document.querySelectorAll('[data-semantic-context]').forEach(mountSemantic);
