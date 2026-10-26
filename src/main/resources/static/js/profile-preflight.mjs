import {t} from './i18n.mjs';
import {observedTime} from './observed-time.mjs';
export function preflightRequest(token,schema,profile){return {method:'POST',headers:{'Content-Type':'application/json',[token.header]:token.value},body:JSON.stringify({schema,profile})};}
if(typeof document!=='undefined')document.querySelectorAll('[data-profile-preflight]').forEach(button=>{
  const token=document.querySelector('[data-profile-preflight-csrf]'),host=document.querySelector('[data-profile-preflight-result]');
  button.addEventListener('click',async()=>{button.disabled=true;host.textContent=t('profilePreflight.running','점검 중…');
    try { const response=await fetch('/ai-profiles/preflight',preflightRequest({header:token.dataset.profilePreflightCsrf,value:token.value},token.dataset.profilePreflightSchema,token.dataset.profilePreflightName)); const data=await response.json(); if(!response.ok)throw new Error(data.error||t('profilePreflight.failed','점검을 완료하지 못했습니다.')); host.replaceChildren();
      const stamp=document.createElement('p');stamp.className='app-preflight-stamp';stamp.textContent=`${observedTime(data.checkedAt)} · ${data.fingerprint}${data.stale?' · '+t('profilePreflight.stale','STALE'):''}`;host.append(stamp);
      for(const check of data.checks){const row=document.createElement('section'),title=document.createElement('h3'),summary=document.createElement('p'),detail=document.createElement('details'),caption=document.createElement('summary'),raw=document.createElement('p');row.className='app-preflight-check';row.dataset.tone=check.tone;title.textContent=`${t('profilePreflight.tone.'+check.tone,check.tone)} · ${t('auditFixes.preflight.'+check.key,check.key)}`;summary.textContent=t('auditFixes.preflight.'+check.key+'.note',check.reason);caption.textContent=t('auditFixes.preflight.details','점검 상세 · 원천 진단');raw.textContent=`${check.summary} · ${check.reason} ${check.method}`;detail.append(caption,raw);row.append(title,summary,detail);host.append(row);}
    } catch(error){host.textContent=error.message;} finally {button.disabled=false;}
  });
});
