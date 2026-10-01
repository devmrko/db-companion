import {t} from './i18n.mjs';
import {mountSourceViewer} from './source-viewer.mjs';

export function policyFunctionTarget(policy){
  const p=policy?.properties;
  if(!p||typeof p.PF_OWNER!=='string'||!p.PF_OWNER||typeof p.FUNCTION!=='string'||!p.FUNCTION)return null;
  if(p.PACKAGE!=null&&typeof p.PACKAGE!=='string')return null;
  const owner=p.PF_OWNER,object=p.PACKAGE||p.FUNCTION,member=p.PACKAGE?p.FUNCTION:null;
  const parts=[owner,object,...(member?[member]:[])];
  if(parts.some(part=>part.includes('\0')))return null;
  return {owner,object,member,label:parts.join('.'),reference:parts.map(part=>'"'+part.replaceAll('"','""')+'"').join('.')};
}
export function matchingFunctionDetail(details,target){
  if(!Array.isArray(details)||!target)return null;
  const matches=details.filter(detail=>{
    const d=detail?.definition;
    return d?.owner===target.owner&&d.object===target.object&&(d.member??null)===target.member&&d.type===(target.member?'PACKAGE':'FUNCTION');
  });
  return matches.length===1?matches[0]:null;
}
export function mountVpdFunctionSource(dialog,{schema,url}){
  const get=name=>dialog.querySelector(`[data-vpd-source-${name}]`);
  let generation=0,pending=null;
  function clear(){++generation;pending?.abort();pending=null;get('code').replaceChildren();get('code').setAttribute('aria-busy','false');}
  dialog.addEventListener('close',clear);get('close').addEventListener('click',()=>dialog.close());
  return {
    close(){clear();if(dialog.open)dialog.close();},
    async open(policy){
      const target=policyFunctionTarget(policy);if(!target)return;
      clear();const version=generation;pending=new AbortController();
      get('target').textContent=target.label;get('scope').hidden=!target.member;
      get('status').textContent=t('ui.8bf609c884ca','불러오는 중…');get('code').setAttribute('aria-busy','true');
      if(!dialog.open)dialog.showModal();
      try{
        const request=new URL(url,location.origin);request.searchParams.set('schema',schema);request.searchParams.set('name',target.reference);
        const response=await fetch(request,{headers:{Accept:'application/json'},cache:'no-store',signal:pending.signal});
        if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1','로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'));
        if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(t('functions.loadError','함수 정보를 조회하지 못했습니다. 조회 권한을 확인해 주세요.'));
        const details=await response.json();if(!response.ok)throw new Error(details.error||t('functions.loadError','함수 정보를 조회하지 못했습니다.'));
        if(version!==generation||!dialog.open)return;
        const detail=matchingFunctionDetail(details,target);
        if(!detail||!detail.definition.sections?.length){get('status').textContent=t('ui.6d893718182f','객체는 확인되었으나 소스가 공개되지 않았거나 조회 권한이 없습니다.');return;}
        get('status').textContent=detail.objects.map(object=>[object.type,object.status,object.modified].filter(Boolean).join(' · ')).join(' / ');
        mountSourceViewer(get('code'),detail.definition.sections,target.member);
      }catch(error){if(version===generation&&dialog.open&&error.name!=='AbortError')get('status').textContent=error.message;}
      finally{if(version===generation)get('code').setAttribute('aria-busy','false');}
    }
  };
}
