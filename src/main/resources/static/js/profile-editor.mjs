import {t} from './i18n.mjs';
import {profileField,booleanValue,validateTypedValue,credentialChoices,attachProfileHelp} from './profile-fields.mjs';
import {objectPicker} from './profile-objects.mjs';
export const profileBytes = value => new TextEncoder().encode(value).length;
export function validateProfileValue(attribute,value,maxBytes=32767) {
  if (!value || !value.trim()) return t('ui.db6144584361', "값을 입력해 주세요. 빈 값 저장이나 속성 삭제는 지원하지 않습니다.");
  if (!value.isWellFormed() || value.includes('\0')) return t('ui.276b201421c0', "올바르지 않은 문자가 있습니다.");
  if (profileBytes(value)>maxBytes) return t('ui.2c41f4ca8f42', "내용은 UTF-8 기준 32,767바이트 이내로 입력해 주세요. 입력은 자르지 않습니다.");
  const typed=validateTypedValue(attribute,value);if(typed)return typed;
  if (attribute==='object_list') {
    try { if (!Array.isArray(JSON.parse(value))) return t('ui.69ae71398505', "object_list는 JSON 배열로 입력해 주세요."); }
    catch { return t('ui.ddcee732ecb8', "object_list는 올바른 JSON 배열로 입력해 주세요."); }
  }
  return '';
}
export const profileRetryAllowed = status => status===400;

function mount(dialog) {
  const find=name=>dialog.querySelector(`[data-pe-${name}]`);
  const fields=find('fields'), value=find('value'), save=find('save'), close=find('close'), message=find('message');
  let state=null, initial='', pending=null, generation=0, saving=false, locked=false, reload=false, control=value, cleanup=()=>{};
  const show=(text,error=false)=>{message.textContent=text; message.hidden=!text; message.className=error?'app-alert is-error':'app-alert';};
  const currentValue=()=>control.type==='checkbox'?String(control.checked):control.value;
  const dirty=()=>state && currentValue()!==initial;
  const count=()=>{find('bytes').textContent=profileBytes(currentValue()); save.disabled=!state||saving||locked||!dirty();};
  const element=(tag,text,className)=>{const node=document.createElement(tag);if(text)node.textContent=text;if(className)node.className=className;return node;};
  function prepare(data) {
    const attr=data.target.attribute,spec=profileField(attr),host=find('control');
    const help=dialog.querySelector('[data-sql-help-for="profiles"]');
    if(help)help.dataset.sqlHelpOperation=attr==='model'?'model':attr==='object_list'?'objects':'options';
    let type=spec.type;
    // Keep unfamiliar existing representations intact instead of silently converting them.
    if(type==='boolean'&&booleanValue(data.value)===null)type='multiline';
    if(['text','suggest','number','integer'].includes(type)&&/[\r\n]/.test(data.value??''))type='multiline';
    value.value=data.value??'';control=value;find('raw').hidden=!['multiline','objects'].includes(type);
    find('counter').hidden=!['multiline','objects'].includes(type);
    dialog.classList.toggle('app-profile-editor-compact',!['multiline','objects'].includes(type));
    if(type==='boolean') {
      const row=element('div',null,'form-check form-switch app-profile-switch');control=element('input',null,'form-check-input');control.type='checkbox';control.setAttribute('role','switch');control.checked=booleanValue(data.value)==='true';
      const status=element('span',String(control.checked));status.setAttribute('aria-live','polite');control.addEventListener('change',()=>{status.textContent=String(control.checked);count();});row.append(control,status);host.append(row);
    }else if(type==='select'||type==='credential') {
      control=element('select',null,'form-select app-select');
      const choices=type==='credential'?credentialChoices(data.value,data.choices):[...new Set([data.value,...spec.choices])].filter(v=>v!==null&&v!==undefined).map(v=>({value:v,label:v,disabled:false}));
      const empty=element('option',t('ui.33ae8967881d', "선택하세요"));empty.value='';empty.disabled=true;control.append(empty);
      for(const item of choices){const option=element('option',item.label);option.value=item.value;option.disabled=item.disabled;control.append(option);}
      control.value=data.value??'';host.append(control);control.addEventListener('change',count);
      if(type==='credential'&&!data.choices.length)host.append(element('p',t('ui.aba766fd1bc9', "활성 Credential이 없습니다."),'app-muted app-editor-note'));
    }else if(type==='objects') {
      cleanup=objectPicker(host,value,data,read,count,dialog.dataset.url);
      const details=element('details',null,'app-profile-json-editor'),summary=element('summary',t('ui.7e7265068560', "JSON 직접 편집"));details.append(summary);
      find('raw').append(details);details.append(value);
    }else if(type!=='multiline') {
      control=element('input',null,'form-control');control.type=['integer','number'].includes(type)&&attr!=='seed'?'number':'text';
      if(type==='integer')control.inputMode='numeric';if(type==='number')control.inputMode='decimal';
      for(const key of ['min','max','step'])if(spec[key])control.setAttribute(key,spec[key]);
      control.value=data.value??'';
      if(control.value!==(data.value??'')){control.type='text';control.value=data.value??'';}
      host.append(control);control.addEventListener('input',count);
      if(type==='suggest'){
        const list=element('datalist');list.id='profile-value-suggestions';control.setAttribute('list',list.id);
        for(const item of data.choices){const option=element('option');option.value=item;list.append(option);}host.append(list);
      }
    }
    control.id=control===value?'profile-attribute-value':'profile-typed-value';
    control.setAttribute('aria-label',attr);find('label').htmlFor=control.id;
    find('help').replaceChildren();attachProfileHelp(find('help'),attr);
    initial=currentValue();
  }
  async function read(url,options={}) {
    const response=await fetch(url,{cache:'no-store',credentials:'same-origin',...options});
    if(response.redirected||response.status===401) throw new Error(t('ui.43956c4e70d1', "로그인 세션이 만료되었습니다. 입력을 복사한 뒤 다시 로그인해 주세요."));
    if(!response.headers.get('content-type')?.includes('application/json')) throw new Error(t('ui.1930407cf479', "응답을 확인하지 못했습니다. HTTP {0}", response.status));
    const data=await response.json();
    if(!response.ok) throw Object.assign(new Error(data.error||`HTTP ${response.status}`),{status:response.status});
    return data;
  }
  document.querySelectorAll('[data-edit-profile-attribute]').forEach(button=>button.addEventListener('click',async()=>{
    pending?.abort(); pending=new AbortController(); const id=++generation;
    cleanup();cleanup=()=>{};find('raw').append(value);find('raw').querySelector('details')?.remove();find('control').replaceChildren();find('help').replaceChildren();
    control=value;state=null; locked=false; reload=false; fields.disabled=true; value.value=''; initial=''; count();
    find('object').textContent=`${dialog.dataset.schema}.${dialog.dataset.profile}`;
    find('label').textContent=button.dataset.editProfileAttribute; find('hint').textContent='';
    show(t('ui.8bf609c884ca', "불러오는 중…")); dialog.showModal();
    const url=new URL(dialog.dataset.url,location.origin);
    for(const [key,item] of Object.entries({schema:dialog.dataset.schema,profile:dialog.dataset.profile,attribute:button.dataset.editProfileAttribute})) url.searchParams.set(key,item);
    try {
      const data=await read(url,{signal:pending.signal}); if(id!==generation||!dialog.open)return;
      prepare(data);state=data;
      fields.disabled=false; count(); show(''); if(control===value&&profileField(data.target.attribute).type==='objects')find('control').querySelector('select')?.focus();else control.focus();
    } catch(error){if(error.name!=='AbortError'&&id===generation)show(error.message,true);}
  }));
  value.addEventListener('input',count);
  find('form').addEventListener('submit',async event=>{
    event.preventDefault(); if(!state||saving||locked||save.disabled)return;
    const submitted=currentValue(), invalid=validateProfileValue(state.target.attribute,submitted,state.maxBytes);
    if(invalid){show(invalid,true);return;}
    const csrf=find('csrf'); saving=true; close.disabled=true; fields.disabled=true; count(); show(t('ui.88daaeedfe4c', "저장 중…"));
    try {
      const result=await read(dialog.dataset.url,{method:'POST',headers:{'Content-Type':'application/json',[csrf.dataset.csrfHeader]:csrf.value},
        body:JSON.stringify({...state.target,value:submitted,version:state.version})});
      locked=true; reload=result.verified; if(result.verified)initial=submitted; show(result.message,!result.verified);
    } catch(error){locked=!profileRetryAllowed(error.status);show(error.message,true);}
    finally{saving=false;close.disabled=false;fields.disabled=false;count();}
  });
  const canClose=()=>!saving && (!dirty() || confirm(t('ui.1e0b7aee0c56', "입력을 닫을까요? 저장 결과를 확인하고 필요한 내용은 먼저 복사해 주세요.")));
  close.addEventListener('click',()=>{if(canClose())dialog.close();});
  dialog.addEventListener('cancel',event=>{if(!canClose())event.preventDefault();});
  dialog.addEventListener('close',()=>{++generation;pending?.abort();cleanup();if(reload)location.reload();});
}
if(typeof document!=='undefined')document.querySelectorAll('[data-profile-editor]').forEach(mount);
