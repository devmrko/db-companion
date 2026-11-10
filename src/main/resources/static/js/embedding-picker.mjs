export function databaseEmbeddingValue(model) { return `database: ${model}`; }
export function ociEmbeddingQuery(settings) {
  if(settings.provider!=='oci')throw new Error('OCI 모델 목록은 provider=oci인 프로필에서 조회할 수 있습니다.');
  if(!settings.region||!settings.credential_name||!settings.oci_compartment_id)throw new Error('프로필의 region·credential_name·oci_compartment_id를 먼저 설정하세요.');
  return {credential:settings.credential_name,region:settings.region,compartment:settings.oci_compartment_id};
}
export function embeddingPicker(host,value,data,read,changed,csrf) {
  let active=true,options=null,busy=false,nextPage='';
  const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
  const label=(text,control)=>{const n=el('label',text,'app-config-field');n.append(control);host.append(n);};
  const mode=el('select',undefined,'form-select');
  for(const [key,text] of [['database','DB 내부 모델'],['oci','OCI 제공 모델'],['manual','직접 입력 · 기타 제공자']]){const o=el('option',text);o.value=key;mode.append(o);}
  label('모델 위치',mode);
  const context=el('p','프로필과 모델 목록을 확인 중…','app-config-notice');host.append(context);
  const candidates=el('select',undefined,'form-select');label('임베딩 모델 선택',candidates);candidates.disabled=true;
  const fetchButton=el('button','OCI 임베딩 모델 조회','btn app-btn app-btn-secondary');fetchButton.type='button';fetchButton.hidden=true;host.append(fetchButton);
  const hint=el('p','임베딩 모델은 기억을 벡터로 바꿔 관련 기억을 검색할 때 사용합니다. 목록 조회는 임베딩·LLM을 실행하지 않습니다.','app-muted');host.append(hint);
  const manual=el('input',undefined,'form-control');manual.type='text';manual.hidden=true;manual.placeholder='제공자가 지정한 임베딩 모델명';manual.setAttribute('aria-label','임베딩 모델 직접 입력');host.append(manual);
  const preview=el('input',undefined,'form-control');preview.readOnly=true;preview.value=data.value||'';label('저장할 embedding_model 값',preview);
  const current=el('p',data.value?`현재 DB 저장값: ${data.value}`:'현재 DB 저장값: 미설정','app-config-notice');host.append(current);
  const status=el('p','모델을 선택한 뒤 아래 저장 버튼으로 적용하세요.','app-config-notice');status.setAttribute('role','status');host.append(status);
  function choose(text) { value.value=text;preview.value=text;changed(); }
  function empty(text) { candidates.replaceChildren();const option=el('option',text);option.value='';candidates.append(option); }
  function render() {
    nextPage='';fetchButton.textContent='OCI 임베딩 모델 조회';fetchButton.hidden=mode.value!=='oci';
    manual.hidden=mode.value!=='manual';candidates.hidden=mode.value==='manual';
    candidates.disabled=true;fetchButton.disabled=busy||!options;
    if(!options)return;
    if(mode.value==='database') {
      context.textContent='DB에서 조회한 ONNX 임베딩 모델입니다. 선택하면 database: 소유자.모델명 형식으로 저장합니다.';
      empty(options.databaseModels.length?'DB 내부 모델을 선택하세요':'등록된 DB 내부 임베딩 모델이 없습니다.');
      for(const model of options.databaseModels){const option=el('option',model);option.value=databaseEmbeddingValue(model);candidates.append(option);}
      candidates.disabled=!options.databaseModels.length;
      status.textContent=options.modelError||(!options.databaseModels.length?'DB 내부 모델을 먼저 설치하거나 OCI 제공 모델을 선택하세요.':'목록 조회 완료 · 실제 모델 호출은 수행하지 않았습니다.');
    }else if(mode.value==='oci') {
      empty('OCI 목록을 조회한 뒤 선택하세요.');
      try { const q=ociEmbeddingQuery(options.settings);context.textContent=`리전: ${q.region} · Credential: ${q.credential} · 현재 프로필의 Compartment 사용`;status.textContent='조회 버튼으로 해당 리전의 활성 임베딩 모델 목록을 가져옵니다. 모델을 호출하거나 프로필을 변경하지 않습니다.';fetchButton.disabled=busy; }
      catch(error){context.textContent=error.message;fetchButton.disabled=true;}
    }else {context.textContent='목록 조회를 지원하지 않는 제공자 또는 별도 모델을 위한 입력입니다. 제공자의 정확한 모델명을 확인하세요.';status.textContent='직접 입력한 모델의 사용 가능 여부는 검증하지 않았습니다.';manual.value=value.value;}
  }
  mode.addEventListener('change',()=>{choose('');render();});
  candidates.addEventListener('change',()=>{choose(candidates.value);status.textContent='선택한 값을 저장하면 이 프로필의 embedding_model만 변경됩니다. 실제 임베딩 호출 권한·호환성은 아직 검증하지 않았습니다.';});
  manual.addEventListener('input',()=>choose(manual.value));
  fetchButton.addEventListener('click',async()=>{
    if(busy||!options)return;busy=true;mode.disabled=true;fetchButton.disabled=true;status.textContent='OCI 모델 목록 조회 중…';
    try {
      const result=await read('/vector-search/oci-models',{method:'POST',headers:{'Content-Type':'application/json',[csrf.dataset.csrfHeader]:csrf.value},body:JSON.stringify({query:ociEmbeddingQuery(options.settings),page:nextPage,refresh:false})});
      if(!active)return;
      if(!nextPage)empty(result.page.items.length?'OCI 임베딩 모델을 선택하세요':'조회된 활성 기본 임베딩 모델이 없습니다.');
      for(const model of result.page.items){const option=el('option',`${model.name}${model.deprecatedAt?' · 종료 예정 '+model.deprecatedAt:''}`);option.value=model.name;candidates.append(option);}
      nextPage=result.page.nextPage||'';fetchButton.textContent=nextPage?'다음 모델 더 보기':'OCI 목록 다시 조회';
      candidates.disabled=candidates.options.length<2;status.textContent='OCI 목록 조회 완료 · 목록 등재는 Select AI 호출 성공을 보장하지 않습니다. 실제 모델 호출은 수행하지 않았습니다.';
    }catch(error){if(active)status.textContent=error.message;}finally{busy=false;if(active){mode.disabled=false;fetchButton.disabled=false;}}
  });
  read('/ai-profiles/attribute/embedding-options?'+new URLSearchParams({schema:data.target.schema,profile:data.target.profile})).then(result=>{
    if(!active)return;options=result;mode.value=data.value?.startsWith('database:')?'database':result.settings.provider==='oci'?'oci':result.databaseModels.length?'database':'manual';render();
  }).catch(error=>{if(active){context.textContent=error.message;status.textContent='목록을 확인하지 못했습니다. 현재 설정값을 유지합니다.';}});
  return ()=>{active=false;};
}
