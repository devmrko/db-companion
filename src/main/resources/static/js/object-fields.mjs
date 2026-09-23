import {t} from './i18n.mjs';
import {referenceName,teamBytes,attachTeamHelp} from './team-fields.mjs';
const fields={
  role:{type:'text',help:t('ui.f98a1f3d5430', "Agent의 역할과 응답 지침입니다. 모델에 전달되는 문구이며 원문 그대로 저장합니다.")},
  instruction:{type:'text',help:t('ui.7952be16100c', "Task 또는 Tool이 수행할 작업과 처리 지침입니다. Task의 {query}는 사용자 질문을 나타냅니다.")},
  profile_name:{type:'profile',help:t('ui.c7aa7bbc09ed', "모델 호출에 사용할 현재 스키마의 Select AI 프로필입니다.")},
  enable_human_tool:{type:'boolean',help:t('ui.10d1940aa7dd', "실행 중 사용자에게 추가 정보나 확인을 요청할 수 있는지 지정합니다. Task 설정이 Agent 설정보다 우선합니다.")},
  tools:{type:'tools',help:t('ui.69ceb0f28da0', "이 Agent 또는 Task에서 사용할 Tool 목록입니다. 연결을 바꾸며 Tool 자체를 생성하거나 삭제하지 않습니다.")},
  short_term_memory_length:{type:'integer',help:t('ui.87f9bd23645c', "Task 실행 중 모델 호출에 포함할 이전 대화 턴의 최대 개수입니다.")},
  input:{type:'task',help:t('ui.94bdee8448d8', "결과를 현재 Task의 입력으로 전달할 다른 Task입니다. 자기 참조와 순환 연결은 허용하지 않습니다.")},
  function:{type:'routine',help:t('ui.67b87075082f', "사용자 정의 Tool이 호출할 함수·프로시저 이름입니다. 소유자와 패키지를 포함할 수 있습니다. 소스 변경이나 실행은 하지 않습니다.")},
  tool_type:{type:'type',help:t('ui.15c6095b12b4', "내장 Tool 유형입니다. SQL·RAG는 프로필, WEBSEARCH는 Credential, NOTIFICATION은 알림 설정이 필요합니다.")},
  tool_params:{type:'params',help:t('ui.e42294c71ab7', "내장 Tool의 설정 JSON입니다. 프로필·Credential 등 선택한 항목만 바꾸며 다른 키는 유지합니다.")},
  tool_inputs:{type:'jsonArray',help:t('ui.808f446a5555', "함수 입력 인수의 이름과 설명을 담은 JSON 객체 배열입니다. 모델이 인수를 구성할 때 참고합니다.")},
  supervisor:{type:'readonly',help:t('ui.f9ee0157ee1a', "Supervisor Agent 여부입니다. Oracle은 생성 후 이 값을 변경할 수 없도록 제한합니다.")}
};
const editable={AGENT:['role','profile_name','enable_human_tool','tools','short_term_memory_length'],TASK:['instruction','tools','input','enable_human_tool'],TOOL:['instruction','function','tool_type','tool_params','tool_inputs']};
export const objectField=(kind,name)=>editable[kind]?.includes(name)||kind==='AGENT'&&name==='supervisor'?fields[name]:null;
export const parameterHelp={profile_name:t('ui.20014ed99713', "SQL·RAG에서 사용할 Select AI 프로필입니다."),credential_name:t('ui.a5f89886f5b0', "외부 서비스 인증에 사용할 활성 Credential 이름입니다. 비밀값은 조회하지 않습니다."),notification_type:t('ui.54b2756c9df2', "알림 경로입니다. slack 또는 email을 선택합니다."),channel:t('ui.392dc6516cd1', "Slack 알림을 받을 채널입니다."),recipient:t('ui.ecda036ac942', "이메일 수신자입니다."),sender:t('ui.69eb174c57b6', "이메일 발신자입니다."),smtp_host:t('ui.2a3176006d21', "이메일 발송에 사용할 SMTP 호스트입니다."),subject:t('ui.7c5286b44cf1', "알림 이메일의 제목입니다."),endpoint:t('ui.67b758c678b1', "외부 서비스의 HTTP 요청 주소입니다.")};
export const objectHelp=(host,kind,name)=>attachTeamHelp(host,name,objectField(kind,name));
export function toolNames(value) {
  const rows=JSON.parse(value);
  if(!Array.isArray(rows)||!rows.every(row=>typeof row==='string'))throw new Error(t('ui.de8dfc553b49', "Tool 이름의 JSON 배열로 입력해 주세요."));
  return rows;
}
export function updateTool(value,index,name) {
  const rows=toolNames(value);if(index<0||index>=rows.length||!Number.isInteger(index))throw new Error(t('ui.c1f724779792', "Tool 연결을 확인해 주세요."));
  rows[index]=name;return JSON.stringify(rows);
}
export function updateParameter(value,key,next) {
  const params=structuredParameters(value);
  if(!Object.hasOwn(parameterHelp,key))throw new Error(t('ui.b146bb6bd737', "tool_params를 확인해 주세요."));
  return JSON.stringify({...params,[key]:next});
}
export function structuredParameters(value) {
  const params=JSON.parse(value);
  if(!params||typeof params!=='object'||Array.isArray(params))throw new Error(t('ui.f0bfba52f70c', "tool_params는 JSON 객체로 입력해 주세요."));
  const safe=value=>typeof value==='number'?Number.isSafeInteger(value)&&!Object.is(value,-0):value&&typeof value==='object'?Object.values(value).every(safe):true;
  if(!safe(params))throw new Error(t('ui.ff61f49a1248', "숫자 원문을 보존하려면 JSON 직접 편집을 사용해 주세요."));
  return params;
}
export function parameterKeys(type,params) {
  const needed=['SQL','RAG'].includes(type?.toUpperCase())?['profile_name']:type?.toUpperCase()==='WEBSEARCH'?['credential_name']:type?.toUpperCase()==='NOTIFICATION'?['notification_type','credential_name']:[];
  if(type?.toUpperCase()==='NOTIFICATION') {
    const notification=typeof params.notification_type==='string'?params.notification_type.toLowerCase():null;
    if(notification==='email')needed.push('recipient','sender','smtp_host');
    if(notification==='slack')needed.push('channel');
  }
  return [...new Set([...needed,...Object.keys(params).filter(key=>Object.hasOwn(parameterHelp,key))])];
}
export function validateObjectValue(kind,attribute,value,maxBytes=32767) {
  const spec=objectField(kind,attribute);if(!spec||spec.type==='readonly')return t('ui.6feceb03f35f', "이 속성은 편집할 수 없습니다.");
  if(!value||!value.trim())return t('ui.db6144584361', "값을 입력해 주세요. 빈 값 저장이나 속성 삭제는 지원하지 않습니다.");
  if(!value.isWellFormed()||value.includes('\0'))return t('ui.276b201421c0', "올바르지 않은 문자가 있습니다.");
  if(teamBytes(value)>maxBytes)return t('ui.2c41f4ca8f42', "내용은 UTF-8 기준 32,767바이트 이내로 입력해 주세요. 입력은 자르지 않습니다.");
  if(spec.type==='boolean'&&!['true','false'].includes(value.toLowerCase()))return t('ui.964b402bc714', "true 또는 false를 선택해 주세요.");
  if(spec.type==='integer'&&(!/^\d+$/.test(value)||BigInt(value)<1n))return t('ui.6a6788643058', "1 이상의 정수를 입력해 주세요.");
  if(spec.type==='type'&&!['SQL','RAG','WEBSEARCH','NOTIFICATION'].includes(value))return t('ui.816d91b2e47a', "지원하는 Tool 유형을 선택해 주세요.");
  try {
    if(spec.type==='tools') {
      const names=toolNames(value).map(referenceName);
      if(names.some(name=>!name.trim()||teamBytes(name)>128))return t('ui.5512762ec48c', "Tool을 선택해 주세요.");
      if(new Set(names).size!==names.length)return t('ui.23b03cbee181', "같은 Tool이 중복되어 있습니다.");
    }
    if(spec.type==='params'){const v=JSON.parse(value);if(!v||typeof v!=='object'||Array.isArray(v))return t('ui.f0bfba52f70c', "tool_params는 JSON 객체로 입력해 주세요.");}
    if(spec.type==='jsonArray') {
      const rows=JSON.parse(value);
      if(!Array.isArray(rows)||!rows.every(row=>row&&typeof row==='object'&&!Array.isArray(row)&&typeof row.name==='string'&&row.name.trim()&&(!Object.hasOwn(row,'description')||typeof row.description==='string')))return t('ui.3786ad7fa467', "입력 name과 description을 가진 JSON 객체 배열로 입력해 주세요.");
      if(new Set(rows.map(row=>row.name)).size!==rows.length)return t('ui.3e594d8aea5e', "입력 이름이 중복되어 있습니다.");
    }
  }catch(_){return t('ui.a3029d3e034d', "올바른 JSON을 입력해 주세요.");}
  return '';
}
export const objectRetryAllowed=status=>status===400;
