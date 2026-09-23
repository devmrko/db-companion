import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
export function explanationRequest(preview,consent){return {token:preview.token,consent:consent===true};}
export function mountFunctionExplain(dialog){
  const get=name=>dialog.querySelector(`[data-ae-${name}]`);let preview=null,running=false,spent=false,generation=0;
  const message=(text,error=false)=>{get('message').textContent=text;get('message').className=error?'app-alert is-error':'app-filter-message';};
  const enable=()=>{get('run').disabled=!preview||running||spent||!get('consent').checked;get('close').disabled=running;get('consent').disabled=running||spent;};
  const open=async(schema,reference)=>{
    if(running)return;const id=++generation;preview=null;spent=false;get('consent').checked=false;get('fields').hidden=true;get('result').hidden=true;get('result').textContent='';get('source').textContent='';get('target').textContent=reference;get('profile').textContent='';enable();
    message(t('ui.8bf609c884ca','불러오는 중…'));dialog.showModal();
    try{const data=await assistantApi(dialog.dataset.preview,assistantPost(get('csrf'),{schema,reference}));if(id!==generation||!dialog.open)return;
      preview=data;get('target').textContent=data.reference;get('profile').textContent=`${data.profile.selection.owner}.${data.profile.selection.name} · ${data.profile.provider} / ${data.profile.model||t('assistant.defaultModel','제공자 기본 모델')}`;
      get('source').textContent=data.source;get('source-details').open=false;
      get('scope').textContent=t(data.packageSource?'assistant.packageScope':'assistant.functionScope',data.packageSource?'패키지 전체 명세·본문 · {0}자':'함수 소스 · {0}자',data.characters.toLocaleString());
      get('fields').hidden=false;message('');enable();
    }catch(ex){if(id===generation)message(ex.message,true);}
  };
  get('consent').addEventListener('change',enable);
  get('run').addEventListener('click',async()=>{
    if(get('run').disabled)return;running=true;spent=true;enable();message(t('assistant.generating','설명 생성 중…'));
    try{const result=await assistantApi(dialog.dataset.run,assistantPost(get('csrf'),explanationRequest(preview,get('consent').checked)));
      get('result').textContent=result.text;get('result').hidden=false;get('fields').hidden=true;message(t('assistant.reviewResult','AI가 작성한 설명입니다. 실제 코드와 함께 확인하세요.'));
    }catch(ex){message(ex.message,true);}finally{running=false;enable();}
  });
  get('close').addEventListener('click',()=>{if(!running)dialog.close();});
  dialog.addEventListener('cancel',event=>{if(running)event.preventDefault();});
  dialog.addEventListener('close',()=>{++generation;preview=null;get('source').textContent='';get('result').textContent='';});
  return open;
}
