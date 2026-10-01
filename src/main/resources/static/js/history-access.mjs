import {t} from './i18n.mjs';

export const permissionOperation=operation=>['READ','MANAGE','DENY','INHERIT'].includes(operation);
export function accessRequest(token,tables,operation,users,allUsers) {
  return {token,tables,operation,users:permissionOperation(operation)&&!allUsers?users:[],allUsers:permissionOperation(operation)&&allUsers};
}
export function grantText(grant) {
  return `${grant.user} · ${grant.manage?t('history.access.manage','조회·수집 ON/OFF 허용'):grant.read?t('history.access.read','조회 허용'):t('history.access.deny','조회·관리 제외')}`;
}
export function mountHistoryAccess(root) {
  const dialog=root.querySelector('[data-access-dialog]'),form=root.querySelector('[data-access-form]');
  const message=root.querySelector('[data-access-message]'),results=root.querySelector('[data-access-results]');
  const operation=root.querySelector('[data-access-operation]'),all=root.querySelector('[data-access-all]'),users=root.querySelector('[data-access-users]');
  let preview,busy=false,generation=0;
  const resetConsent=()=>{root.querySelector('[data-access-confirm]').checked=false;};
  const controls=()=>{root.querySelector('[data-access-principals]').hidden=!permissionOperation(operation.value);users.disabled=all.checked;resetConsent();};
  operation.addEventListener('change',controls);all.addEventListener('change',controls);users.addEventListener('change',resetConsent);
  const request=async(url,body)=> {
    const csrf=root.querySelector('[data-access-csrf]');
    const response=await fetch(url,{method:body?'POST':'GET',headers:{Accept:'application/json',...(body?{'Content-Type':'application/json',[csrf.dataset.header]:csrf.value}:{})},...(body?{body:JSON.stringify(body)}:{})});
    if(response.redirected)throw new Error(t('ui.e0cca5f2b4e4','로그인 세션이 만료되었습니다.'));
    const data=await response.json();if(!response.ok)throw new Error(data.error||t('history.access.failed','DB 작업을 확인하지 못했습니다.'));return data;
  };
  root.querySelector('[data-access-open]').addEventListener('click',async()=> {
    if(busy)return;
    const current=++generation;preview=null;form.hidden=true;results.textContent='';dialog.showModal();
    message.textContent=t('history.access.loading','대상과 현재 권한을 확인하고 있습니다…');
    const url=new URL(root.dataset.url,location.href);url.searchParams.set('schema',root.dataset.schema);
    if(root.dataset.table)url.searchParams.set('table',root.dataset.table);
    else { const profile=document.querySelector('[data-profile-filter]')?.value;
      if(!profile){message.textContent=t('history.access.profile','Select AI 프로필을 선택해 주세요.');return;}url.searchParams.set('profile',profile); }
    try {
      const data=await request(url);if(current!==generation||!dialog.open)return;preview=data;
      const targets=root.querySelector('[data-access-targets]');targets.replaceChildren();users.replaceChildren();
      for(const item of data.items) {
        const label=document.createElement('label'),input=document.createElement('input'),text=document.createElement('span'),details=document.createElement('small');
        input.type='checkbox';input.value=item.name;input.checked=true;input.dataset.accessTarget='';input.addEventListener('change',resetConsent);
        text.textContent=`${data.schema}.${item.name}`;details.textContent=[item.state?.message||t('history.access.verifyOnApply','설치·코드·권한은 적용 전에 재검증합니다.'),...item.grants.map(grantText)].filter(Boolean).join(' / ');
        text.append(details);label.append(input,text);targets.append(label);
      }
      for(const user of data.users){const option=document.createElement('option');option.value=user;option.textContent=user;users.append(option);}
      operation.value='ENABLE';all.checked=false;controls();form.hidden=false;message.textContent='';
    }catch(error){if(current===generation)message.textContent=error.message;}
  });
  root.querySelector('[data-access-close]').addEventListener('click',()=>{if(!busy){generation++;dialog.close();}});
  dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();else generation++;});
  form.addEventListener('submit',async event=> {
    event.preventDefault();if(busy||!preview||!root.querySelector('[data-access-confirm]').checked)return;
    const tables=Array.from(root.querySelectorAll('[data-access-target]:checked'),node=>node.value);
    if(!tables.length){message.textContent=t('history.access.selection','확인한 목록에서 대상 객체를 선택해 주세요.');return;}
    const selected=Array.from(users.selectedOptions,node=>node.value);
    if(permissionOperation(operation.value)&&!all.checked&&!selected.length){message.textContent=t('history.access.user','현재 DB에 존재하는 사용자를 선택해 주세요.');return;}
    const body=accessRequest(preview.token,tables,operation.value,selected,all.checked);
    busy=true;root.querySelector('[data-access-apply]').disabled=true;form.inert=true;message.textContent=t('history.access.running','적용 중… 창을 닫지 마세요.');
    try {const data=await request(root.dataset.url,body);results.textContent=data.map(item=>`${item.success?'✓':'!'} ${item.table} · ${item.message}`).join('\n');message.textContent='';}
    catch(error){message.textContent=error.message;}
    finally{busy=false;preview=null;form.hidden=true;form.inert=false;root.querySelector('[data-access-apply]').disabled=false;document.dispatchEvent(new CustomEvent('metadata-history-access-changed'));}
  });
}
if(typeof document!=='undefined')document.querySelectorAll('[data-history-access]').forEach(mountHistoryAccess);
