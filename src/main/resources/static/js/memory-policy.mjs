export function normalizeResourceName(value) { return String(value??'').trim().toUpperCase(); }
export function memoryPrerequisite(profile, settings) {
  if(!profile)return {ready:false,message:'추출·임베딩에 사용할 프로필을 선택하세요.'};
  const embedding=String(settings?.embedding_model||'').trim();
  return embedding?{ready:true,message:`${profile} · 임베딩 모델: ${embedding}. 생성 요청 시 실제 모델 접근을 DB가 확인합니다.`}:{ready:false,message:`${profile}에 임베딩 모델이 설정되어 있지 않습니다. 먼저 ‘임베딩 모델 설정’에서 사용할 모델을 저장한 뒤 새로고침하세요. conversation 설정만으로는 저장소를 만들 수 없습니다.`};
}
export function creationInput(form, schema) {
  const data = new FormData(form), kind = form.dataset.mpForm;
  return {schema,kind,name:normalizeResourceName(data.get('name')),profile:String(data.get('profile')??''),
    description:String(data.get('description')??''),matchLimit:kind==='memory'?Number(data.get('matchLimit')):null,
    refreshRate:kind==='memory'&&data.get('refreshRate')?Number(data.get('refreshRate')):null,
    mode:data.get('mode'),pii:data.has('pii'),injection:data.has('injection'),moderation:data.has('moderation'),confirmed:data.has('confirmed')};
}
export function linkChoices(attribute, status) {
  return attribute==='memory_store_name'?status.stores.map(r=>r.name):status.guardrails.filter(r=>r.status?.toUpperCase()==='ENABLED').map(r=>r.name);
}
export function mountMemoryPolicy(root) {
  const get=n=>root.querySelector(`[data-mp-${n}]`);
  let busy=false, status=null, memoryReady=false, prerequisiteRevision=0;
  const attempted=new Set();
  const message=text=>{get('message').textContent=text;};
  const url='/ai-profiles/memory-policy?'+new URLSearchParams({schema:root.dataset.schema,profile:root.dataset.profile});
  async function api(options={},target=url) {
    const response=await fetch(target,{credentials:'same-origin',cache:'no-store',...options});
    if(response.redirected||response.status===401)throw new Error('로그인 세션이 만료되었습니다.');
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error('서버 응답을 확인하지 못했습니다.');
    const data=await response.json();if(!response.ok)throw Object.assign(new Error(data.error||'처리 실패'),{status:response.status});return data;
  }
  function controls() {
    get('refresh').disabled=busy;
    get('observe').disabled=busy;
    get('conversations').disabled=busy||!status?.capability.memory;
    for(const control of get('extract').elements)control.disabled=busy||!status?.settings.memory_store_name;
    for(const form of root.querySelectorAll('[data-mp-form]')) {
      const supported=!!status?.capability[form.dataset.mpForm];
      for(const control of form.elements)control.disabled=busy||!supported||attempted.has(form.dataset.mpForm);
      if(form.dataset.mpForm==='memory')form.querySelector('[type="submit"]').disabled=busy||!supported||attempted.has('memory')||!memoryReady;
    }
  }
  async function prerequisite() {
    const revision=++prerequisiteRevision;
    const form=root.querySelector('[data-mp-form="memory"]'),profile=form.elements.profile.value;
    memoryReady=false;get('prerequisite').textContent='선택한 프로필의 임베딩 모델 설정 확인 중…';controls();
    get('embedding-edit').hidden=profile!==root.dataset.profile;
    get('profile-link').hidden=!profile||profile===root.dataset.profile;
    get('profile-link').href='/ai-profiles/detail?'+new URLSearchParams({profile});
    try {
      const selected=profile===root.dataset.profile?status:profile?await api({},'/ai-profiles/memory-policy?'+new URLSearchParams({schema:root.dataset.schema,profile})):null;
      if(revision!==prerequisiteRevision)return;
      const result=memoryPrerequisite(profile,selected?.settings);memoryReady=result.ready;get('prerequisite').textContent=result.message;
    }catch(error){if(revision===prerequisiteRevision)get('prerequisite').textContent=error.message;}
    finally{if(revision===prerequisiteRevision)controls();}
  }
  function list(host,items) {
    host.replaceChildren();
    for(const item of items){const li=document.createElement('li');li.textContent=[item.name,item.profile,item.status,item.description].filter(Boolean).join(' · ');host.append(li);}
    if(!items.length){const li=document.createElement('li');li.textContent='등록된 항목이 없습니다.';host.append(li);}
  }
  async function refresh() {
    if(busy)return;busy=true;controls();
    try {
      status=await api(); const c=status.capability;
      get('status').textContent=`${c.version||'?'} · Memory ${c.memory?'지원 확인':'지원 미확인'} · Guardrail ${c.guardrail?'지원 확인':'지원 미확인'} · ${c.reason}`;
      get('memory').hidden=!c.memory;get('guardrail').hidden=!c.guardrail;
      get('memory-current').textContent=status.settings.memory_store_name||'미연결';
      const memoryLink='/ai-memory?'+new URLSearchParams({store:status.settings.memory_store_name||''});
      get('browse-link').href=memoryLink;
      if(status.settings.memory_store_name){const link=document.createElement('a');link.href=memoryLink;link.textContent=status.settings.memory_store_name;get('memory-current').replaceChildren(link);}
      get('guard-current').textContent=status.settings.guardrail||'미연결';
      get('conversation').textContent=status.settings.conversation||'미설정';
      list(get('stores'),status.stores);list(get('guards'),status.guardrails);
      const storeHost=get('store-details');storeHost.replaceChildren();
      for(const store of status.stores){
        const button=document.createElement('button');button.type='button';button.className='btn app-btn';button.textContent=store.name+' · ID·설정 보기';
        button.addEventListener('click',async()=>{
          if(busy)return;busy=true;controls();
          try {
            const details=await api({},'/ai-profiles/memory-policy/store?'+new URLSearchParams({schema:root.dataset.schema,name:store.name}));
            let output=button.nextElementSibling;
            if(!output||output.tagName!=='PRE'){output=document.createElement('pre');button.after(output);}
            output.textContent=JSON.stringify(details,null,2);output.style.whiteSpace='pre-wrap';
          }catch(error){message(error.message);}finally{busy=false;controls();}
        });storeHost.append(button);
      }
      for(const select of root.querySelectorAll('[data-mp-profiles]')) {
        const previous=select.value;select.replaceChildren();
        const empty=document.createElement('option');empty.value='';empty.textContent='프로필을 선택하세요';select.append(empty);
        for(const name of status.profiles){const option=document.createElement('option');option.value=name;option.textContent=name;select.append(option);}
        select.value=status.profiles.includes(previous)?previous:status.profiles.includes(root.dataset.profile)?root.dataset.profile:'';
      }
    }catch(error){status=null;get('memory').hidden=true;get('guardrail').hidden=true;message(error.message);}
    finally{busy=false;controls();if(status)await prerequisite();}
  }
  root.querySelector('[data-mp-form="memory"] [name="profile"]').addEventListener('change',()=>{root.querySelector('[data-mp-form="memory"] [name="confirmed"]').checked=false;prerequisite();});
  for(const field of root.querySelectorAll('[data-mp-form] [name="name"]'))field.addEventListener('blur',()=>{field.value=normalizeResourceName(field.value);});
  for(const form of root.querySelectorAll('[data-mp-form]')) form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy||attempted.has(form.dataset.mpForm)||!form.reportValidity())return;
    if(form.dataset.mpForm==='memory'&&!memoryReady){form.querySelector('[data-mp-create-message]').textContent=get('prerequisite').textContent;return;}
    const body=creationInput(form,root.dataset.schema);if(!body.confirmed)return;
    const localMessage=text=>{form.querySelector('[data-mp-create-message]').textContent=text;message(text);};
    busy=true;controls();localMessage('생성 중…');
    try {
      const csrf=get('csrf');attempted.add(body.kind);
      const result=await api({method:'POST',headers:{'Content-Type':'application/json',[csrf.dataset.csrfHeader]:csrf.value},body:JSON.stringify(body)});
      localMessage(result.message);
    }catch(error){if(error.status===400||error.status===409)attempted.delete(body.kind);localMessage(error.message);}
    finally{busy=false;controls();await refresh();}
  });
  get('observe').addEventListener('click',async()=>{
    if(busy)return;busy=true;controls();
    try{
      const rows=await api({},url.replace('memory-policy?','memory-policy/observations?'));
      const host=get('observations');host.replaceChildren();
      for(const row of rows){
        const details=document.createElement('details'),summary=document.createElement('summary'),text=document.createElement('pre');
        summary.textContent=`${row.created} · Conversation ${row.conversationId} · 입력 ${row.promptStatus??'미기록'} / 출력 ${row.responseStatus??'미기록'}`;
        text.textContent=`질문\n${row.prompt??''}\n\n반환 응답\n${row.response??''}`;text.style.whiteSpace='pre-wrap';
        details.append(summary,text);host.append(details);
      }
      if(!rows.length)host.textContent='기록된 대화가 없습니다.';
    }catch(error){message(error.message);}finally{busy=false;controls();}
  });
  get('conversations').addEventListener('click',async()=>{
    if(busy)return;busy=true;controls();
    try{
      const rows=await api({},url.replace('memory-policy?','memory-policy/conversations?'));
      const select=get('conversation-list');select.replaceChildren();
      const empty=document.createElement('option');empty.value='';empty.textContent=rows.length?'대화를 선택하세요':'이 프로필에 기록된 대화가 없습니다.';select.append(empty);
      for(const row of rows){const option=document.createElement('option');option.value=row.id;option.textContent=`${row.title} · ${row.id} · ${row.prompts}건 · ${row.modified}`;select.append(option);}
    }catch(error){message(error.message);}finally{busy=false;controls();}
  });
  get('extract').addEventListener('submit',async event=>{
    event.preventDefault();const form=event.currentTarget;
    if(busy||!form.reportValidity())return;
    const fields=new FormData(form),store=status?.settings.memory_store_name,conversationId=String(fields.get('conversationId')||'');
    const key=`extract:${store}:${conversationId}`;
    if(attempted.has(key)){message('이 화면에서 이미 추출을 요청한 대화입니다. 결과를 확인한 뒤 다시 실행 여부를 판단하세요.');return;}
    if(!store||!fields.has('confirmed'))return;
    busy=true;controls();attempted.add(key);message('메모리 추출 중…');
    try{
      const csrf=get('csrf');
      const result=await api({method:'POST',headers:{'Content-Type':'application/json',[csrf.dataset.csrfHeader]:csrf.value},body:JSON.stringify({schema:root.dataset.schema,profile:root.dataset.profile,store,conversationId,confirmed:true})},'/ai-profiles/memory-policy/extract');
      message(result.message);
    }catch(error){if(error.status===400||error.status===409)attempted.delete(key);message(error.message);}
    finally{form.querySelector('[name="confirmed"]').checked=false;busy=false;controls();}
  });
  document.addEventListener('profile-attribute-saved',async event=>{
    const saved=event.detail;
    if(saved.schema!==root.dataset.schema||saved.profile!==root.dataset.profile||saved.attribute!=='embedding_model')return;
    await refresh();
    const form=root.querySelector('[data-mp-form="memory"]');
    form.closest('details').open=true;
    form.querySelector('[data-mp-create-message]').textContent=`임베딩 모델 저장 확인: ${saved.value}. 입력한 저장소 설정을 이어서 작성하세요.`;
    get('prerequisite').scrollIntoView({block:'center'});
  });
  document.addEventListener('memory-store-create-requested',()=>{
    const form=root.querySelector('[data-mp-form="memory"]');form.closest('details').open=true;
    form.scrollIntoView({block:'center'});form.elements.name.focus();
  });
  get('refresh').addEventListener('click',refresh);refresh();
}
if(typeof document!=='undefined')document.querySelectorAll('[data-memory-policy]').forEach(mountMemoryPolicy);
