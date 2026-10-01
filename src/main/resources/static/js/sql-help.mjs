import {sqlHelpCatalog} from './sql-help-catalog.mjs';
export const helpRegistry=sqlHelpCatalog;
const labels={
  ko:{title:'화면 작업의 SQL / PL/SQL',operation:'작업 선택',purpose:'선택한 작업',sql:'SQL · PL/SQL',variables:'예시 값·바인드 변수',permission:'실행 계정·권한',effects:'직접 실행 시 영향',result:'확인 및 앱 처리와의 차이',source:'소스 근거',verify:'확인용 조회 SQL',copy:'SQL 복사',copyVerify:'확인 SQL 복사',close:'닫기',doc:'Oracle 공식 문서',copied:'복사했습니다. 실행하지 않았습니다.',copyFailed:'복사하지 못했습니다. 코드를 선택해 직접 복사해 주세요.',notice:'설명용 예제입니다. 현재 로그인 정보나 실제 값은 채우지 않습니다. 복사는 실행하지 않습니다.',fallback:'',read:'조회',write:'DB 변경',ddl:'DDL · 암시적 COMMIT',ai:'AI 요청 · 외부 호출/비용 확인','write-ai':'DB 변경 · 외부 호출/비용 확인'},
  en:{title:'SQL / PL/SQL for this screen',operation:'Choose an operation',purpose:'Selected operation',sql:'SQL / PL/SQL',variables:'Placeholders and bind variables',permission:'Execution account and privileges',effects:'Effects if run manually',result:'Verification and application boundaries',source:'Source reference',verify:'Verification SQL',copy:'Copy SQL',copyVerify:'Copy verification SQL',close:'Close',doc:'Official Oracle documentation',copied:'Copied; not executed.',copyFailed:'Copy failed; select the code and copy it manually.',notice:'Reference examples only. No login information or live values are inserted. Copying does not execute SQL.',fallback:'',read:'Read',write:'DB change',ddl:'DDL / implicit COMMIT',ai:'AI request / check external cost','write-ai':'DB change / check external cost'},
  ja:{title:'この画面の SQL / PL/SQL',operation:'操作を選択',purpose:'選択した操作',sql:'SQL / PL/SQL',variables:'プレースホルダーとバインド変数',permission:'実行ユーザーと権限',effects:'手動実行時の影響',result:'確認とアプリ処理の境界',source:'ソース参照',verify:'確認 SQL',copy:'SQL をコピー',copyVerify:'確認 SQL をコピー',close:'閉じる',doc:'Oracle 公式ドキュメント',copied:'コピーしました。実行していません。',copyFailed:'コピーできませんでした。コードを選択してコピーしてください。',notice:'参照用の例です。ログイン情報は挿入せず、自動実行しません。',fallback:'操作の詳細説明は英語で表示します。',read:'読み取り',write:'DB 書き込み',ddl:'DDL / 暗黙 COMMIT',ai:'AI リクエスト / 外部呼出・費用を確認','write-ai':'DB 書き込み / 外部呼出・費用を確認'},
  zh:{title:'本页面的 SQL / PL/SQL',operation:'选择操作',purpose:'所选操作',sql:'SQL / PL/SQL',variables:'占位符和绑定变量',permission:'执行账户和权限',effects:'手动执行的影响',result:'验证及应用处理边界',source:'源码依据',verify:'验证 SQL',copy:'复制 SQL',copyVerify:'复制验证 SQL',close:'关闭',doc:'Oracle 官方文档',copied:'已复制，未执行。',copyFailed:'复制失败，请选择代码后手动复制。',notice:'仅供参考，不填入登录信息，不会自动执行。',fallback:'操作的详细说明以英语显示。',read:'只读',write:'DB 写入',ddl:'DDL / 隐式 COMMIT',ai:'AI 请求 / 确认外部调用及费用','write-ai':'DB 写入 / 确认外部调用及费用'}
};
const localeKey=locale=>{const value=String(locale).toLowerCase();return value.startsWith('ko')?'ko':value.startsWith('ja')?'ja':value.startsWith('zh')?'zh':'en';};
const local=(value,lang)=>typeof value==='string'?value:value?.[lang==='ko'?'ko':'en']||'';
export function operationsFor(feature,locale='ko') {
  const lang=localeKey(locale);
  return (sqlHelpCatalog[feature]||[]).map(item=>({id:item.id,title:local(item.title,lang)}));
}
export function helpFor(feature,locale='ko',operation) {
  const entries=sqlHelpCatalog[feature];
  if(!entries?.length)return null;
  const item=operation?entries.find(value=>value.id===operation):entries[0];
  if(!item)return null;
  const lang=localeKey(locale),ko=lang==='ko',ui=labels[lang];
  const placeholders=[...new Set((item.sql+'\n'+(item.verify||'')).match(/<[A-Z][A-Z0-9_]*>|(?<![\w:]):[a-z][a-z0-9_]*/g)||[])];
  return {...item,title:local(item.title,lang),purpose:local(item.purpose,lang),labels:ui,
    variables:(placeholders.length?placeholders.join(', '):ko?'별도 입력값 없음.':'No input placeholders.')+'\n'+(ko?'꺾쇠 예시는 실제 값으로 바꾸고, :이름은 SQLcl/JDBC에서 알맞은 타입으로 바인드하세요. 문자열의 작은따옴표는 두 번 써서 이스케이프합니다. SET/VARIABLE/PRINT 및 /는 SQLcl 명령이며 JDBC 구문에는 포함하지 않습니다.':'Replace angle-bracket placeholders; bind :names with suitable types in SQLcl/JDBC. Escape literal single quotes by doubling them. SET/VARIABLE/PRINT and / are SQLcl commands, not JDBC SQL.'),
    permission:ko?'조회는 대상 사전 뷰·테이블 SELECT 권한이 필요합니다(ORA-00942는 권한 또는 객체 가용성 확인 필요). 변경은 소유자·해당 패키지 EXECUTE/DDL 권한이 필요하며, ADMIN·IAM 설정이 필요한 예제는 별도로 표시합니다. USER_*는 로그인 사용자 기준입니다.':'Requires SELECT on the referenced views/tables; ORA-00942 may indicate unavailable objects or privileges. Changes require owner/package EXECUTE/DDL privileges; ADMIN/IAM steps are noted separately. USER_* refers to the logged-in user.',
    effects:ui[item.risk]+' · '+ui.notice,
    result:local(item.note,lang),verify:item.verify||'',source:item.source
  };
}
export function featureForPath(path){if(path==='/db/vpd')return'vpd';if(path.includes('ai-test/problems'))return'problem-questions';if(path.includes('credentials'))return'credentials';if(path.includes('ai-profiles'))return'profiles';if(path.includes('ai-test'))return'ai-test';if(path.includes('executions')||path.includes('mapped-sql'))return'executions';if(path.includes('functions'))return'functions';if(path.includes('tables'))return'tables';if(path.includes('feedback'))return'feedback';if(path.includes('agents'))return'agents';if(path.includes('scheduler'))return'scheduler';if(path.includes('external-sources'))return'external';if(path.includes('security'))return'security';if(path.includes('vector'))return'vectors';if(path.includes('ontology-query'))return'ontology-query';if(path.includes('ontology'))return'ontology';if(path.includes('assistant'))return'assistant';return null;}
export function featureForHelpOpener(opener,fallback){return opener?.dataset?.sqlHelpFor||opener?.closest?.('[data-sql-help-feature]')?.dataset?.sqlHelpFeature||fallback;}
export async function copySql(text){await navigator.clipboard.writeText(text);}
export function mountSqlHelp(root,feature){
  const dialog=root.querySelector('[data-sql-help-dialog]');if(!dialog)return;
  const doc=root.ownerDocument||document,lang=doc.documentElement.lang;
  const status=dialog.querySelector('[data-sql-help-status]'),select=dialog.querySelector('[data-sql-help-operation]');
  let current=null,currentFeature=feature,opener=null;
  const render=operation=>{
    current=helpFor(currentFeature,lang,operation);if(!current)return;
    if(status)status.textContent='';
    for(const key of ['purpose','sql','variables','permission','effects','result','verify','source']){
      const node=dialog.querySelector(`[data-sql-help-${key}]`);if(node)node.textContent=current[key];
    }
    dialog.querySelector('[data-sql-help-doc]')?.setAttribute('href',current.doc);
    for(const node of dialog.querySelectorAll('[data-sql-help-label]'))node.textContent=current.labels[node.dataset.sqlHelpLabel];
    const badge=dialog.querySelector('[data-sql-help-risk]');if(badge){badge.textContent=current.labels[current.risk];badge.dataset.risk=current.risk;}
    const verification=dialog.querySelector('[data-sql-help-verification]');if(verification)verification.hidden=!current.verify;
    const fallback=dialog.querySelector('[data-sql-help-fallback]');if(fallback){fallback.hidden=!current.labels.fallback;fallback.textContent=current.labels.fallback;}
  };
  doc.addEventListener('click',event=>{
    const target=event.target.closest('[data-sql-help-for]');if(!target)return;
    const selectedFeature=featureForHelpOpener(target,feature),items=operationsFor(selectedFeature,lang);
    const operation=target.dataset.sqlHelpOperation||items[0]?.id;
    if(!helpFor(selectedFeature,lang,operation))return;
    opener=target;currentFeature=selectedFeature;
    if(select){select.replaceChildren();for(const item of items){const option=doc.createElement('option');option.value=item.id;option.textContent=item.title;select.append(option);}select.value=operation;}
    render(operation);if(!dialog.open)dialog.showModal();select?.focus();
  });
  select?.addEventListener('change',()=>render(select.value));
  dialog.querySelector('[data-sql-help-close]')?.addEventListener('click',()=>dialog.close());
  dialog.addEventListener('close',()=>opener?.focus());
  const copied=(text,selected)=>copySql(text).then(()=>{if(status&&current===selected)status.textContent=selected.labels.copied;}).catch(()=>{if(status&&current===selected)status.textContent=selected.labels.copyFailed;});
  dialog.querySelector('[data-sql-help-copy]')?.addEventListener('click',()=>{if(current)copied(current.sql,current);});
  dialog.querySelector('[data-sql-help-copy-verify]')?.addEventListener('click',()=>{if(current?.verify)copied(current.verify,current);});
}
