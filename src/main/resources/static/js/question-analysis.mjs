import {assistantApi} from './ai-assistant.mjs';
import {t} from './i18n.mjs';

export const analysisLanguage=locale=>/^zh(?:-|$)/i.test(locale)?'zh-CN':/^en(?:-|$)/i.test(locale)?'en':/^ja(?:-|$)/i.test(locale)?'ja':'ko';
export const analysisEndpoint=(path,language)=>path+'?'+new URLSearchParams({language});
export const configurationMatches=(value,language)=>value?.language===language;

/** Explicit language; read-only status + copy-only SQL. Never installs on load or search. */
export function mountQuestionAnalysis(root,{changed=()=>{},isBusy=()=>false}={}){
  const host=root.querySelector('[data-question-analysis]');
  if(!host)return {language:()=>analysisLanguage(document.documentElement?.lang||'ko'),load:async()=>{},controls:()=>{}};
  const get=name=>host.querySelector(`[data-analysis-${name}]`),select=get('language');
  select.value=analysisLanguage(document.documentElement.lang);
  let sequence=0,loading=false,config=null;
  const controls=pending=>{select.disabled=pending||loading;get('refresh').disabled=pending||loading;get('copy').disabled=pending||loading||!config?.setupSql;};
  async function load(){
    const serial=++sequence,language=select.value;loading=true;config=null;get('setup').hidden=true;get('setup').open=false;get('sql').textContent='';controls(isBusy());
    get('status').className='app-filter-message';get('status').textContent=t('questionAnalysis.checking','분석 설정을 확인하는 중…');
    try{
      const value=await assistantApi(analysisEndpoint('/question-analysis/configuration',language));
      if(serial!==sequence||!configurationMatches(value,select.value))return;
      config=value;
      get('status').className=value.state==='READY'?'app-filter-message':'app-alert';
      get('status').textContent=`${value.owner} · ${select.selectedOptions[0].textContent} · ${t('questionAnalysis.state.'+value.state,value.state)} · ${value.lexer} · ${value.policy}`;
      if(value.state==='MISSING')get('status').textContent+=' — '+t('questionAnalysis.needsSetup','아래 설정 SQL을 적용한 뒤 설정 상태를 다시 확인하세요.');
      if(['PARTIAL','CONFLICT'].includes(value.state))get('status').textContent+=' — '+t('questionAnalysis.inspect','기존 구성을 확인해 주세요. 덮어쓰기·재설치는 하지 않습니다.');
      get('sql').textContent=value.setupSql||'';get('setup').hidden=!value.setupSql;
    }catch(ex){if(serial===sequence){get('status').className='app-alert is-error';get('status').textContent=ex.message;}}
    finally{if(serial===sequence){loading=false;controls(isBusy());}}
  }
  select.addEventListener('change',()=>{changed();load();});
  get('refresh').addEventListener('click',()=>{changed();load();});
  get('copy').addEventListener('click',async()=>{
    if(!config?.setupSql||loading)return;
    try{await navigator.clipboard.writeText(config.setupSql);get('copy').textContent=t('questionAnalysis.copied','복사됨');}
    catch{get('status').textContent=t('questionAnalysis.copyError','SQL을 직접 선택해 복사해 주세요.');}
  });
  return {language:()=>select.value,load,controls};
}
