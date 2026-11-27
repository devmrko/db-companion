import {t} from './i18n.mjs';
export function navigationRequest(question){
  return /(?:실행|질문|응답|SQL|에이전트|agent|execution).*(?:내역|이력|기록|history)/i.test(question)||
    /(?:어디|메뉴|화면|where)/i.test(question)&&/(?:프로필|프로파일|profile|용어|glossary|테이블|table|시멘틱|semantic|설정|이력)/i.test(question);
}
export function navigationItems(question,profile=''){
  if(/실행|질문|응답|SQL|에이전트|agent|execution/i.test(question))return [
    {label:t('assistantNav.executions','질문·응답 실행 내역'),href:'/ai-executions'+(profile?'?'+new URLSearchParams({profile}):''),description:t('assistantNav.executionsHelp','실행 내역에서 기간·프로파일·질문으로 찾고, 항목을 열어 질문·응답·생성 SQL을 확인합니다. 조회 가능한 정보는 DB 기록과 권한에 따라 다릅니다.')},
    {label:t('assistantNav.agents','Agent 실행 내역'),href:'/ai-executions/agents',description:t('assistantNav.agentsHelp','Agent의 Run·Task·Tool 실행 내역을 확인합니다.')},
    {label:t('assistantNav.sql','SQL 실행 기록의 출처'),href:'/ai-executions/sql/sources',description:t('assistantNav.sqlHelp','SQL 기록의 출처와 수집·조회 준비 상태를 확인합니다. 설정 변경 이력과는 별도입니다.')}
  ];
  const item=/용어|glossary/i.test(question)?['glossary','업무 용어','/business-glossary']:/테이블|table/i.test(question)?['tables','테이블·컬럼','/tables']:
    /시멘틱|semantic/i.test(question)?['semantic','시멘틱 소스','/ai-profiles']:['profiles','프로파일 설정','/ai-profiles'];
  return [{label:t('assistantNav.'+item[0],item[1]),href:item[2],description:t('assistantNav.readOnly','이동할 화면 안내입니다. AI 호출이나 설정 변경은 실행하지 않습니다.')}];
}
