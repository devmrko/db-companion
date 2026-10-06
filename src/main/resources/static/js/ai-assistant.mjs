import {t} from './i18n.mjs';
export function assistantError(data,status){
  const error=new Error(data.error||`HTTP ${status}`);
  if(status===422&&data.diagnostic)error.diagnostic=data.diagnostic;
  return error;
}
export async function assistantApi(url,options={}){
  const response=await fetch(url,{cache:'no-store',credentials:'same-origin',headers:{Accept:'application/json'},...options});
  if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1','로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'));
  if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(t('ui.1930407cf479','응답을 확인하지 못했습니다. HTTP {0}',response.status));
  const data=await response.json();if(!response.ok)throw assistantError(data,response.status);return data;
}
export function assistantPost(csrf,data){return {method:'POST',headers:{'Content-Type':'application/json',[csrf.dataset.csrfHeader]:csrf.value},body:JSON.stringify(data)};}
export function profileLabel(profile){return `${profile.name} · ${profile.provider}${profile.model?' / '+profile.model:''}`;}
export function tokenValue(raw){
  const text=String(raw).trim();if(!text)return null;
  if(!/^[0-9]+$/.test(text)||!Number.isSafeInteger(Number(text))||Number(text)<1||Number(text)>2147483647)throw new Error(t('assistant.tokens.invalid','1 이상의 정수를 입력하세요. 모델별 허용 한도는 별도입니다.'));
  return Number(text);
}
if(typeof document!=='undefined')document.querySelectorAll('[data-assistant-settings]').forEach(root=>{
  const get=name=>root.querySelector(`[data-as-${name}]`),select=get('profile');let profiles=[],busy=false,tokens=null;
  const lock=value=>{busy=value;for(const name of ['save','clear','refresh','profile','tokens','tokens-session','tokens-reset','tokens-consent','tokens-persist'])get(name).disabled=value;get('save').disabled=value||!select.value;get('tokens-persist').disabled=value||!get('tokens-consent').checked;};
  const message=(value,error=false)=>{get('message').textContent=value;get('message').className=error?'app-alert is-error':'app-filter-message';};
  const current=value=>{get('current').textContent=value?t('assistant.current','사용 중 · {0}',`${value.owner}.${value.name}`):t('assistant.notSelected','선택된 프로필 없음');};
  const model=()=>{const item=profiles.find(p=>p.name===select.value);get('model').textContent=item?`${item.provider} / ${item.model||t('assistant.defaultModel','제공자 기본 모델')}`:'';get('save').disabled=busy||!item;get('tokens-panel').hidden=!tokens||tokens.profile!==select.value;};
  async function load(refresh=false){
    lock(true);message(t('ui.8bf609c884ca','불러오는 중…'));
    try{const data=await assistantApi(root.dataset.base+'/options?'+new URLSearchParams({refresh}));profiles=data.profiles;select.replaceChildren();
      const empty=document.createElement('option');empty.value='';empty.textContent=t('assistant.select','프로필 선택');select.append(empty);
      for(const item of profiles){const option=document.createElement('option');option.value=item.name;option.textContent=profileLabel(item);select.append(option);}
      select.value=data.selected?.name??'';tokens=data.tokens??null;
      get('tokens').value=tokens?.sessionMaxTokens??'';get('tokens').placeholder=tokens?.profileMaxTokens||t('assistant.tokens.default','기본값 (미설정)');
      get('tokens-consent').checked=false;
      get('tokens-current').textContent=tokens?t('assistant.tokens.current','DB 프로필: {0} · 이 세션: {1}',tokens.profileMaxTokens||t('assistant.tokens.default','기본값 (미설정)'),tokens.sessionMaxTokens??t('assistant.tokens.inherit','프로필 값 사용')):'';
      current(data.selected);model();message(profiles.length?'':t('assistant.noProfiles','사용 가능한 활성 프로필이 없습니다.'));return true;
    }catch(ex){profiles=[];tokens=null;select.replaceChildren();get('model').textContent='';message(ex.message,true);return false;}finally{lock(false);model();}
  }
  async function save(name){if(busy)return;lock(true);message('');try{
    await assistantApi(root.dataset.base+'/selection',assistantPost(get('csrf'),{name}));if(await load())message(t('assistant.saved','이 로그인 세션에 적용했습니다.'));
  }catch(ex){message(ex.message,true);}finally{lock(false);model();}}
  async function saveTokens(persistent=false,reset=false){
    if(busy||!tokens||tokens.profile!==select.value)return;
    try{
      const maxTokens=reset?null:tokenValue(get('tokens').value),consent=get('tokens-consent').checked;
      if(persistent&&(maxTokens===null||!consent))throw new Error(t('assistant.tokens.confirm','저장할 값과 DB 프로필 변경 확인이 필요합니다.'));
      lock(true);message('');
      await assistantApi(root.dataset.base+'/tokens',assistantPost(get('csrf'),{name:tokens.profile,version:tokens.version,maxTokens,persistent,consent}));
      if(await load())message(persistent?t('assistant.tokens.persisted','DB 프로필에 저장하고 세션 덮어쓰기를 해제했습니다.'):t('assistant.saved','이 로그인 세션에 적용했습니다.'));
    }catch(ex){message(ex.message,true);}finally{lock(false);model();}
  }
  get('tokens-session').addEventListener('click',()=>saveTokens());
  get('tokens-reset').addEventListener('click',()=>saveTokens(false,true));
  get('tokens-persist').addEventListener('click',()=>saveTokens(true));
  get('tokens-consent').addEventListener('change',()=>lock(busy));
  get('tokens').addEventListener('input',()=>{get('tokens-consent').checked=false;lock(busy);});
  get('form').addEventListener('submit',event=>{event.preventDefault();if(select.value)save(select.value);});
  select.addEventListener('change',model);get('clear').addEventListener('click',()=>save(''));get('refresh').addEventListener('click',()=>load(true));load();
});
