import {t} from './i18n.mjs';
const specs={
  agents:{type:'assignments',help:t('ui.be4c726a4599', "Team에서 사용할 Agent와 Task 연결입니다. 위에서 아래 순서대로 처리합니다. 같은 Agent에 여러 Task를 연결할 수 있습니다.")},
  process:{type:'process',help:t('ui.df7b2b081695', "Task 처리 방식입니다. sequential은 지정한 순서대로 하나씩 처리합니다.")},
  supervisor_agent:{type:'agent',help:t('ui.3ffe2eb00a5f', "작업을 분배하는 Supervisor Agent입니다. workers 목록에 같은 Agent를 중복 지정하지 않습니다.")},
  long_term_memory_length:{type:'integer',help:t('ui.032290b02c8a', "새 실행의 메모리 문맥에 포함할 이전 Task 요약의 최대 개수입니다.")}
};
export const teamField=name=>specs[name]??null;
export const teamBytes=value=>new TextEncoder().encode(value).length;
export function teamAssignments(value) {
  const rows=JSON.parse(value);
  if(!Array.isArray(rows)||!rows.every(row=>row&&typeof row==='object'&&!Array.isArray(row)&&typeof row.name==='string'&&typeof row.task==='string'))
    throw new Error(t('ui.e10d53a5b287', "agents는 name과 task를 가진 JSON 객체 배열이어야 합니다."));
  return rows;
}
export const referenceName=value=>value.startsWith('"')&&value.endsWith('"')?value.slice(1,-1).replaceAll('""','"'):value.toUpperCase();
export const referenceValue=name=>/^[A-Z][A-Z0-9_$#]*$/.test(name)?name:`"${name.replaceAll('"','""')}"`;
export function validateTeamValue(attribute,value,maxBytes=32767) {
  if(!value||!value.trim())return t('ui.db6144584361', "값을 입력해 주세요. 빈 값 저장이나 속성 삭제는 지원하지 않습니다.");
  if(!value.isWellFormed()||value.includes('\0'))return t('ui.276b201421c0', "올바르지 않은 문자가 있습니다.");
  if(teamBytes(value)>maxBytes)return t('ui.2c41f4ca8f42', "내용은 UTF-8 기준 32,767바이트 이내로 입력해 주세요. 입력은 자르지 않습니다.");
  if(!teamField(attribute))return t('ui.6feceb03f35f', "이 속성은 편집할 수 없습니다.");
  if(attribute==='process'&&value!=='sequential')return t('ui.a58741280753', "process는 sequential만 지원합니다.");
  if(attribute==='long_term_memory_length'&&(!/^\d+$/.test(value)||BigInt(value)<1n))return t('ui.6a6788643058', "1 이상의 정수를 입력해 주세요.");
  const validName=name=>name.trim()&&teamBytes(name)<=128&&name.isWellFormed()&&!name.includes('\0');
  if(attribute==='supervisor_agent'&&!validName(referenceName(value)))return t('ui.0641c6ad9ea7', "Agent 이름을 확인해 주세요.");
  if(attribute==='agents') {
    try {
      const rows=teamAssignments(value),seen=new Set();
      if(!rows.length)return t('ui.2ff186673083', "Agent·Task 연결을 한 개 이상 지정해 주세요.");
      for(const row of rows) {
        const name=referenceName(row.name),task=referenceName(row.task),key=JSON.stringify([name,task]);
        if(!validName(name)||!validName(task))return t('ui.e0db152ddb3a', "Agent와 Task를 선택해 주세요.");
        if(seen.has(key))return t('ui.f2be01325fbc', "같은 Agent·Task 연결이 중복되어 있습니다.");
        seen.add(key);
      }
    } catch(error) {return error.message;}
  }
  return '';
}
export function moveAssignment(value,index,direction) {
  const rows=teamAssignments(value),next=index+direction;
  if(!Number.isInteger(index)||![1,-1].includes(direction)||index<0||index>=rows.length||next<0||next>=rows.length)return value;
  [rows[index],rows[next]]=[rows[next],rows[index]];
  return JSON.stringify(rows);
}
export function changeAssignment(value,index,key,nextValue) {
  const rows=teamAssignments(value);
  if(!Number.isInteger(index)||!rows[index]||!['name','task'].includes(key))throw new Error(t('ui.ecc46667b4c9', "연결 항목을 다시 확인해 주세요."));
  rows[index]={...rows[index],[key]:nextValue};
  return JSON.stringify(rows);
}
export function removeAssignment(value,index) {
  const rows=teamAssignments(value);
  if(!Number.isInteger(index)||!rows[index])throw new Error(t('ui.ecc46667b4c9', "연결 항목을 다시 확인해 주세요."));
  return JSON.stringify(rows.filter((_,i)=>i!==index));
}
export function attachTeamHelp(host,attribute,spec=teamField(attribute)) {
  if(!spec)return;
  const button=document.createElement('button'),tip=document.createElement('div');
  const id=`team-help-${++attachTeamHelp.sequence}`;
  button.type='button';button.className='app-help-button';button.textContent='?';
  button.setAttribute('aria-label',t('ui.b323bb52d6cc', "{0} 설명", attribute));button.setAttribute('popovertarget',id);
  button.setAttribute('aria-controls',id);button.setAttribute('aria-expanded','false');
  tip.id=id;tip.className='app-help-tooltip';tip.setAttribute('popover','auto');tip.setAttribute('role','tooltip');tip.textContent=spec.help;
  host.append(button,tip);
  tip.addEventListener('toggle',event=>{
    const open=event.newState==='open';button.setAttribute('aria-expanded',String(open));
    if(!open){button.removeAttribute('aria-describedby');return;}
    button.setAttribute('aria-describedby',id);const rect=button.getBoundingClientRect();
    tip.style.left=`${Math.max(10,Math.min(rect.left,window.innerWidth-tip.offsetWidth-10))}px`;
    tip.style.top=`${Math.max(10,Math.min(rect.bottom+8,window.innerHeight-tip.offsetHeight-10))}px`;
  });
}
attachTeamHelp.sequence=0;
