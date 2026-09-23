import {t} from './i18n.mjs';
import {objectField,objectHelp,toolNames,updateTool,updateParameter,structuredParameters,parameterKeys,parameterHelp,validateObjectValue,objectRetryAllowed} from './object-fields.mjs';
import {referenceName,referenceValue,attachTeamHelp} from './team-fields.mjs';
const element=(tag,text='',className)=>{const node=document.createElement(tag);node.textContent=text;if(className)node.className=className;return node;};
function mount(dialog) {
  const find=name=>dialog.querySelector(`[data-aoe-${name}]`),fields=find('fields'),value=find('value'),save=find('save'),close=find('close'),host=find('control'),message=find('message');
  let state=null,initial='',control=value,pending=null,generation=0,saving=false,locked=false,reload=false;
  const show=(text,error=false)=>{message.textContent=text;message.hidden=!text;message.className=error?'app-alert is-error':'app-alert';};
  const dirty=()=>state&&control.value!==initial;
  const count=()=>{save.disabled=!state||!state.historyInstalled||saving||locked||!dirty();};
  function select(names,current,label,identifiers=true) {
    const node=element('select','','form-select app-select');node.setAttribute('aria-label',label);
    const empty=element('option',t('ui.33ae8967881d', "선택하세요"));empty.value='';empty.disabled=true;node.append(empty);
    for(const name of names){const option=element('option',name);option.value=identifiers?referenceValue(name):name;node.append(option);}
    const same=(a,b)=>identifiers?referenceName(a)===referenceName(b):a===b;
    if(current) {
      const found=[...node.options].find(option=>option.value&&same(option.value,current));
      if(found)found.value=current;
      else{const option=element('option',t('ui.83d0fbfd6d83', "{0} (현재값 · 목록에 없음)", current));option.value=current;option.disabled=true;node.append(option);}
    }
    node.value=current??'';return node;
  }
  function toolsControl() {
    host.replaceChildren();let rows;
    try{rows=toolNames(value.value);}catch(error){host.append(element('p',error.message,'app-alert is-error'));return;}
    rows.forEach((name,index)=>{
      const row=element('div','','app-object-tool-row'),choice=select(state.tools,name,`Tool ${index+1}`),remove=element('button',t('ui.ab6625092d13', "제거"),'btn app-btn app-btn-quiet');
      choice.addEventListener('change',()=>{value.value=updateTool(value.value,index,choice.value);count();});
      remove.type='button';remove.setAttribute('aria-label',t('ui.fbd076282cc4', "{0}번 Tool 제거", index+1));
      remove.addEventListener('click',()=>{value.value=JSON.stringify(toolNames(value.value).filter((_,i)=>i!==index));toolsControl();count();});row.append(choice,remove);host.append(row);
    });
    const add=element('button',t('ui.73e4f305ec16', "Tool 추가"),'btn app-btn app-btn-secondary');add.type='button';
    add.addEventListener('click',()=>{value.value=JSON.stringify([...toolNames(value.value),'']);toolsControl();count();host.querySelector('.app-object-tool-row:last-of-type select')?.focus();});host.append(add);
  }
  function paramsControl() {
    host.replaceChildren();let params;
    try{params=structuredParameters(value.value);}
    catch(error){host.append(element('p',error.message,'app-alert is-error'));return;}
    for(const key of parameterKeys(state.toolType,params)) {
      // Do not coerce unknown/non-string structures into text. Raw JSON remains available.
      if(Object.hasOwn(params,key)&&typeof params[key]!=='string')continue;
      const row=element('div','','app-object-param'),heading=element('div','','app-profile-field-label'),label=element('label',key);
      let input;
      if(key==='profile_name'||key==='credential_name')input=select(key==='profile_name'?state.profiles:state.credentials,params[key],key);
      else if(key==='notification_type')input=select([...new Set([params[key],'slack','email'].filter(Boolean))],params[key],key,false);
      else{input=element('input','','form-control');input.type='text';input.value=params[key]??'';input.setAttribute('aria-label',key);}
      input.id=`object-param-${key}`;label.htmlFor=input.id;heading.append(label);attachTeamHelp(heading,key,{help:parameterHelp[key]});
      input.addEventListener('change',()=>{value.value=updateParameter(value.value,key,input.value);if(key==='notification_type')paramsControl();count();});
      if(input.tagName==='INPUT')input.addEventListener('input',()=>{value.value=updateParameter(value.value,key,input.value);count();});
      row.append(heading,input);host.append(row);
    }
  }
  function prepare(data) {
    state=data;value.value=data.value??'';control=value;host.replaceChildren();const spec=objectField(data.target.kind,data.target.attribute);
    if(!spec||spec.type==='readonly')throw new Error(t('ui.6feceb03f35f', "이 속성은 편집할 수 없습니다."));
    find('raw').hidden=!['tools','params','jsonArray'].includes(spec.type);find('raw').open=spec.type==='jsonArray';
    if(spec.type==='tools')toolsControl();
    else if(spec.type==='params')paramsControl();
    else if(spec.type==='boolean') {
      if(!['true','false'].includes((data.value??'').toLowerCase()))throw new Error(t('ui.a4b2e7884372', "현재 논리값 형식을 확인해 주세요."));
      const row=element('label','','form-check form-switch app-object-toggle'),toggle=element('input','','form-check-input'),text=element('span',data.value);
      toggle.type='checkbox';toggle.setAttribute('role','switch');toggle.setAttribute('aria-label',data.target.attribute);toggle.checked=data.value.toLowerCase()==='true';
      toggle.addEventListener('change',()=>{value.value=String(toggle.checked);text.textContent=value.value;count();});row.append(toggle,text);host.append(row);
    }else if(spec.type==='profile'||spec.type==='task') {control=select(spec.type==='profile'?data.profiles:data.tasks,data.value,data.target.attribute);host.append(control);}
    else if(spec.type==='type'){control=select(['SQL','RAG','WEBSEARCH','NOTIFICATION'],data.value,data.target.attribute,false);host.append(control);}
    else if(spec.type!=='jsonArray') {
      control=element(spec.type==='text'?'textarea':'input','','form-control app-editor-value');control.value=data.value??'';
      if(spec.type==='text'){control.rows=12;control.spellcheck=false;}else{control.type='text';if(spec.type==='integer')control.inputMode='numeric';}
      host.append(control);
    }
    if(control!==value){control.id='object-typed-value';control.setAttribute('aria-label',data.target.attribute);control.addEventListener('input',count);control.addEventListener('change',count);}
    value.setAttribute('aria-label',data.target.attribute);find('label').htmlFor=control.id;initial=control.value;
    find('help').replaceChildren();objectHelp(find('help'),data.target.kind,data.target.attribute);
  }
  async function api(url,options={}) {
    const response=await fetch(url,{credentials:'same-origin',cache:'no-store',...options});
    if(response.redirected||response.status===401)throw new Error(t('ui.43956c4e70d1', "로그인 세션이 만료되었습니다. 입력을 복사한 뒤 다시 로그인해 주세요."));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(t('ui.1930407cf479', "응답을 확인하지 못했습니다. HTTP {0}", response.status));
    const data=await response.json();if(!response.ok)throw Object.assign(new Error(data.error||`HTTP ${response.status}`),{status:response.status});return data;
  }
  document.querySelectorAll('[data-edit-object-attribute]').forEach(button=>button.addEventListener('click',async()=>{
    pending?.abort();pending=new AbortController();const id=++generation,component=button.closest('[data-object-kind]');
    state=null;control=value;initial='';locked=false;reload=false;fields.disabled=true;value.value='';host.replaceChildren();find('help').replaceChildren();find('raw').hidden=true;count();
    const target={schema:dialog.dataset.schema,kind:component.dataset.objectKind,name:component.dataset.objectName,attribute:button.dataset.editObjectAttribute};
    find('title').textContent=t('ui.510f545fca89', "{0} 속성 편집", component.dataset.componentKind);find('object').textContent=`${target.schema}.${target.name}`;find('label').textContent=target.attribute;
    show(t('ui.8bf609c884ca', "불러오는 중…"));dialog.showModal();const url=new URL(dialog.dataset.url,location.origin);Object.entries(target).forEach(([key,v])=>url.searchParams.set(key,v));
    try {
      const data=await api(url,{signal:pending.signal});if(id!==generation||!dialog.open)return;
      prepare(data);fields.disabled=false;count();show(data.historyInstalled?'':t('ui.660fecd18ae9', "변경 이력에서 공통 이력을 준비해 주세요."));host.querySelector('select,input,textarea')?.focus();
    }catch(error){if(error.name!=='AbortError'&&id===generation){state=null;show(error.message,true);count();}}
  }));
  value.addEventListener('input',()=>{if(state?.target.attribute==='tools')toolsControl();if(state?.target.attribute==='tool_params')paramsControl();count();});
  find('form').addEventListener('submit',async event=>{
    event.preventDefault();if(!state||saving||locked||save.disabled)return;
    const submitted=control.value,invalid=validateObjectValue(state.target.kind,state.target.attribute,submitted,state.maxBytes);
    if(invalid){show(invalid,true);return;}
    const csrf=find('csrf');saving=true;close.disabled=true;fields.disabled=true;count();show(t('ui.88daaeedfe4c', "저장 중…"));
    try {
      const result=await api(dialog.dataset.url,{method:'POST',headers:{'Content-Type':'application/json',[csrf.dataset.csrfHeader]:csrf.value},body:JSON.stringify({...state.target,value:submitted,version:state.version})});
      locked=true;reload=result.verified;if(result.verified)initial=submitted;show(result.message,!result.verified);
    }catch(error){locked=!objectRetryAllowed(error.status);show(error.message,true);}
    finally{saving=false;close.disabled=false;fields.disabled=false;count();}
  });
  const canClose=()=>!saving&&(!dirty()||confirm(t('ui.1ea0a53d0b54', "입력을 닫을까요? 필요한 내용은 먼저 복사해 주세요.")));
  close.addEventListener('click',()=>{if(canClose())dialog.close();});dialog.addEventListener('cancel',event=>{if(!canClose())event.preventDefault();});
  dialog.addEventListener('close',()=>{++generation;pending?.abort();if(reload)location.reload();});
}
if(typeof document!=='undefined') {
  document.querySelectorAll('[data-object-attribute-name]').forEach(cell=>objectHelp(cell,cell.closest('[data-object-kind]').dataset.objectKind,cell.dataset.objectAttributeName));
  document.querySelectorAll('[data-object-editor]').forEach(mount);
}
