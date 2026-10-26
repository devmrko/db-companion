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
  const get=n=>root.querySelector(`[data-sem-${n}]`),label=k=>t('semantic.'+k,k);
  let busy=false,locked=false,ready=false;
  const message=text=>{get('message').textContent=text;};
  const controls=()=>{for(const n of get('enabled').querySelectorAll('input,select,textarea,button'))n.disabled=busy||!ready;get('refresh').disabled=busy;get('save').disabled=busy||locked||!ready;};
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
      get('status').textContent=`${s.version||'?'} · ${label(s.supported?s.editable?'supported':'ownerOnly':s.reason)}`;
      get('sources').replaceChildren();
      for(const source of s.sources){const tr=document.createElement('tr');for(const text of [source.name,source.type,source.status]){const td=document.createElement('td');td.textContent=text;tr.append(td);}const td=document.createElement('td');sourceDetails(td,source,root.dataset.schema);tr.append(td);get('sources').append(tr);}
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
    event.preventDefault();if(busy||locked||!ready||!get('confirm').checked)return;
    const body={schema:root.dataset.schema,name:get('name').value,type:get('type').value,attributes:get('attributes').value,description:get('description').value,confirmed:true};
    try{const parsed=JSON.parse(body.attributes);if(!parsed||Array.isArray(parsed)||typeof parsed!=='object')throw new Error();}catch{message(label('invalid'));return;}
    busy=true;controls();message(label('running'));
    try{const s=await api({method:'POST',headers:{'Content-Type':'application/json',[get('csrf').dataset.csrfHeader]:get('csrf').value},body:JSON.stringify(body)});locked=true;message(s.message);}
    catch(e){locked=true;message(e.message+' '+label('uncertain'));}
    finally{busy=false;controls();await refresh();}
  });
  refresh();
}
if(typeof document!=='undefined')document.querySelectorAll('[data-semantic-context]').forEach(mountSemantic);
