import {t} from './i18n.mjs';

const labels={DEFINITIONS:['definitions','업무 용어 확인'],CONNECTION:['connection','DB 연결·트랜잭션 시작'],PROFILE:['profile','프로필·근거 확인'],AI:['ai','Select AI 응답 대기'],RESPONSE:['response','응답·참조 정보 정리'],QUERY:['query','SQL 실행'],FETCH:['fetch','결과 읽기'],CLEANUP:['cleanup','트랜잭션 정리']};
export const stageLabel=stage=>{const value=labels[stage];return value?t('aitest.progress.'+value[0],value[1]):t('aitest.progress.processing','처리 중');};
export const operationLabel=operation=>operation==='EXECUTE'?t('aitest.progress.execute','SQL 조회'):operation==='PROMPT'?t('aitest.showprompt','프롬프트 보기'):operation==='CHAT'?t('aitest.chat','대화'):t('aitest.sql','SQL 만들기');
export const seconds=value=>t('aitest.progress.seconds','{0}초',(Math.max(0,Number(value)||0)/1000).toFixed(2));
export const elapsedAt=(value,receivedAt,now)=>Math.max(0,value.elapsedMillis||0)+(value.status==='RUNNING'?Math.max(0,now-receivedAt):0);
export const terminal=value=>value?.status==='COMPLETE'||value?.status==='FAILED';

/** One read-only status request at a time. Never retries the generation/execution request. */
export function createProgressPoller({fetchStatus,changed,failed,schedule=setTimeout,cancel=clearTimeout,delay=1000}){
  let watching=false,timer=null,inflight=null,disposed=false;
  function plan(){if(watching&&!disposed&&timer===null)timer=schedule(()=>{timer=null;refresh();},delay);}
  function refresh(){
    if(disposed)return Promise.resolve();
    if(inflight)return inflight;
    inflight=Promise.resolve().then(fetchStatus).then(value=>{if(!disposed)changed(value);},error=>{if(!disposed)failed(error);}).finally(()=>{inflight=null;plan();});
    return inflight;
  }
  return {refresh,watch(value){watching=value;if(!value&&timer!==null){cancel(timer);timer=null;}if(value)plan();},dispose(){disposed=true;watching=false;if(timer!==null)cancel(timer);timer=null;}};
}

export function mountProgress(host,{fetchStatus,now=()=>performance.now()}={}){
  if(!host)return {setBusy(){},begin(){},settle:async()=>{},restore:async()=>{}};
  const live=host.querySelector('[data-progress-live]'),liveText=host.querySelector('[data-progress-live-text]'),liveTime=host.querySelector('[data-progress-live-time]'),history=host.querySelector('[data-progress-history]'),note=host.querySelector('[data-progress-note]');
  let records=[],received=now(),busy=false,requestId=null,started=0,notice='',ticker=null,watchStarted=0,disposed=false;
  const stageTimers=[];
  const expanded=new Map();
  const statusLabel=status=>status==='COMPLETE'?t('aitest.progress.complete','완료'):status==='FAILED'?t('aitest.progress.failed','실패'):t('aitest.progress.running','진행 중');
  const current=()=>requestId?records.find(row=>row.id===requestId):records.find(row=>row.status==='RUNNING');
  const node=(tag,text,className)=>{const item=document.createElement(tag);if(text!==undefined)item.textContent=text;if(className)item.className=className;return item;};
  function render(){
    history.replaceChildren();stageTimers.length=0;
    for(const id of expanded.keys())if(!records.some(row=>row.id===id))expanded.delete(id);
    for(const [index,row] of records.entries()){
      const detail=node('details',undefined,'app-test-timing');detail.dataset.progressId=row.id;detail.open=expanded.get(row.id)??index===0;
      const summary=node('summary'),title=node('span',operationLabel(row.operation)),status=node('span',statusLabel(row.status),'app-test-timing-state'),duration=node('span',seconds(row.elapsedMillis),'app-test-timing-duration');
      detail.dataset.status=row.status;summary.append(title,status,duration);detail.append(summary);stageTimers.push({node:duration,value:row});
      summary.addEventListener('click',()=>expanded.set(row.id,!detail.open));
      const stamp=node('p',new Date(row.startedAt).toLocaleString(),'app-filter-message');detail.append(stamp);
      const list=node('ol',undefined,'app-test-timing-steps');
      for(const step of row.steps||[]){const item=node('li');item.dataset.status=step.status;const time=node('span',seconds(step.elapsedMillis),'app-test-timing-duration');item.append(node('span',stageLabel(step.stage)),node('span',statusLabel(step.status),'app-test-timing-state'),time);list.append(item);stageTimers.push({node:time,value:step});}
      detail.append(list);history.append(detail);
    }
    tick();
  }
  function tick(){
    if(disposed)return;
    const time=now(),row=current(),active=row?.status==='RUNNING',visible=busy||active;
    host.hidden=!visible&&!records.length&&!notice;live.hidden=!visible;
    live.setAttribute('aria-busy',String(visible));
    const step=row?.steps?.find(item=>item.status==='RUNNING');
    const label=notice|| (active?`${operationLabel(row.operation)} · ${step?stageLabel(step.stage):t('aitest.progress.processing','처리 중')}`:t('aitest.progress.waiting','요청 처리·응답 대기'));
    // Announce stage transitions, not the elapsed timer on every animation tick.
    if(liveText.textContent!==label)liveText.textContent=label;
    liveTime.textContent=seconds(active?elapsedAt(row,received,time):time-started);
    for(const item of stageTimers)item.node.textContent=seconds(elapsedAt(item.value,received,time));
    note.textContent=notice;note.hidden=!notice;
    if(visible&&time-watchStarted>600_000){notice=t('aitest.progress.unconfirmed','진행 상태 확인이 지연되고 있습니다. 새로고침으로 확인해 주세요. 실행을 재요청하지 않았습니다.');poller.watch(false);}
    else poller.watch(visible);
    if(visible&&ticker===null)ticker=setTimeout(()=>{ticker=null;tick();},250);
  }
  const poller=createProgressPoller({fetchStatus,changed(value){records=Array.isArray(value)?value:[];received=now();notice='';render();},failed(){notice=t('aitest.progress.unconfirmed','진행 상태 확인이 지연되고 있습니다. 새로고침으로 확인해 주세요. 실행을 재요청하지 않았습니다.');tick();}});
  const api={
    setBusy(value){if(value&&!busy){started=now();watchStarted=started;}busy=value;tick();},
    begin(id){requestId=id;started=now();watchStarted=started;notice='';tick();poller.refresh();},
    async settle(id){await poller.refresh();if(requestId===id&&!terminal(current()))await poller.refresh();if(requestId===id&&terminal(current()))requestId=null;tick();},
    async restore(){watchStarted=now();await poller.refresh();},
    dispose(){disposed=true;poller.dispose();if(ticker!==null)clearTimeout(ticker);ticker=null;}
  };
  globalThis.addEventListener?.('pagehide',()=>api.dispose(),{once:true});
  return api;
}
