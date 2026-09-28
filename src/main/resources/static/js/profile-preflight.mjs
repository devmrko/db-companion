import {t} from './i18n.mjs';
export function preflightRequest(token,schema,profile){return {method:'POST',headers:{'Content-Type':'application/json',[token.header]:token.value},body:JSON.stringify({schema,profile})};}
if(typeof document!=='undefined')document.querySelectorAll('[data-profile-preflight]').forEach(button=>{
  const token=document.querySelector('[data-profile-preflight-csrf]'),host=document.querySelector('[data-profile-preflight-result]');
  button.addEventListener('click',async()=>{button.disabled=true;host.textContent=t('profilePreflight.running','점검 중…');
    try { const response=await fetch('/ai-profiles/preflight',preflightRequest({header:token.dataset.profilePreflightCsrf,value:token.value},token.dataset.profilePreflightSchema,token.dataset.profilePreflightName)); const data=await response.json(); if(!response.ok)throw new Error(data.error||t('profilePreflight.failed','점검을 완료하지 못했습니다.')); host.replaceChildren();
      const stamp=document.createElement('p');stamp.className='app-preflight-stamp';stamp.textContent=`${data.checkedAt} · ${data.fingerprint}${data.stale?' · '+t('profilePreflight.stale','STALE'):''}`;host.append(stamp);
      for(const check of data.checks){const row=document.createElement('section'),title=document.createElement('h3'),summary=document.createElement('p'),detail=document.createElement('p');row.className='app-preflight-check';row.dataset.tone=check.tone;title.textContent=`${t('profilePreflight.tone.'+check.tone,check.tone)} · ${check.key}`;summary.textContent=check.summary;detail.textContent=`${check.reason} ${check.method}`;row.append(title,summary,detail);host.append(row);}
    } catch(error){host.textContent=error.message;} finally {button.disabled=false;}
  });
});
