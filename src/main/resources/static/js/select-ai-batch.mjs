import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';

export const batchProfileLabel=plan=>{
  const profile=plan?.profile;
  return profile?.selection?.name
    ?profile.selection.owner+'.'+profile.selection.name+' · '+profile.provider+' / '+(profile.model||'default')
    :t('aitest.batch.profileUnknown','준비 당시 프로필 정보 미확인');
};

/** Sequential, one-shot generation only. The caller supplies explicit consent and transport. */
export async function runBatchPlan(initial,{consent,next,status,onChange=()=>{},shouldStop=()=>false}){
  if(!consent)throw new Error(t('aitest.batch.consentRequired','전송 동의가 필요합니다.'));
  if(!initial?.generation||!Array.isArray(initial.items)||initial.items.length<1||initial.items.length>20)throw new Error(t('aitest.batch.planInvalid','실행 계획을 확인해 주세요.'));
  let plan={...initial,items:initial.items.map(item=>({...item}))};
  while(!shouldStop()){
    const item=plan.items.find(value=>value.status==='PENDING');
    if(!item)break;
    const result=await next({generation:plan.generation,token:item.token,consent:true});
    if(result?.token!==item.token)throw new Error(t('aitest.batch.responseMismatch','응답과 실행 계획이 다릅니다. 자동 재시도하지 않습니다.'));
    plan={...plan,items:plan.items.map(value=>value.token===item.token?result:value)};
    onChange(plan);
    const current=await status();
    if(!current||current.generation!==plan.generation||!Array.isArray(current.items)
      ||current.items.length!==plan.items.length
      ||current.items.some((value,index)=>value.token!==plan.items[index].token))
      throw new Error(t('aitest.batch.planChanged','실행 계획이 변경되었습니다. 자동 재시도하지 않습니다.'));
    plan=current;onChange(plan);
  }
  return plan;
}

export function mountBatch(root){
  const get=name=>root.querySelector('[data-batch-'+name+']');
  if(!get('panel'))return;
  const csrf=root.querySelector('[data-problem-csrf]');
  const post=(path,value)=>assistantApi('/ai-test/batch/'+path,assistantPost(csrf,value));
  const readPlan=async()=>{const value=await assistantApi('/ai-test/batch/status');return value?.plan===undefined?value:value.plan;};
  let plan=null,busy=false,running=false,stopped=false,cancelRequest=null;
  const selected=new Map(),picked=new Set(),saved=new Map(),uncertain=new Set();
  const message=(value,error=false)=>{get('message').textContent=value;get('message').className=error?'app-alert is-error':'app-filter-message';};
  function controls(){
    get('selection').textContent=t('aitest.batch.selection','선택 질문 {0} / 최대 20',selected.size);
    const summary=get('selection-summary');if(summary)summary.textContent=get('selection').textContent;
    for(const name of ['profiles','profile','restore'])get(name).disabled=busy;
    get('prepare').disabled=busy||!selected.size||!get('profile').value;
    get('run').disabled=busy||!plan?.profile?.selection?.name||!get('consent').checked||!plan.items.some(i=>i.status==='PENDING');
    get('cancel').disabled=!plan||!plan.items.some(i=>i.status==='PENDING')||(busy&&!running);
    get('save').disabled=busy||!plan||!get('save-consent').checked||!plan.items.some(i=>picked.has(i.token)&&!saved.has(i.token)&&!uncertain.has(i.token));
    root.querySelectorAll('[data-batch-parent]').forEach(node=>{node.checked=selected.has(node.dataset.batchParent);node.disabled=busy;});
  }
  function render(){
    const host=get('results');host.replaceChildren();
    get('plan').textContent=plan?t('aitest.batch.plan','준비 당시 프로필: {0}\n등록 질문 {1}건 · 최대 {1}회 SQL 생성 · 남은 질문 {2}건\n유료 호출이 발생할 수 있습니다. 생성 SQL은 자동 실행하지 않습니다. 결과는 별도 선택 저장합니다.',batchProfileLabel(plan),plan.items.length,plan.items.filter(i=>i.status==='PENDING').length):t('aitest.batch.noPlan','아직 실행 계획이 없습니다.');
    for(const item of plan?.items||[]){
      const section=document.createElement('section'),title=document.createElement('label'),pick=document.createElement('input'),text=document.createElement('pre');
      const finished=['SUCCEEDED','FAILED'].includes(item.status);
      pick.type='checkbox';pick.checked=picked.has(item.token);pick.disabled=!finished||saved.has(item.token)||uncertain.has(item.token);
      pick.setAttribute('aria-label',t('aitest.batch.pickSave','이 결과를 저장 대상으로 선택'));
      pick.addEventListener('change',()=>{if(pick.checked)picked.add(item.token);else picked.delete(item.token);controls();});
      title.append(pick,document.createTextNode(' '+item.question+' · '+item.status+(saved.has(item.token)?' · 저장됨':uncertain.has(item.token)?' · 저장 확인 필요':'')));
      text.className='app-preview-value';text.textContent=[item.result,item.error].filter(Boolean).join('\n');
      section.append(title,text);host.append(section);
    }
    controls();
  }
  root.addEventListener('change',event=>{
    const node=event.target;if(!node.matches?.('[data-batch-parent]'))return;
    if(node.checked){if(selected.size>=20&&!selected.has(node.dataset.batchParent)){node.checked=false;message(t('aitest.batch.maximum','최대 20개 질문을 선택해 주세요.'),true);return;}selected.set(node.dataset.batchParent,node.dataset.batchQuestion);}
    else selected.delete(node.dataset.batchParent);controls();
  });
  root.addEventListener('problem-list-rendered',controls);
  get('profile').addEventListener('change',()=>{if(plan){plan=null;picked.clear();saved.clear();uncertain.clear();get('consent').checked=false;get('save-consent').checked=false;render();message(t('aitest.batch.profileChanged','프로필이 변경되어 기존 실행 계획과 동의를 무효화했습니다. 다시 준비해 주세요.'));}controls();});
  get('consent').addEventListener('change',controls);
  get('save-consent').addEventListener('change',controls);
  get('profiles').addEventListener('click',async()=>{
    get('consent').checked=false;
    busy=true;controls();try{const value=await assistantApi('/ai-test/options');const select=get('profile');select.replaceChildren(new Option(t('assistant.select','프로필 선택'),''));
      for(const profile of value.profiles||[])select.append(new Option(profile.name+' · '+(profile.model||''),profile.name));
      message(t('aitest.batch.profilesLoaded','프로필 목록을 읽었습니다. AI 호출은 하지 않았습니다.'));
    }catch(error){message(error.message,true);}finally{busy=false;controls();}
  });
  get('prepare').addEventListener('click',async()=>{
    busy=true;controls();try{plan=await post('prepare',{parentIds:[...selected.keys()],profile:get('profile').value});picked.clear();saved.clear();uncertain.clear();stopped=false;get('consent').checked=false;get('save-consent').checked=false;render();message(t('aitest.batch.prepareReady','아래 질문과 호출 수를 확인한 뒤 전송에 동의해 주세요.'));}
    catch(error){message(error.message,true);}finally{busy=false;controls();}
  });
  get('restore').addEventListener('click',async()=>{
    busy=true;controls();try{plan=await readPlan();picked.clear();saved.clear();uncertain.clear();get('consent').checked=false;get('save-consent').checked=false;render();message(plan?t('aitest.batch.restored','현재 로그인 세션의 계획입니다. 새 AI 호출은 하지 않았습니다.'):t('aitest.batch.noSessionPlan','이 세션에 실행 계획이 없습니다.'));}
    catch(error){message(error.message,true);}finally{busy=false;controls();}
  });
  get('run').addEventListener('click',async()=>{
    if(busy||!plan||!get('consent').checked)return;
    busy=true;running=true;stopped=false;cancelRequest=null;controls();
    try{plan=await runBatchPlan(plan,{consent:true,next:value=>post('next',value),status:readPlan,shouldStop:()=>stopped,onChange:value=>{plan=value;render();}});message(stopped?t('aitest.batch.stopped','남은 항목 처리를 중단했습니다. 이미 전송한 호출은 결과 확인이 필요합니다.'):t('aitest.batch.finished','계획의 순차 처리를 마쳤습니다. 실패 항목은 자동 재시도하지 않았습니다.'));}
    catch(error){stopped=true;message(error.message+' '+t('aitest.batch.noRetryCheck','자동 재시도하지 않았습니다. 세션 계획을 다시 조회해 상태를 확인해 주세요.'),true);}
    finally{if(stopped&&cancelRequest){try{await cancelRequest;const current=await readPlan();if(current?.generation===plan?.generation)plan=current;}catch{message(t('aitest.batch.cancelUnknown','취소 후 상태를 확인하지 못했습니다. 세션 계획을 다시 조회해 주세요.'),true);}}busy=false;running=false;render();}
  });
  get('cancel').addEventListener('click',async()=>{
    if(!plan)return;stopped=true;
    try{cancelRequest=post('cancel',{generation:plan.generation,token:'',consent:false});plan=await cancelRequest;render();message(t('aitest.batch.cancelled','미실행 항목을 취소했습니다. 진행 중인 외부 호출은 취소됐다고 단정하지 않습니다.'));}
    catch(error){message(error.message+' '+t('aitest.batch.screenStopped','화면에서 다음 전송은 중단했습니다.'),true);}
  });
  get('save').addEventListener('click',async()=>{
    if(busy||!plan||!get('save-consent').checked)return;
    busy=true;controls();try{
      for(const item of plan.items.filter(i=>picked.has(i.token)&&['SUCCEEDED','FAILED'].includes(i.status)&&!saved.has(i.token)&&!uncertain.has(i.token))){
        try{const value=await post('save',{generation:plan.generation,token:item.token,confirmed:true});saved.set(item.token,value.id);render();}
        catch(error){uncertain.add(item.token);throw new Error(error.message+' '+t('aitest.batch.saveUnknown','저장 여부를 문제 질문 상세에서 확인해 주세요. 자동 재전송하지 않습니다.'));}
      }
      message(t('aitest.batch.saved','선택한 결과를 원래 문제 질문의 새 실행 기록으로 저장했습니다. 추가 AI 호출은 하지 않았습니다.'));
    }catch(error){message(error.message,true);}finally{busy=false;render();}
  });
  controls();
}
if(typeof document!=='undefined')document.querySelectorAll('[data-problem-questions]').forEach(mountBatch);
