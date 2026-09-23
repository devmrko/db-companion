import {t} from './i18n.mjs';
import {teamField,teamAssignments,referenceName,referenceValue,validateTeamValue,moveAssignment,changeAssignment,removeAssignment,attachTeamHelp} from './team-fields.mjs';
const element=(tag,text='',className)=>{const node=document.createElement(tag);node.textContent=text;if(className)node.className=className;return node;};
export const teamRetryAllowed=status=>status===400;
function mount(dialog) {
  const find=name=>dialog.querySelector(`[data-te-${name}]`);
  const fields=find('fields'),value=find('value'),save=find('save'),close=find('close'),host=find('control'),message=find('message');
  let state=null,initial='',control=value,pending=null,generation=0,saving=false,locked=false,reload=false;
  const show=(text,error=false)=>{message.textContent=text;message.hidden=!text;message.className=error?'app-alert is-error':'app-alert';};
  const dirty=()=>state&&control.value!==initial;
  const count=()=>{save.disabled=!state||saving||locked||!dirty();};
  function select(names,current,label) {
    const node=element('select','','form-select app-select');node.setAttribute('aria-label',label);
    const empty=element('option',t('ui.33ae8967881d', "선택하세요"));empty.value='';empty.disabled=true;node.append(empty);
    for(const name of names){const option=element('option',name);option.value=referenceValue(name);node.append(option);}
    if(current&&!names.some(name=>name===referenceName(current))) {
      const option=element('option',t('ui.83d0fbfd6d83', "{0} (현재값 · 목록에 없음)", current));option.value=current;option.disabled=true;node.append(option);
    } else if(current) {
      // Preserve original spelling/quoting until the user changes this field.
      const option=[...node.options].find(option=>option.value&&referenceName(option.value)===referenceName(current));
      if(option)option.value=current;
    }
    node.value=current??'';return node;
  }
  function assignments() {
    host.replaceChildren();let rows;
    try{rows=teamAssignments(value.value);}catch(error){host.append(element('p',error.message,'app-alert is-error'));return;}
    rows.forEach((row,index)=>{
      const line=element('div','','app-team-assignment'),order=element('span',String(index+1),'app-muted');
      const agent=select(state.agents,row.name,`Agent ${index+1}`),task=select(state.tasks,row.task,`Task ${index+1}`);
      agent.addEventListener('change',()=>{value.value=changeAssignment(value.value,index,'name',agent.value);count();});
      task.addEventListener('change',()=>{value.value=changeAssignment(value.value,index,'task',task.value);count();});
      const actions=element('div','','app-team-assignment-actions');
      for(const [text,label,direction] of [['↑',t('ui.145cbfec79a0', "{0}번 연결 위로", index+1),-1],['↓',t('ui.b0a359b9faa9', "{0}번 연결 아래로", index+1),1],['×',t('ui.591f27a6a8be', "{0}번 연결 제거", index+1),0]]) {
        const button=element('button',text,'btn app-btn app-btn-quiet');button.type='button';button.setAttribute('aria-label',label);
        button.disabled=direction===-1&&index===0||direction===1&&index===rows.length-1;
        button.addEventListener('click',()=>{value.value=direction?moveAssignment(value.value,index,direction):removeAssignment(value.value,index);assignments();count();});actions.append(button);
      }
      line.append(order,agent,task,actions);host.append(line);
    });
    const add=element('button',t('ui.9752f9237ef5', "연결 추가"),'btn app-btn app-btn-secondary');add.type='button';
    add.addEventListener('click',()=>{value.value=JSON.stringify([...teamAssignments(value.value),{name:'',task:''}]);assignments();count();host.querySelector('.app-team-assignment:last-of-type select')?.focus();});host.append(add);
  }
  function prepare(data) {
    const attribute=data.target.attribute,spec=teamField(attribute);state=data;value.value=data.value??'';control=value;
    find('raw').hidden=spec.type!=='assignments';find('raw').open=false;host.replaceChildren();
    if(spec.type==='assignments')assignments();
    else if(spec.type==='agent'){control=select(data.agents,data.value,attribute);host.append(control);}
    else if(spec.type==='process') {
      control=element('select','','form-select app-select');
      for(const item of new Set([data.value,'sequential'])){const option=element('option',item);option.value=item;option.disabled=item!=='sequential';control.append(option);}
      control.value=data.value;host.append(control);
    }else{control=element('input','','form-control');control.type='text';control.inputMode='numeric';control.value=data.value??'';host.append(control);}
    if(control!==value){control.id='team-typed-value';control.setAttribute('aria-label',attribute);control.addEventListener('input',count);control.addEventListener('change',count);}
    value.setAttribute('aria-label',attribute);find('label').htmlFor=control.id;initial=control.value;
    find('help').replaceChildren();attachTeamHelp(find('help'),attribute);
  }
  async function api(url,options={}) {
    const response=await fetch(url,{credentials:'same-origin',cache:'no-store',...options});
    if(response.redirected||response.status===401)throw new Error(t('ui.43956c4e70d1', "로그인 세션이 만료되었습니다. 입력을 복사한 뒤 다시 로그인해 주세요."));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(t('ui.1930407cf479', "응답을 확인하지 못했습니다. HTTP {0}", response.status));
    const data=await response.json();if(!response.ok)throw Object.assign(new Error(data.error||`HTTP ${response.status}`),{status:response.status});return data;
  }
  document.querySelectorAll('[data-edit-team-attribute]').forEach(button=>button.addEventListener('click',async()=>{
    pending?.abort();pending=new AbortController();const id=++generation;
    state=null;control=value;initial='';locked=false;reload=false;fields.disabled=true;value.value='';host.replaceChildren();find('help').replaceChildren();count();
    find('object').textContent=`${dialog.dataset.schema}.${dialog.dataset.team}`;find('label').textContent=button.dataset.editTeamAttribute;
    show(t('ui.8bf609c884ca', "불러오는 중…"));dialog.showModal();
    const url=new URL(dialog.dataset.url,location.origin);
    for(const [key,item] of Object.entries({schema:dialog.dataset.schema,team:dialog.dataset.team,attribute:button.dataset.editTeamAttribute}))url.searchParams.set(key,item);
    try{const data=await api(url,{signal:pending.signal});if(id!==generation||!dialog.open)return;prepare(data);fields.disabled=false;count();show('');host.querySelector('select,input')?.focus();}
    catch(error){if(error.name!=='AbortError'&&id===generation)show(error.message,true);}
  }));
  value.addEventListener('input',()=>{if(state?.target.attribute==='agents')assignments();count();});
  find('form').addEventListener('submit',async event=>{
    event.preventDefault();if(!state||saving||locked||save.disabled)return;
    const submitted=control.value,invalid=validateTeamValue(state.target.attribute,submitted,state.maxBytes);
    if(invalid){show(invalid,true);return;}
    const csrf=find('csrf');saving=true;close.disabled=true;fields.disabled=true;count();show(t('ui.88daaeedfe4c', "저장 중…"));
    try {
      const result=await api(dialog.dataset.url,{method:'POST',headers:{'Content-Type':'application/json',[csrf.dataset.csrfHeader]:csrf.value},body:JSON.stringify({...state.target,value:submitted,version:state.version})});
      locked=true;reload=result.verified;if(result.verified)initial=submitted;show(result.message,!result.verified);
    }catch(error){locked=!teamRetryAllowed(error.status);show(error.message,true);}
    finally{saving=false;close.disabled=false;fields.disabled=false;count();}
  });
  const canClose=()=>!saving&&(!dirty()||confirm(t('ui.1ea0a53d0b54', "입력을 닫을까요? 필요한 내용은 먼저 복사해 주세요.")));
  close.addEventListener('click',()=>{if(canClose())dialog.close();});dialog.addEventListener('cancel',event=>{if(!canClose())event.preventDefault();});
  dialog.addEventListener('close',()=>{++generation;pending?.abort();if(reload)location.reload();});
}
if(typeof document!=='undefined') {
  document.querySelectorAll('[data-team-attribute-name]').forEach(cell=>attachTeamHelp(cell,cell.dataset.teamAttributeName));
  document.querySelectorAll('[data-team-editor]').forEach(mount);
}
