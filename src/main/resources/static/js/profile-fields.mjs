import {t} from './i18n.mjs';
const fields = {
  annotations: ['boolean', t('ui.8320d61d1e1a', "테이블·컬럼의 Annotation을 AI 질의 생성에 참고할 메타데이터로 포함합니다.")],
  comments: ['boolean', t('ui.203d8195608e', "테이블·컬럼 코멘트를 AI 질의 생성에 참고할 메타데이터로 포함합니다.")],
  conversation: ['boolean', t('ui.9176dc4a15ab', "이 프로필에서 대화 이력을 사용합니다.")],
  enforce_object_list: ['boolean', t('ui.7e405ef01b8a', "AI가 생성하는 SQL의 참조 대상을 object_list에 지정한 객체로 제한합니다. DB 접근 권한을 부여하는 설정은 아닙니다.")],
  constraints: ['boolean', t('ui.2fb13caf3916', "기본키·외래키 등 관계 제약조건을 AI에 전달할 메타데이터에 포함합니다.")],
  case_sensitive_values: ['boolean', t('ui.9c136f8fb193', "AI가 문자열 값을 비교하는 SQL을 만들 때 대소문자를 구분하도록 안내합니다.")],
  enable_custom_source_uri: ['boolean', t('ui.4efe70181a8b', "RAG 인용 출처에 Object Storage의 사용자 지정 URL 메타데이터를 사용합니다.")],
  credential_name: ['credential', t('ui.5cfa295d7b61', "AI 제공자 API 인증에 사용할 Credential 이름입니다. 현재 계정의 활성 Credential을 선택합니다. 목록에 있다고 인증 성공이나 해당 제공자와의 호환성이 보장되지는 않습니다.")],
  additional_instructions: ['multiline', t('ui.ec1f81bf8fdb', "AI 요청마다 참고할 업무 규칙·용어·응답 형식 등의 추가 지침입니다.")],
  role: ['multiline', t('ui.8138986a443a', "AI에게 부여할 역할과 응답 행동을 설명합니다.")],
  object_list: ['objects', t('ui.fba12ee54eb3', "자연어 SQL 생성에 참고할 스키마·객체 목록입니다. owner만 지정하면 해당 스키마 전체를 의미합니다. 테이블명·컬럼명·설명이 AI 제공자에게 전달될 수 있으므로 필요한 객체만 선택하세요.")],
  max_tokens: ['integer', t('ui.6903a6a7a1a1', "한 번의 응답에서 생성할 최대 토큰 수입니다. 모델별 지원 한도가 다릅니다."), {min:'1', step:'1'}],
  seed: ['integer', t('ui.7d132e14dd5e', "응답의 변동성을 줄이기 위한 정수 시드입니다. 같은 값이어도 동일한 응답을 보장하지는 않습니다. signed 64-bit 정수 범위입니다."), {min:'-9223372036854775808',max:'9223372036854775807',step:'1'}],
  temperature: ['number', t('ui.2b686b287a1f', "응답 생성의 무작위성을 조절합니다. 낮을수록 변동성이 줄어듭니다. 0 이상이며 상한과 지원 여부는 모델에 따라 다릅니다."), {min:'0',step:'any'}],
  provider: ['select', t('ui.c9eaa7e94c8b', "AI 서비스를 제공하는 플랫폼입니다. 변경해도 Credential·모델·리전은 자동으로 변경되지 않습니다."), {choices:['openai','cohere','azure','database','oci','google','anthropic','huggingface','aws']}],
  model: ['suggest', t('ui.9d7f78ad66dc', "응답을 생성할 모델의 이름 또는 식별자입니다. 후보는 같은 제공자를 사용하는 현재 계정의 기존 프로필값이며, 전체 지원 목록은 아닙니다. 직접 입력할 수 있습니다.")],
  region: ['suggest', t('ui.059f443c5269', "AI 서비스를 호출할 리전입니다. 사용할 수 있는 모델은 리전에 따라 다릅니다. 후보는 기존 프로필값이며 직접 입력할 수 있습니다.")],
  oci_compartment_id: ['text', t('ui.640f2a0a20f9', "OCI Generative AI를 호출할 때 사용하는 Compartment의 OCID입니다. 현재 Credential에 필요한 접근 권한이 있어야 합니다.")],
  oci_endpoint_id: ['text', t('ui.a41f59e9efa8', "OCI 전용 AI 호스팅 엔드포인트의 OCID입니다.")],
  oci_apiformat: ['select', t('ui.dbfd3ba8a1fc', "OCI 전용 Chat 모델 엔드포인트가 사용하는 API 형식입니다."), {choices:['COHERE','GENERIC']}],
  oci_runtimetype: ['select', t('ui.eec2048ce165', "이전 OCI Generate Text 모델의 런타임 유형입니다. 새 Chat 모델에서는 oci_apiformat을 사용합니다."), {choices:['COHERE','LLAMA']}],
  object_list_mode: ['select', t('ui.684adab11dfd', "all은 지정한 전체 객체, automated는 질의와 관련된 객체의 메타데이터를 선택합니다. automated 설정은 DB에 관련 벡터 인덱스를 만들 수 있습니다."), {choices:['all','automated']}],
  embedding_model: ['text', t('ui.c29206a320ab', "벡터 검색에 사용할 임베딩 모델 이름입니다. 제공자 모델 또는 DB에 가져온 모델을 지정합니다.")],
  provider_endpoint: ['text', t('ui.5d1cf0268d28', "AI 제공자 API를 호출할 엔드포인트입니다. 제공자가 요구하는 형식으로 입력합니다.")],
  azure_deployment_name: ['text', t('ui.a582653f322c', "Azure OpenAI에서 배포한 응답 생성 모델의 Deployment 이름입니다.")],
  azure_embedding_deployment_name: ['text', t('ui.d91d0575b3f3', "Azure OpenAI에서 배포한 임베딩 모델의 Deployment 이름입니다.")],
  azure_resource_name: ['text', t('ui.d9892d091374', "Azure OpenAI 리소스 이름입니다.")],
  conversation_length: ['text', t('ui.a21ab9989709', "대화에서 유지할 이력 길이 설정입니다. DB 버전과 대화 설정의 지원 형식을 확인하세요.")],
  stop_tokens: ['multiline', t('ui.6ebebcca6889', "응답 생성을 멈출 문자열 목록입니다. JSON 문자열 배열로 입력합니다.")],
  vector_index_name: ['text', t('ui.34b37700acfe', "RAG 검색에 사용할 벡터 인덱스 이름입니다.")],
  source_language: ['text', t('ui.37d40ec1004b', "번역할 원문의 언어 이름 또는 언어 코드입니다.")],
  target_language: ['text', t('ui.38048053e341', "번역 결과의 언어 이름 또는 언어 코드입니다.")]
};
export function profileField(attribute) {
  const [type,help,options={}] = fields[attribute] || ['multiline',t('ui.d157745a088d', "이 프로필의 추가 설정값입니다. DB 버전과 AI 제공자에 따라 의미와 지원 형식이 다르므로 해당 속성 문서를 확인하세요.")];
  return {type,help,...options};
}
export const booleanValue = value => /^(true|false)$/i.test(value ?? '') ? value.toLowerCase() : null;
export function validateTypedValue(attribute,value) {
  const type=profileField(attribute).type;
  if(type==='boolean' && booleanValue(value)===null)return t('ui.964b402bc714', "true 또는 false를 선택해 주세요.");
  if(type==='integer') {
    if(!/^[+-]?\d+$/.test(value))return t('ui.8f888129440a', "정수를 입력해 주세요.");
    const n=BigInt(value);
    if(attribute==='seed'&&(n< -9223372036854775808n||n>9223372036854775807n))return t('ui.3cd111659656', "seed는 signed 64-bit 정수 범위로 입력해 주세요.");
    if(attribute==='max_tokens'&&n<1n)return t('ui.d5e9de9a52e9', "max_tokens는 1 이상으로 입력해 주세요.");
  }
  if(type==='number'&&(!/^[+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)||!Number.isFinite(Number(value))||Number(value)<0))return t('ui.edfe3fa757b7', "0 이상의 숫자를 입력해 주세요.");
  return '';
}
export function credentialChoices(current,choices) {
  const rows=[...new Set(choices)].map(value=>({value,label:value,disabled:false}));
  if(current&&!rows.some(row=>row.value===current))rows.unshift({value:current,label:t('ui.83d0fbfd6d83', "{0} (현재값 · 목록에 없음)", current),disabled:true});
  return rows;
}
export function distinctObjectChoices(choices) {
  const names=new Map();
  for(const item of choices) {
    const existing=names.get(item.name);
    if(!existing || item.type==='MATERIALIZED VIEW'&&existing.type==='TABLE')names.set(item.name,item);
  }
  return [...names.values()];
}
export function objectEntries(value) {
  const rows=JSON.parse(value);
  if(!Array.isArray(rows)||!rows.every(row=>row&&typeof row==='object'&&!Array.isArray(row)&&typeof row.owner==='string'&&row.owner.length>0&&
    (row.name===undefined||typeof row.name==='string'&&row.name.length>0)&&Object.values(row).every(v=>typeof v==='string')))
    throw new Error(t('ui.284509a8965f', "이 형식은 아래 JSON 직접 편집을 사용해 주세요. 기존 값은 유지됩니다."));
  return rows;
}
export function addObject(value,owner,name) {
  const rows=objectEntries(value);
  if(!owner)throw new Error(t('ui.ae271450ec62', "스키마를 선택해 주세요."));
  if(rows.some(row=>row.owner===owner&&row.name===(name||undefined)))return value;
  return JSON.stringify([...rows,name?{owner,name}:{owner}]);
}
export function removeObject(value,index) {
  const rows=objectEntries(value);
  if(!Number.isInteger(index)||index<0||index>=rows.length)throw new Error(t('ui.f63ba073e186', "객체 항목을 다시 확인해 주세요."));
  return JSON.stringify(rows.filter((_,i)=>i!==index));
}

let helpSequence=0;
export function attachProfileHelp(container,attribute) {
  const id=`profile-help-${++helpSequence}`, button=document.createElement('button'), tip=document.createElement('div');
  button.type='button';button.className='app-help-button';button.textContent='?';
  button.setAttribute('aria-label',t('ui.b323bb52d6cc', "{0} 설명", attribute));button.setAttribute('popovertarget',id);
  button.setAttribute('aria-controls',id);button.setAttribute('aria-expanded','false');
  tip.id=id;tip.className='app-help-tooltip';tip.setAttribute('popover','auto');tip.setAttribute('role','tooltip');
  tip.textContent=profileField(attribute).help;
  container.append(button,tip);
  tip.addEventListener('toggle',event=>{
    const open=event.newState==='open';button.setAttribute('aria-expanded',String(open));
    if(!open){button.removeAttribute('aria-describedby');return;}
    button.setAttribute('aria-describedby',id);
    const rect=button.getBoundingClientRect();
    tip.style.left=`${Math.max(10,Math.min(rect.left,window.innerWidth-tip.offsetWidth-10))}px`;
    tip.style.top=`${Math.max(10,Math.min(rect.bottom+8,window.innerHeight-tip.offsetHeight-10))}px`;
  });
  return button;
}
if(typeof document!=='undefined')document.querySelectorAll('[data-profile-attribute-name]').forEach(cell=>attachProfileHelp(cell,cell.dataset.profileAttributeName));
