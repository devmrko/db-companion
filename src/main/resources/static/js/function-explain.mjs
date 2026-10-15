import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
export function explanationRequest(preview,consent){return {token:preview.token,consent:consent===true};}
export function explanationPreviewRequest(schema,reference,businessContext=''){return {schema,reference,businessContext};}
export function mountFunctionExplain(dialog){
  const get=name=>dialog.querySelector(`[data-ae-${name}]`);let preview=null,running=false,preparing=false,spent=false,generation=0,target=null,refining=false;
  const message=(text,error=false)=>{get('message').textContent=text;get('message').className=error?'app-alert is-error':'app-filter-message';};
  const enable=()=>{const busy=running||preparing;get('run').disabled=!preview||busy||spent||!get('consent').checked;get('close').disabled=busy;get('consent').disabled=busy||spent;get('context').disabled=busy;get('refine').disabled=busy||!get('context').value.trim();};
  const prepare=async(context)=>{
    const id=++generation;preview=null;spent=false;preparing=true;get('consent').checked=false;get('fields').hidden=true;get('source').textContent='';enable();
    message(t('ui.8bf609c884ca','불러오는 중…'));
    try{const data=await assistantApi(dialog.dataset.preview,assistantPost(get('csrf'),explanationPreviewRequest(target.schema,target.reference,context)));if(id!==generation||!dialog.open)return;
      preview=data;get('target').textContent=data.reference;get('profile').textContent=`${data.profile.selection.owner}.${data.profile.selection.name} · ${data.profile.provider} / ${data.profile.model||t('assistant.defaultModel','제공자 기본 모델')}`;
      get('source').textContent=JSON.stringify(JSON.parse(data.source),null,2);get('source-details').open=false;
      get('scope').textContent=t(data.packageSource?'assistant.packageScope':'assistant.functionScope',data.packageSource?'패키지 전체 명세·본문 · {0}자':'함수 소스 · {0}자',data.characters.toLocaleString());
      get('fields').hidden=false;message('');if(refining)get('fields').scrollIntoView({block:'nearest'});
    }catch(ex){if(id===generation)message(ex.message,true);}finally{if(id===generation){preparing=false;enable();}}
  };
  const open=async(schema,reference)=>{
    if(running||preparing)return;target={schema,reference};refining=false;get('context').value='';get('business').hidden=true;get('result-panel').hidden=true;get('refined-panel').hidden=true;get('result').textContent='';get('refined').textContent='';get('target').textContent=reference;get('profile').textContent='';dialog.showModal();await prepare('');
  };
  get('context').addEventListener('input',()=>{++generation;preview=null;spent=false;get('consent').checked=false;get('fields').hidden=true;get('refined-panel').hidden=true;get('refined').textContent='';enable();});
  get('refine').addEventListener('click',async()=>{if(get('refine').disabled)return;refining=true;await prepare(get('context').value);});
  get('consent').addEventListener('change',enable);
  get('run').addEventListener('click',async()=>{
    if(get('run').disabled)return;running=true;spent=true;enable();message(t('assistant.generating','설명 생성 중…'));
    try{const result=await assistantApi(dialog.dataset.run,assistantPost(get('csrf'),explanationRequest(preview,get('consent').checked)));
      if(refining){get('refined').textContent=result.text;get('refined-panel').hidden=false;}
      else{get('result').textContent=result.text;get('result-panel').hidden=false;get('business').hidden=false;}
      get('fields').hidden=true;get(refining?'refined-panel':'result-panel').scrollIntoView({block:'nearest'});message(t('assistant.reviewResult','AI가 작성한 설명입니다. 실제 코드와 함께 확인하세요.'));
    }catch(ex){message(ex.message,true);}finally{running=false;enable();}
  });
  get('close').addEventListener('click',()=>{if(!running&&!preparing)dialog.close();});
  dialog.addEventListener('cancel',event=>{if(running||preparing)event.preventDefault();});
  dialog.addEventListener('close',()=>{++generation;preview=null;target=null;get('source').textContent='';get('result').textContent='';get('refined').textContent='';get('context').value='';});
  return open;
}
