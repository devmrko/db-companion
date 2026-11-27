import {assistantApi,assistantPost,profileLabel} from './ai-assistant.mjs';
import {validateProfileValue} from './profile-editor.mjs';
import {t} from './i18n.mjs';
import {creationConversation} from './profile-creation-chat.mjs';
import {navigationRequest,navigationItems} from './assistant-navigation.mjs';
import {renderQueryData} from './query-result-view.mjs';
import {resultArchiveAction} from './select-ai-result-archive.mjs';
import {isCloneRequest,isConversationQuestion} from './conversation-turn.mjs';

const text=(key,fallback,...args)=>t('workbench.'+key,fallback,...args);
const attributeNames={conversation:'conversation',comments:'comments',constraints:'constraints',model:'model',provider:'provider',temperature:'temperature',max_tokens:'max_tokens',object_list:'object_list',prompt:'prompt',semantic_source:'semantic_source'};
export function requestIntent(question){
  const q=question.trim();
  if(/^(취소|그만|cancel|キャンセル|取消)$/i.test(q))return {kind:'cancel'};
  if(/권한|관리자|(?:^|\s)(?:admin|grant|privilege|permission)(?:$|[\s?])/i.test(q))return {kind:'permissions'};
  if(navigationRequest(q))return {kind:'navigation'};
  if(isCloneRequest(q))return {kind:'clone-profile'};
  if(/^(질문으로\s*확인|프로필\s*테스트|프로파일\s*테스트|test profile)$/i.test(q))return {kind:'query-profile'};
  if(!/(?:말고|하지\s*마|설명만|don't|do not)/i.test(q)&&/(?:프로파일|프로필|profile)/i.test(q)&&/(?:만들|생성|create)/i.test(q))return {kind:isConversationQuestion(q)?'help':'create-profile'};
  if(/(?:이력|history|履歴|历史)/i.test(q)&&!/(?:저장|활성|설정|enable)/i.test(q))return {kind:'history'};
  if(/(?:시멘틱|시맨틱|semantic|업무.?용어|업무.?지식|business definitions|knowledge|用語|术语|业务定义)/i.test(q)&&/(?:연결|붙|attach|connect|接続|连接)/i.test(q))return {kind:'sources'};
  if(/(?:용어|term|用語|术语)/i.test(q)&&/(?:등록|추가|수정|편집|register|create|edit|add|登録|追加|編集|编辑|注册|创建|新增)/i.test(q))return {kind:'glossary'};
  if(/(?:용어|glossary|用語|术语)/i.test(q)&&/(?:목록|검색|조회|list|search|一覧|搜索)/i.test(q))return {kind:'glossary-list'};
  let attribute=Object.keys(attributeNames).find(name=>new RegExp('(?:^|[^a-z_])'+name+'(?:$|[^a-z_])','i').test(q));
  if(!attribute&&/(?:대화|conversation|会話|对话)/i.test(q))attribute='conversation';
  if(!attribute&&/(?:테이블|컬럼|열).*(?:설명)|설명.*(?:사용|포함)/.test(q))attribute='comments';
  if(!attribute&&/제약조건/.test(q))attribute='constraints';
  if(!attribute&&/온도/.test(q))attribute='temperature';
  if(!attribute&&/모델/.test(q))attribute='model';
  if(!attribute&&/제공자/.test(q))attribute='provider';
  if(!attribute&&/최대.*토큰/.test(q))attribute='max_tokens';
  if(attribute&&!/(?:말고|하지\s*마|뭐|무엇|어떻게|what|how|是否)/i.test(q)&&/(?:켜|꺼|끄|활성|비활성|변경|수정|설정|enable|disable|turn\s+(?:on|off)|set|change|有効|無効|启用|禁用)/i.test(q)){
    const boolean=['conversation','comments','constraints'].includes(attribute);
    const disabled=/(?:비활성|꺼|끄|않|제외|disable|false|turn\s+off|無効|禁用)/i.test(q);
    return {kind:'attribute',attribute,value:boolean?String(!disabled):null};
  }
  return {kind:'assistant'};
}
export function appendSource(before,name,choices){
  const values=before==null||before===''?[]:JSON.parse(before);
  if(!Array.isArray(values)||values.some(v=>typeof v!=='string')||new Set(values).size!==values.length)throw new Error(text('invalidSources','기존 연결값을 해석하지 못했습니다. 원래 값을 유지합니다.'));
  if(!choices.some(v=>v.name===name&&v.status==='ENABLED'))throw new Error(text('invalidSource','활성 상태로 조회된 소스를 선택하세요.'));
  return JSON.stringify([...new Set([...values,name])]);
}
export function conversationRequest(turns,question){
  const prior=turns.slice(-6).map(v=>`${v.role}: ${v.content}`).join('\n');
  const value=prior?`${prior}\nuser: ${question}`:question;
  // Keep the current request intact. Never silently truncate a reviewed request.
  return value.length<=2000?value:question;
}

export function mountWorkbench(root,{api=assistantApi,doc=document}={}){
  if(root.dataset.workbenchMounted==='true')return;
  root.dataset.workbenchMounted='true';
  const get=name=>root.querySelector(`[data-wb-${name}]`),csrf=get('csrf');
  const profiles=Array.from(get('profiles').options).map(v=>v.value),schema=root.dataset.schema;
  let target=profiles.includes(root.dataset.initialProfile)?root.dataset.initialProfile:'',model=root.dataset.model||'';
  let busy=false,reply=null,pending=null,page=1,termDraft=null,termOriginal=null;
  let dialogue=null,lastAssistant='',expectedInput='',queuedMessage='';
  const el=(tag,value,css)=>{const node=doc.createElement(tag);if(value!==undefined)node.textContent=value;if(css)node.className=css;return node;};
  const post=(url,value)=>api(url,assistantPost(csrf,value));
  const button=(label,action)=>{const node=el('button',label,'btn app-btn app-btn-secondary');node.type='button';node.addEventListener('click',()=>{if(!busy)run(action);});return node;};
  function controls(){get('send').disabled=busy||root.dataset.allowed!=='true';get('input').disabled=busy||root.dataset.allowed!=='true';get('retarget').disabled=busy;const fields=get('body').querySelector('fieldset');if(fields)fields.disabled=busy;get('workspace').setAttribute('aria-busy',String(busy));}
  async function run(work){if(busy)return;busy=true;controls();const progress=t('ui.8bf609c884ca','불러오는 중…');get('status').textContent=progress;try{await work();if(get('status').textContent===progress)get('status').textContent='';}catch(error){pending=null;const diagnostic=error.diagnostic,suffix=diagnostic?.code?' · '+diagnostic.code+(diagnostic.tool?' · '+diagnostic.tool:'')+(diagnostic.requestId?' · '+diagnostic.requestId:''):'';get('status').textContent=error.message+suffix;say('assistant',error.message+suffix);if(diagnostic)detail(get('body').querySelector('fieldset')||get('body'),t('setup.ai.toolResults','서비스 호출 결과'),diagnostic);}finally{busy=false;controls();}}
  function say(role,content,choices=[],proposal=null){
    if(role==='assistant')lastAssistant=content;
    const row=el('article',undefined,'setup-chat-message setup-chat-'+role);
    row.append(el('small',role==='user'?text('you','사용자'):text('assistant','도우미')),el('p',content));
    if(proposal){const list=el('dl',undefined,'setup-chat-proposal');for(const [key,value]of Object.entries(proposal)){
      list.append(el('dt',key==='name'?t('dialogue.profileName','프로파일 이름'):key),el('dd',Array.isArray(value)?value.map(v=>typeof v==='string'?v:v.owner+'.'+v.name).join(', '):String(value)));}row.append(list);}
    if(choices.length){const actions=el('div',undefined,'setup-chat-choices');for(const choice of choices){if(choice.href){const a=el('a',choice.label,'app-inline-link');a.href=choice.href;a.target='_blank';a.rel='noopener';actions.append(a);}else actions.append(button(choice.label,choice.action));}row.append(actions);}
    get('log').append(row);get('log').scrollTop=get('log').scrollHeight;
    // Old controls cannot act on a later conversation turn.
    for(const node of get('log').querySelectorAll('article:not(:last-child) button'))node.disabled=true;
  }
  function workspace(title,step='edit'){
    pending=null;get('title').textContent=title;get('step').textContent=text(step,{edit:'입력',review:'변경안 확인',result:'결과',waiting:'요청 대기',select:'선택'}[step]||step);
    const fields=el('fieldset');get('body').replaceChildren(fields);
    root.dispatchEvent(new CustomEvent('setup-workspace-changed',{bubbles:true,detail:{step,title}}));return fields;
  }
  const detail=(host,label,value)=>{const item=el('details');item.append(el('summary',label),el('pre',typeof value==='string'?value:JSON.stringify(value,null,2),'app-preview-value'));host.append(item);return item;};
  const link=(host,label,url)=>{const a=el('a',label,'app-inline-link');a.href=url;a.target='_blank';a.rel='noopener';host.append(a);};
  const profileUrl=()=>'/ai-profiles/detail?'+new URLSearchParams({profile:target});
  const attributeUrl=attribute=>'/ai-profiles/attribute?'+new URLSearchParams({schema,profile:target,attribute});
  const creation=creationConversation({schema,api,post,workspace,field,button,ask,say,detail,link,review,el,lang:doc.documentElement.lang,
    body:()=>get('body').querySelector('fieldset'),profileNames:()=>profiles,verify:startQuery,created:name=>{if(!profiles.includes(name))profiles.push(name);target=name;get('target').textContent=schema+'.'+name;}});
  async function permissions(question,explicitTopic=''){
    if(!explicitTopic&&!/프로필|프로파일|profile|시멘틱|시맨틱|semantic|소스|콜백|메타|컬럼|주석|metadata|이력|history|네트워크|acl|network|dds|보안|data grant|text|형태소|검색|agent|에이전트|credential|인증|테이블|table/i.test(question)){
      const body=workspace(t('adminHelp.title','ADMIN 조치 안내'),'select');
      for(const [label,query]of [[t('adminHelp.profile','Profile'),'프로필 권한'],[t('adminHelp.semanticTitle','Semantic Source'),'시멘틱 권한'],[t('adminHelp.historyTitle','Metadata history'),'메타데이터 이력 권한'],[t('adminHelp.profileHistoryTitle','Profile history'),'프로필 이력 권한'],[t('aiNetwork.title','Network'),'네트워크 권한'],['DDS','DDS 권한'],['Oracle Text','Oracle Text 권한']])body.append(button(label,()=>permissions(query)));
      ask(text('permissionFeature','어떤 기능의 권한이 필요한가요? 기능을 선택하거나 입력하세요.'),permissions);return;
    }
    const topic=explicitTopic||(/시멘틱|시맨틱|semantic|소스|콜백/i.test(question)?'semantic':/메타|컬럼|주석|metadata/i.test(question)?'metadata-history':/이력|history/i.test(question)?'profile-history':/네트워크|acl|network/i.test(question)?'network':/dds|보안|data grant/i.test(question)?'dds':/text|형태소|검색/i.test(question)?'text':/agent|에이전트/i.test(question)?'agent':/credential|인증/i.test(question)?'credential':/테이블|table/i.test(question)?'table':'profile');
    const display=async account=>{
      const result=await api('/db/admin-help/advice?'+new URLSearchParams({topic,target:account}));
      const body=workspace(t('adminHelp.title','ADMIN 조치 안내'),'result');
      body.append(el('p',`${result.target} · ${result.actor}`),el('h3',result.purpose),el('p',result.steps),el('p',t('adminAdvice.notChecked','{0}의 현재 권한과 계정 존재 여부는 조회하지 않았습니다. ADMIN이 같은 DB에서 확인하고 필요한 항목만 적용하세요.',result.target)));
      for(const instruction of result.instructions)detail(body,t('adminHelp.'+instruction.key,instruction.key),instruction.sql);
      detail(body,text('permissionVerify','ADMIN 확인용 조회 SQL'),result.verification);
      link(body,t('adminHelp.title','ADMIN 조치 안내'),result.link);
      say('assistant',result.purpose+'\n'+result.steps);
      if(creation.active())body.append(button(text('resumeCreation','프로파일 생성 계속'),()=>creation.resume()));
    };
    if(schema==='ADMIN')ask(text('permissionTarget','권한 안내를 받을 대상 일반 계정 이름을 입력하세요.'),async value=>{await display(value.trim().toUpperCase());reply=null;});
    else{await display(schema);reply=null;}
  }
  function navigation(question){
    const items=navigationItems(question,target),body=workspace(text('navigation','화면 안내'),'result');
    for(const item of items){const section=el('section',undefined,'setup-source-choice');link(section,item.label,item.href);section.append(el('p',item.description));body.append(section);}
    say('assistant',items.map(v=>v.label+': '+v.description).join('\n'),items);
    if(creation.active())body.append(button(text('resumeCreation','프로파일 생성 계속'),()=>creation.resume()));
  }
  function field(host,label,value='',multiline=false){const wrap=el('label',undefined,'app-field'),input=el(multiline?'textarea':'input');input.className='form-control';input.value=value;if(multiline)input.rows=3;wrap.append(el('span',label),input);host.append(wrap);return input;}
  function ask(message,handler,choices=[]){reply=handler;expectedInput=message;say('assistant',message,choices.map(value=>({label:value,action:()=>receive(value)})));}
  async function chooseTarget(continuation){
    const body=workspace(text('target','대상 프로필'),'select');
    if(!profiles.length){say('assistant',text('noProfiles','조회된 프로필이 없습니다. 프로필 생성 후 다시 시작하세요.'));link(body,text('profiles','프로필 설정'),'/ai-profiles');reply=null;return;}
    const choose=async value=>{if(!profiles.includes(value)){reply=choose;say('assistant',text('pickTarget','목록의 프로필 이름을 선택하세요.'));return;}target=value;get('target').textContent=schema+'.'+target;reply=null;say('user',target);await continuation();};
    for(const name of profiles)body.append(button(name,()=>choose(name)));
    ask(text('whichTarget','어느 프로필에서 진행할까요?'),choose,[],'value',profiles);
  }
  async function withTarget(action){if(target)await action();else await chooseTarget(action);}
  function confirmWork(body,title,confirmation,label,work){
    const consent=el('label',undefined,'setup-workspace-consent'),check=el('input');check.type='checkbox';consent.append(check,el('span',confirmation));body.append(consent);
    const ticket={};pending=ticket;
    const apply=button(label,async()=>{if(!check.checked||pending!==ticket)return;pending=null;apply.disabled=true;check.checked=false;reply=null;try{await work();}catch(error){workspace(title,'result').append(el('p',text('actionUncertain','처리 결과를 확인하지 못했습니다. 실제 상태를 확인한 뒤 다시 진행하세요. 자동 재시도하지 않습니다.')));throw error;}});
    apply.dataset.wbConfirmAction='';
    apply.disabled=true;check.addEventListener('change',()=>{apply.disabled=!check.checked||pending!==ticket;});body.append(apply,button(text('cancel','취소'),cancel));
  }
  function review(title,before,after,save,confirmation=''){
    const body=workspace(title,'review');
    const compare=el('div',undefined,'setup-workspace-diff');
    for(const [label,value] of [[text('before','변경 전'),before],[text('after','변경 후'),after]]){const part=el('section');part.append(el('h3',label),el('pre',typeof value==='string'?value:JSON.stringify(value,null,2),'app-preview-value'));compare.append(part);}body.append(compare);
    confirmWork(body,title,confirmation||text('confirm','표시된 대상과 변경 내용을 확인했습니다.'),text('apply','적용'),save);
    say('assistant',text('reviewHelp','오른쪽 변경안을 확인한 뒤 적용하세요. 수정 요청이나 취소를 입력하면 이 변경안은 무효화됩니다.'));
  }
  async function startQuery(name){
    reply=null;workspace(text('queryTest','질문으로 확인'),'edit');
    ask(text('queryQuestion','{0}로 확인할 질문을 입력하세요. 전송 내용 확인 후 AI를 1회 호출하며, 생성 SQL 실행은 별도로 확인합니다.',name),async question=>{
      await post('/ai-test/selection',{name});
      const prepared=await post('/ai-test/preview',{action:'SQL',question,useOntology:false,evidenceHash:''});
      const body=workspace(text('queryTest','질문으로 확인'),'review');
      body.append(el('p',profileLabel({...prepared.preview.profile,name:prepared.preview.profile.selection.name})),el('p',question));
      detail(body,t('setup.ai.payload','전송할 정보'),prepared.preview.source);
      confirmWork(body,text('queryTest','질문으로 확인'),t('setup.ai.consent','표시된 정보의 외부 전송과 AI 호출 1회 비용 발생에 동의합니다.'),text('generateSql','SQL 생성'),async()=>{
        const generated=await post('/ai-test/generate',{token:prepared.preview.token,consent:true});if(generated.error)throw new Error(generated.text);
        const panel=workspace(text('sqlReview','생성 SQL 확인'),'review');panel.append(el('pre',generated.text,'app-preview-value'));
        const execution=await post('/ai-test/execute/preview',{resultId:generated.id});
        confirmWork(panel,text('sqlReview','생성 SQL 확인'),text('sqlConsent','표시된 SQL과 조회 대상을 확인했으며 실행에 동의합니다.'),text('executeSql','SQL 실행'),async()=>{
          const result=await post('/ai-test/execute',{token:execution.token,consent:true});if(result.error)throw new Error([result.code,result.error].filter(Boolean).join(' · '));const host=workspace(text('queryResult','조회 결과'),'result');
          detail(host,text('sqlReview','생성 SQL 확인'),execution.sql);const rows=el('div');host.append(rows);renderQueryData(rows,result.data,doc);resultArchiveAction(host,result.resultId,csrf,doc);
          say('assistant',text('queryExecuted','SQL 조회 결과를 받았습니다. 업무상 정답 여부는 기대값과 비교해 확인하세요.'));
          link(host,t('assistantNav.executions','질문·응답 실행 내역'),'/ai-executions?'+new URLSearchParams({profile:name}));
          host.append(button(text('queryTest','질문으로 확인'),()=>startQuery(name)));
        });say('assistant',text('sqlReady','SQL을 생성했습니다. 표시된 SQL을 확인한 뒤 별도로 실행하세요.'));
      });
      say('assistant',text('queryReady','전송할 질문과 정보를 준비했습니다. 동의 후 SQL 생성을 선택하세요.'));
    },[],'content');
  }
  async function history(host=null){
    const body=host||workspace(text('history','프로필 변경 이력'),'result');
    const result=await api('/ai-profiles/history?'+new URLSearchParams({schema,profile:target,page,scope:'profile'}));
    if(!result.entries.length)body.append(el('p',text('noHistory','조회된 이력이 없습니다.')));
    for(const item of result.entries)detail(body,`${item.eventAt} · ${item.kind} · ${item.actor}`,item.payload);
    if(page>1)body.append(button(text('previous','이전'),async()=>{page--;await history();}));
    if(result.hasNext)body.append(button(text('next','다음'),async()=>{page++;await history();}));
  }
  async function finishProfile(result){
    const body=workspace(text('saved','적용 결과'),'result');body.append(el('p',result.message||text('unverified','저장 결과를 확정하지 못했습니다. 설정·이력을 확인하세요.')));
    say('assistant',result.verified?text('verified','변경 후 저장값을 다시 조회해 일치를 확인했습니다.'):text('unverified','저장 결과를 확정하지 못했습니다. 설정·이력을 확인하세요.'));
    link(body,text('profiles','프로필 설정'),profileUrl());
    body.append(el('h3',text('history','프로필 변경 이력')));
    try{page=1;await history(body);}catch(error){body.append(el('p',text('historyUnavailable','이력 조회 실패: {0}',error.message)));}
  }
  async function editAttribute(attribute,value=null){
    reply=null;const form=await api(attributeUrl(attribute));
    const body=workspace(`${target} · ${attribute}`);body.append(el('p',text('current','현재 값: {0}',form.value??'—')));
    const input=field(body,text('newValue','변경할 값'),value??form.value??'',true);input.maxLength=32767;
    const prepare=async raw=>{
      const value=raw.trim(),error=validateProfileValue(attribute,value,form.maxBytes);if(error)throw new Error(error);
      if(value===form.value){reply=null;say('assistant',text('unchanged','이미 같은 값으로 설정되어 있습니다.'));return;}
      reply=null;review(`${target} · ${attribute}`,form.value??'—',value,async()=>finishProfile(await post('/ai-profiles/attribute',{...form.target,value,version:form.version})));
    };
    body.append(button(text('review','변경안 확인'),()=>prepare(input.value)));
    if(value===null)ask(text('attributeQuestion','{0} 값을 입력하거나 오른쪽에서 수정하세요.',attribute),prepare);
    else await prepare(value);
  }
  async function attachSources(){
    reply=null;const form=await api(attributeUrl('semantic_source'));
    const status=await api('/ai-profiles/semantic?'+new URLSearchParams({schema}));
    const body=workspace(text('sources','업무 지식 연결'),'select');
    body.append(el('p',text('current','현재 값: {0}',form.value??'[]')));
    const sources=(status.sources||[]).filter(s=>s.status==='ENABLED');
    if(!status.supported||!status.editable||!sources.length){say('assistant',text('noSources','연결 가능한 활성 소스가 없습니다. Semantic Context에서 소스를 준비한 뒤 다시 요청하세요.'));link(body,text('profiles','프로필 설정'),profileUrl());return;}
    const choose=async name=>{
      if(!sources.some(v=>v.name===name))throw new Error(text('invalidSource','활성 상태로 조회된 소스를 선택하세요.'));
      const value=appendSource(form.value,name,sources);reply=null;
      if(JSON.stringify(JSON.parse(form.value||'[]'))===value){say('assistant',text('alreadyAttached','이미 연결된 소스입니다. 변경하지 않았습니다.'));return;}
      review(`${target} · semantic_source`,form.value??'[]',value,async()=>finishProfile(await post('/ai-profiles/attribute',{...form.target,value,version:form.version})));
    };
    for(const source of sources){const card=el('section',undefined,'setup-source-choice');card.append(button(source.name,()=>choose(source.name)),el('p',`${source.type} · ${source.description||''}`));detail(card,text('callback','콜백 정보'),source.callbacks||{});body.append(card);}
    ask(text('sourceQuestion','어떤 소스를 연결할까요? 기존 연결은 유지합니다.'),choose);
  }
  async function glossaryList(edit=false,initialFilter=''){
    reply=null;const body=workspace(text('glossary','업무 용어'),'select');
    const filter=field(body,text('termFilter','대표 용어 검색'),initialFilter);
    const rows=el('div');body.append(rows);
    async function load(offset=0){const result=await api('/business-glossary/list?'+new URLSearchParams({filter:filter.value,offset}));rows.replaceChildren();
      if(!result.rows.length)rows.append(el('p',text('noTerms','조회된 용어가 없습니다.')));
      for(const term of result.rows){const card=el('section',undefined,'setup-source-choice');card.append(button(`${term.term} · v${term.revision}`,()=>editTerm(term)),el('p',term.definition));card.append(button(text('termHistory','용어 변경 이력'),()=>termHistory(term)));rows.append(card);}
      if(result.more)rows.append(button(text('next','다음'),()=>load(offset+result.rows.length)));
      if(offset)rows.append(button(text('previous','이전'),()=>load(Math.max(0,offset-50))));
    }
    body.prepend(button(text('newTerm','용어 등록'),()=>startTerm()));body.append(button(text('find','검색'),()=>load()));await load();
    ask(edit?text('whichTerm','수정할 용어를 선택하거나 “새 용어”를 입력하세요.'):text('termFilterHelp','검색할 대표 용어를 입력하세요.'),async value=>{if(/^(새 용어|new term)$/i.test(value))await startTerm();else{filter.value=value;await load();}});
  }
  async function termHistory(term,host=null){
    const body=host||workspace(`${term.term} · ${text('termHistory','용어 변경 이력')}`,'result');
    const result=await api('/business-glossary/history?'+new URLSearchParams({id:term.id}));
    detail(body,text('termHistory','용어 변경 이력'),result);
  }
  async function startTerm(){
    const status=await api('/business-glossary/status'),history=await api('/business-glossary/history/status');
    if(status.table!=='READY'||history.storage!=='READY'){
      const body=workspace(text('glossary','업무 용어'),'result');say('assistant',text('storageRequired','용어와 이력 저장소 준비가 필요합니다. 기존 관리 화면에서 생성 SQL을 확인할 수 있습니다.'));link(body,text('glossary','업무 용어'),'/business-glossary');return;
    }
    termOriginal=null;termDraft={term:'',definition:'',aliases:[],criteria:'',enabled:true};renderTerm();
    ask(text('termNameQuestion','등록할 업무 용어는 무엇인가요?'),async name=>{
      termDraft.term=name;renderTerm();ask(text('termDefinitionQuestion','“{0}”의 업무상 의미·집계 기준을 입력하세요.',name),async definition=>{
        termDraft.definition=definition;renderTerm();ask(text('termAliasesQuestion','별칭을 쉼표로 구분해 입력하세요. 없으면 “없음”을 입력하세요.'),async aliases=>{
          termDraft.aliases=/^(없음|none|なし|无)$/i.test(aliases.trim())?[]:aliases.split(/[,\n]/).map(v=>v.trim()).filter(Boolean);renderTerm();
          ask(text('termCriteriaQuestion','참고 SQL·필터 기준을 입력하세요. 없으면 “없음”을 입력하세요. SQL은 실행하지 않습니다.'),async criteria=>{
            termDraft.criteria=/^(없음|none|なし|无)$/i.test(criteria.trim())?'':criteria;reply=null;await reviewTerm();
          },[],'content');
        });
      },[],'content');
    });
  }
  async function editTerm(term){termOriginal=term;termDraft={term:term.term,definition:term.definition,aliases:[...term.aliases],criteria:term.criteria||'',enabled:term.enabled,rdf:term.rdf};reply=null;renderTerm();say('assistant',text('editTermHelp','오른쪽에서 용어를 수정한 뒤 변경안을 확인하세요. “취소”로 종료할 수 있습니다.'));}
  function renderTerm(){
    const body=workspace(text('glossary','업무 용어'));
    const inputs={term:field(body,text('termName','업무 용어'),termDraft.term),definition:field(body,text('termDefinition','업무 정의'),termDraft.definition,true),aliases:field(body,text('termAliases','별칭 (쉼표 구분)'),termDraft.aliases.join(', ')),criteria:field(body,text('termCriteria','참고 SQL·기준 (실행하지 않음)'),termDraft.criteria,true)};
    inputs.term.maxLength=256;inputs.definition.maxLength=4000;inputs.criteria.maxLength=4000;
    for(const [name,input] of Object.entries(inputs))input.addEventListener('input',()=>{termDraft[name]=name==='aliases'?input.value.split(/[,\n]/).map(v=>v.trim()).filter(Boolean):input.value;});
    const enabled=el('label'),toggle=el('input');toggle.type='checkbox';toggle.checked=termDraft.enabled;toggle.dataset.wbTermEnabled='';toggle.addEventListener('change',()=>{termDraft.enabled=toggle.checked;});enabled.append(toggle,el('span',t('businessGlossary.active','사용')));body.append(enabled);
    body.append(button(text('review','변경안 확인'),async()=>{reply=null;await reviewTerm();}));
  }
  async function reviewTerm(){
    if(!termDraft.term.trim()||!termDraft.definition.trim())throw new Error(text('termRequired','용어와 업무 정의를 모두 입력하세요.'));
    if(termDraft.term.length>256||termDraft.definition.length>4000||termDraft.criteria.length>4000||termDraft.aliases.length>20)throw new Error(text('termLimit','용어 256자, 정의·기준 각 4,000자, 별칭 20개 한도를 확인하세요.'));
    const draft=structuredClone(termDraft),original=termOriginal;
    const duplicates=await api('/business-glossary/list?'+new URLSearchParams({filter:draft.term,offset:0}));
    if(duplicates.rows.some(row=>row.id!==original?.id&&row.term.trim().toUpperCase()===draft.term.trim().toUpperCase())||duplicates.more)throw new Error(text('duplicateTerm','같은 이름 또는 추가 후보가 있습니다. 용어 목록에서 기존 항목을 확인하고 수정하세요.'));
    review(text('glossary','업무 용어'),original||text('newTerm','용어 등록'),draft,async()=>{
      const saved=await post('/business-glossary/save',{id:original?.id,revision:original?.revision||0,value:draft,consent:true});
      const body=workspace(text('saved','적용 결과'),'result');body.append(el('h3',`${saved.term} · v${saved.revision}`),el('p',saved.definition));
      say('assistant',text('termSaved','용어와 변경 이력을 저장했습니다.'));try{await termHistory(saved,body);}catch(error){body.append(el('p',text('historyUnavailable','이력 조회 실패: {0}',error.message)));}
    });
  }
  function cancel(){reply=null;pending=null;creation.clear();workspace(text('workspace','현재 작업'),'waiting');say('assistant',text('cancelled','진행 중인 입력·변경안을 취소했습니다. 새 요청을 입력하세요.'));}
  async function configureDialogue(question=''){
    queuedMessage=question;const options=await api('/ai-assistant/options');
    const body=workspace(t('dialogue.title','AI 대화 설정'),'select');
    body.append(el('p',t('dialogue.modelHelp','대화를 처리할 모델입니다. 생성·수정 대상 프로파일과는 별개입니다.')));
    detail(body,t('dialogue.capabilities','메뉴·서비스 역할·입출력'),await api('/ai-setup/conversation/catalogue'));
    const select=el('select');select.className='form-control';select.setAttribute('data-wb-dialogue-model','');
    for(const item of options.profiles){const option=el('option',profileLabel(item));option.value=item.name;option.selected=item.name===options.selected?.name;select.append(option);}body.append(select);
    const consent=el('label',undefined,'setup-workspace-consent'),check=el('input');check.type='checkbox';check.setAttribute('data-wb-dialogue-consent','');
    consent.append(check,el('span',t('dialogue.consent','이 창에서 보내는 질문·최근 대화·설정 초안과 조회한 메타데이터를 선택한 AI 모델에 전송합니다. 메시지당 최대 7회 호출과 비용 발생에 동의합니다. DB 변경·SQL 실행은 별도로 확인합니다.')));body.append(consent);
    const start=button(t('dialogue.start','대화 시작'),async()=>{
      if(!check.checked||!select.value)return;
      await post('/ai-assistant/selection',{name:select.value});
      const fresh=await api('/ai-assistant/options');model=fresh.selected.name;
      dialogue={helper:model,version:fresh.tokens.version,consent:true};
      const queued=queuedMessage;queuedMessage='';
      if(queued)await converse(queued);else{if(creation.active())await creation.resume();say('assistant',t('dialogue.ready','AI 대화를 시작했습니다. 요청을 입력하세요.'));}
    });start.setAttribute('data-wb-dialogue-start','');start.disabled=true;
    check.addEventListener('change',()=>{start.disabled=!check.checked||!select.value;});body.append(start);
    if(!options.profiles.length)say('assistant',t('dialogue.noModels','대화에 사용할 활성 AI 프로파일이 없습니다. 프로파일 설정에서 모델·인증을 준비하세요.'));
    else say('assistant',t('dialogue.choose','처음 한 번 대화 모델과 전송 동의를 선택하세요. 이후 입력은 AI가 대화 내용에 따라 처리합니다.'));
  }
  async function applyDialogue(result){
    const args=result.arguments||{},action=result.action;
    if(!action||action==='suggest')return;
    // Invalidate pending Apply only when a new workspace action is actually requested.
    pending=null;for(const node of get('body').querySelectorAll('[data-wb-confirm-action]'))node.disabled=true;
    if(['create','clone','draft','tables','models','review','resume'].includes(action)){reply=null;await creation.operate(action,args);return;}
    if(action==='cancel'){cancel();return;}
    if(action==='input'){
      const handler=reply;if(!handler||creation.ownsReply(handler)||typeof args.value!=='string')throw new Error(t('dialogue.noInput','현재 값을 받을 입력 단계가 없습니다. 요청을 구체적으로 입력하세요.'));
      reply=null;await handler(args.value);return;
    }
    reply=null;expectedInput='';
    if(action==='target'){if(!profiles.includes(args.profile))throw new Error('Unknown profile');target=args.profile;get('target').textContent=schema+'.'+target;return;}
    if(action==='permissions'){await permissions('',args.topic);return;}
    if(action==='navigate'){
      const body=workspace(text('navigation','화면 안내'),'result');link(body,result.answer,args.path);return;
    }
    if(action==='glossary'){await startTerm();return;}if(action==='glossary-list'){await glossaryList(args.edit!==false,args.filter||'');return;}
    await withTarget(async()=>{switch(action){case 'history':page=1;await history();break;case 'sources':await attachSources();break;case 'attribute':await editAttribute(args.attribute,args.value??null);break;case 'query':await startQuery(target);break;default:throw new Error('Unknown AI action');}});
  }
  async function converse(question){
    const context={target,workspace:get('title').textContent,creation:creation.context(),lastAssistant,expectedInput:reply?expectedInput:''};
    let result;try{result=await post('/ai-setup/conversation/message',{question,...dialogue,context});}
    catch(error){if(error.status===409)dialogue=null;throw error;}
    say('assistant',result.answer,result.action==='suggest'?[{label:t('dialogue.accept','제안 반영'),action:async()=>{say('user',t('dialogue.accept','제안 반영'));await creation.operate('draft',result.arguments);}}]:[],result.action==='suggest'?result.arguments:null);await applyDialogue(result);
    if(result.executions?.length){const body=get('body').querySelector('fieldset');if(body){body.querySelector('[data-wb-evidence]')?.remove();const evidence=detail(body,t('dialogue.evidence','사용한 서비스·조회 결과'),result.executions);evidence.setAttribute('data-wb-evidence','');}}
  }
  async function receive(raw){const question=raw.trim();if(!question)return;say('user',question);
    if(!dialogue){await configureDialogue(question);return;}
    await converse(question);
  }
  get('form').addEventListener('submit',event=>{event.preventDefault();const question=get('input').value;if(!question.trim()||busy)return;get('input').value='';run(()=>receive(question));});
  get('input').addEventListener('keydown',event=>{if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing){event.preventDefault();get('form').requestSubmit();}});
  get('retarget').addEventListener('click',()=>run(async()=>{reply=null;pending=null;creation.clear();await chooseTarget(async()=>{workspace(text('workspace','현재 작업'),'waiting');say('assistant',text('targetChosen','{0}에서 진행합니다. 요청을 입력하세요.',target));});}));
  get('target').textContent=target?schema+'.'+target:text('noTarget','대상 미선택');
  get('retarget').parentElement.append(button(t('dialogue.settings','대화 모델·동의'),async()=>{dialogue=null;await configureDialogue();}));
  workspace(text('workspace','현재 작업'),'waiting').append(el('p',text('workspaceHelp','요청에 맞는 설정·변경안·결과를 이곳에 표시합니다.')));
  say('assistant',text('welcome','무엇을 설정할까요? 필요한 정보는 대화에서 확인합니다.'),[
    {label:text('exampleCreate','프로파일 만들기'),action:()=>receive(text('exampleCreate','프로파일 만들기'))},
    {label:t('creationChat.cloneTitle','기존 프로파일 복제'),action:()=>receive(t('creationChat.cloneTitle','기존 프로파일 복제'))},
    {label:text('examplePermissions','필요한 권한 안내'),action:()=>receive(text('examplePermissions','필요한 권한 안내'))},
    {label:text('exampleConversation','대화 저장 켜기'),action:()=>receive(text('exampleConversation','대화 저장 켜기'))},
    {label:text('exampleTerm','업무 용어 등록'),action:()=>receive(text('exampleTerm','업무 용어 등록'))},
    {label:text('exampleSource','업무 용어 연결'),action:()=>receive(text('exampleSource','업무 용어 연결'))},
    {label:text('exampleHistory','변경 이력 조회'),action:()=>receive(text('exampleHistory','변경 이력 조회'))}
  ]);controls();
  return {receive:value=>run(()=>receive(value)),cancel,selectedTarget:()=>target};
}
if(typeof document!=='undefined')document.querySelectorAll('[data-setup-workbench]').forEach(root=>mountWorkbench(root));
