import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {mountQuestionAnalysis,analysisEndpoint} from './question-analysis.mjs';
import {automaticTermIds,renderDictionaryChoices,renderGroundedResult} from './ontology-grounding.mjs';
import {renderRdfWorkflow} from './ontology-rdf-workflow.mjs';
import {renderEvidence,evidenceReady,evidenceMatches,evidenceKey} from './select-ai-evidence.mjs';

const node=(tag,text)=>{const e=document.createElement(tag);if(text!=null)e.textContent=text;return e;};
const label=(key,fallback)=>t('aitest.'+key,fallback);
export function mountRdfEvidence(root,{isBusy,setBusy,changed,message,question}){
  const get=name=>root.querySelector(`[data-test-evidence-${name}]`);
  const post=(path,data={})=>assistantApi('/ontology-query/'+path,assistantPost(root.querySelector('[data-test-csrf]'),data));
  let selected=null,loadedSchema='',sequence=0,preview=null,searchId='';
  const enabled=()=>get('enabled').checked;
  const panel=node('section');get('checked').after(panel);
  get('definitions').closest('fieldset').hidden=true;
  const dialog=node('dialog');dialog.className='app-preview-dialog app-ontology-dialog';
  const title=node('h3',t('ontology.query.grounding.aiTransmission','질문과 사전 정의를 선택한 AI 도우미에 전송합니다. 호출 비용이 발생할 수 있습니다.'));
  const profile=node('p'),payload=node('pre'),consent=node('input');payload.className='app-preview-value app-test-prompt';consent.type='checkbox';
  title.id='test-rdf-preview-title';dialog.setAttribute('aria-labelledby',title.id);
  const consentLabel=node('label');consentLabel.append(consent,node('span',t('ontology.consent','위 메타데이터의 외부 전송과 AI 사용량 발생을 확인했습니다.')));
  const send=node('button',label('rdfSearch','AI 검색 질문 생성 · RDF 검색')),close=node('button',t('aitest.close','닫기'));send.type=close.type='button';
  const status=node('p');status.setAttribute('role','status');
  send.className='btn app-btn app-btn-primary';close.className='btn app-btn app-btn-quiet';
  dialog.append(title,profile,payload,consentLabel,status,send,close);root.append(dialog);
  const clearSelected=()=>{selected=null;renderEvidence(get('selected'),null);changed();};
  function invalidate(){sequence++;preview=null;searchId='';dialog.close();consent.checked=false;panel.replaceChildren();clearSelected();}
  const analysis=mountQuestionAnalysis(root,{changed:invalidate,isBusy});
  function controls(pending){
    get('enabled').disabled=pending;get('panel').hidden=!enabled();get('schema').disabled=true;
    for(const name of ['anchor','refresh'])get(name).disabled=pending;
    get('find').disabled=pending||!question().trim()||!get('schema').value;
    get('hint').textContent=enabled()&&!selected?label('rdfHint','용어사전으로 검색 질문을 보강한 뒤 RDF 근거를 찾고 적용하세요. AI 호출 전에 전송 내용을 확인합니다.'):'';
    for(const input of panel.querySelectorAll('button,input,select'))input.disabled=pending||input.hasAttribute('data-local-disabled');
    send.disabled=pending||!preview||!consent.checked;close.disabled=pending;consent.disabled=pending;analysis.controls(pending);
  }
  async function work(fn){if(isBusy())return;setBusy(true);message('');status.textContent='';try{await fn();}catch(ex){message(ex.message,true);status.textContent=ex.message;}finally{setBusy(false);}}
  async function options(refresh=false){
    const schema=get('schema').value;if(!schema||!refresh&&loadedSchema===schema)return;
    const data=await assistantApi('/ontology-query/options?'+new URLSearchParams({schema,refresh}));loadedSchema=schema;
    get('anchor').replaceChildren(new Option(label('evQuestionSearch','질문으로 찾기'),''));
    for(const row of data.tables)get('anchor').append(new Option(row.name+(row.concept?' · '+row.concept:''),row.name));
    get('checked').textContent=`${schema} · ${new Date(data.checkedAt).toLocaleString()}`;await analysis.load();
  }
  function showPreview(value){preview=value;consent.checked=false;const p=value.request.profile;profile.textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model||''}`;payload.textContent=JSON.stringify(JSON.parse(value.request.source),null,2);dialog.showModal();}
  async function start(){
    invalidate();await options();const serial=sequence,q=question(),schema=get('schema').value,anchor=get('anchor').value;
    const dictionary=await post('interpret',{schema,question:q,anchor});
    const prepare=async termIds=>{const value=await post(analysisEndpoint('ai-interpret-preview',analysis.language()),{schema,question:q,anchor,dictionaryId:dictionary.id,termIds,graph:''});if(serial===sequence&&q===question())showPreview(value);};
    const ids=automaticTermIds(dictionary);if(ids!==null)await prepare(ids);else renderDictionaryChoices(panel,dictionary,ids=>work(()=>prepare(ids)));
  }
  get('find').addEventListener('click',()=>work(start));
  consent.addEventListener('change',()=>controls(isBusy()));
  send.addEventListener('click',()=>work(async()=>{
    if(!preview||!consent.checked)return;const serial=sequence,q=question(),token=preview.request.token;preview=null;
    const value=await post('assistant-generate',{token,consent:true});dialog.close();
    if(serial!==sequence||q!==question())return;panel.replaceChildren();
    if(!value.result?.summary){for(const text of value.interpretation?.questions||[])panel.append(node('p',text));return;}
    searchId=value.result.search.id;
    const flow=node('section'),raw=node('section');panel.append(flow,raw);
    const evidence=renderRdfWorkflow(flow,value.result.summary,selection=>work(async()=>{
      clearSelected();const saved=await post('test-evidence',{id:searchId,...selection});
      if(serial!==sequence||q!==question())return;selected=saved;renderEvidence(get('selected'),selected);changed();message(label('rdfAttached','선택한 RDF 근거를 첨부했습니다. 테스트 프로필로 SQL을 생성할 수 있습니다.'));
    }),clearSelected,label('rdfApply','선택 근거 적용'));
    renderGroundedResult(raw,value.result);evidence.append(raw);
    const interpretation=node('details');interpretation.append(node('summary',t('ontology.query.grounding.aiInterpretation','AI가 생성한 온톨로지 검색 질문')),node('p',value.interpretation.summary));evidence.append(interpretation);
  }));
  async function cancel(){if(isBusy())return;await work(async()=>{if(preview)await post('cancel',{token:preview.request.token});preview=null;dialog.close();});}
  close.addEventListener('click',cancel);dialog.addEventListener('cancel',e=>{e.preventDefault();cancel();});
  get('enabled').addEventListener('change',()=>{invalidate();if(enabled())work(()=>options());});
  get('anchor').addEventListener('change',invalidate);
  get('refresh').addEventListener('click',()=>{invalidate();work(()=>options(true));});
  return {controls,invalidate,enabled,current:()=>selected,ready:()=>evidenceReady(enabled(),selected,question()),matches:value=>evidenceMatches(value,enabled(),selected,question()),request:()=>({useOntology:enabled(),evidenceHash:enabled()?evidenceKey(selected):null}),
    async restore(data,refresh){
      get('schema').replaceChildren(new Option(data.evidenceSchema,data.evidenceSchema));
      if(refresh){invalidate();loadedSchema='';}
      else if(data.evidence?.route?.startsWith('RDF_')&&data.evidence.question===question()&&data.evidence.schema===data.evidenceSchema){selected=data.evidence;get('enabled').checked=true;renderEvidence(get('selected'),selected);}
      if(enabled())try{await options();}catch(ex){return ex.message;}return '';
    }
  };
}
