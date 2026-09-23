import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {parseShowprompt,inspectionMatches,sameProfile,optionEnabled,promptTable,sqlTable,feedbackMatch,mountInspection,renderPromptInspection,renderSqlReferences} from '../../main/resources/static/js/select-ai-inspection.mjs';

const examples=[{user_prompt:'샘플게임 AU / 사업 AU',sql_query:"WITH q AS (SELECT 'a]b' X FROM DUAL) SELECT * FROM q"},{user_prompt:'다른 질문',sql_query:'응답'}];
const system=`Available tables:\n--'표준 AU는 EXPT_USER_YN=''N'' (테이블 설명)' # CREATE TABLE "DEMO_APP"."USERS" ("GUID" VARCHAR2(32767) '식별자, 설명', "BASE_DT" DATE '날짜', "AU_FLAG" NUMBER(19,0) '값 ''1'' (활성)', "NO_COMMENT" VARCHAR2(20))\n--'사업 AU' # CREATE TABLE "DEMO_APP"."BIZ_USERS" ("GUID" VARCHAR2(100) 'ID', "BIZ_AU_FLAG" VARCHAR2(10) '사업')`;
const source=(schema=system,rows=examples)=>JSON.stringify([{role:'SYSTEM',content:[{type:'TEXT',text:schema}]},{role:'USER',content:[{type:'TEXT',text:'Here are examples of previous successful queries for similar questions that you can refer to and learn from: \n'+JSON.stringify(rows)+'\n\nAdditional Instructions:\n Do not use unrelated examples.\nQuestion:\nuser input'}]}]);
test('structures provided SHOWPROMPT including quoted commas parentheses and apostrophes',()=>{
  const p=parseShowprompt(source());assert.equal(p.tablesParsed,true);assert.equal(p.examplesParsed,true);assert.equal(p.tables.length,2);
  assert.equal(p.tables[0].owner,'DEMO_APP');assert.equal(p.tables[0].name,'USERS');assert.equal(p.tables[0].columns.length,4);assert.equal(p.tables[0].complete,true);
  assert.equal(p.tables[0].comment,"표준 AU는 EXPT_USER_YN='N' (테이블 설명)");assert.equal(p.tables[0].columns[0].comment,'식별자, 설명');
  assert.equal(p.tables[0].columns[2].dataType,'NUMBER(19,0)');assert.equal(p.tables[0].columns[2].comment,"값 '1' (활성)");assert.equal(p.tables[0].columns[3].comment,'');
  assert.deepEqual(p.examples,examples);assert.match(p.tables[0].raw,/# CREATE TABLE/);
});
test('unknown formats are not represented as successful empty lookups',()=>{
  for(const value of [null,'not JSON','{}','[]','x'.repeat(2_000_001)]){const p=parseShowprompt(value);assert.equal(p.tablesParsed,false);assert.equal(p.examplesParsed,false);}
  assert.equal(parseShowprompt(source(system,[])).examplesParsed,true);
  const broken=parseShowprompt(source("# CREATE TABLE \"A\".\"T\" (\"C\" VARCHAR2(2) 'unfinished"));assert.equal(broken.tablesParsed,false);
});
test('SQL instructions and user input do not become metadata definitions or feedback examples',()=>{
  const raw=JSON.stringify([{role:'user',content:'Here are examples of previous successful queries: \nAdditional Instructions:\n'+JSON.stringify(examples)+'\n'+system}]);
  const p=parseShowprompt(raw);assert.equal(p.tablesParsed,false);assert.equal(p.examplesParsed,false);
});
test('quoted identifiers and HTML are preserved as data',()=>{
  const p=parseShowprompt(source(`--'<script>alert(1)</script>' # CREATE TABLE "A""B"."T" ("C""D" VARCHAR2(2) '<img>')`));
  assert.equal(p.tables[0].owner,'A"B');assert.equal(p.tables[0].columns[0].name,'C"D');assert.equal(p.tables[0].columns[0].comment,'<img>');
});
test('profile question and version guard comparisons',()=>{
  const profile={selection:{owner:'APP',name:'P'},provider:'oci',model:'model',version:'v1'};
  assert.equal(inspectionMatches({profile,question:'q'},'P','q'),true);assert.equal(inspectionMatches({profile,question:'q'},'P','q '),false);assert.equal(inspectionMatches(null,'P','q'),false);
  assert.equal(sameProfile(profile,{...profile}),true);assert.equal(sameProfile(profile,{...profile,version:'v2'}),false);assert.equal(sameProfile(profile,{...profile,selection:{owner:'OTHER',name:'P'}}),false);
  assert.equal(optionEnabled('TRUE'),true);assert.equal(optionEnabled('"true"'),true);assert.equal(optionEnabled(undefined),false);assert.equal(optionEnabled('false'),false);
});
test('lookup wiring preserves filters avoids AI calls and renders untrusted text safely',()=>{
  const js=fs.readFileSync('src/main/resources/static/js/select-ai-inspection.mjs','utf8');
  assert.match(js,/filter.value=value.feedback\?\.search\?\?value.question/);assert.match(js,/search:f.search,page:f.page\+1/);assert.match(js,/new URLSearchParams/);
  assert.match(js,/e.textContent=text/);assert.doesNotMatch(js,/innerHTML|localStorage|sessionStorage|setInterval|post\('generate'|search:''/);
  const main=fs.readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8');assert.match(main,/renderPromptInspection/);assert.match(main,/inspection.restore\(data.inspection\)/);assert.match(main,/inspection.invalidate\(\)/);
});
test('reference labels exist in all four languages and template has linked panels',()=>{
  const messages=JSON.parse(fs.readFileSync('tools/i18n/feature-select-ai-inspection.json','utf8'));
  for(const values of Object.values(messages)){assert.equal(values.length,4);assert.ok(values.every(v=>v.length>0));}
  const html=fs.readFileSync('src/main/resources/templates/ai-test.html','utf8');assert.match(html,/data-inspect-load disabled/);assert.match(html,/data-test-prompt-inspection/);
});
test('highlight identity is owner plus exact case-sensitive object name',()=>{
  const parsed=parseShowprompt(source());
  assert.equal(promptTable(parsed,{owner:'DEMO_APP',name:'USERS'}),parsed.tables[0]);
  for(const object of [{owner:'OTHER',name:'USERS'},{owner:'DEMO_APP',name:'users'},{owner:'DEMO_APP',name:'*'}])assert.equal(promptTable(parsed,object),null);
  assert.equal(promptTable(null,{owner:'DEMO_APP',name:'USERS'}),null);
});
test('Feedback highlighting needs matching question AND response, never sql_text alone',()=>{
  const parsed=parseShowprompt(source()),row={question:examples[0].user_prompt};
  assert.equal(feedbackMatch(parsed,row,null),'question');
  assert.equal(feedbackMatch(parsed,row,{detail:{question:row.question,sqlText:examples[0].sql_query}}),'question');
  assert.equal(feedbackMatch(parsed,row,{detail:{question:row.question,response:'different',sqlText:examples[0].sql_query}}),'different');
  assert.equal(feedbackMatch(parsed,row,{detail:{question:row.question,response:examples[0].sql_query}}),'feedback');
  assert.equal(feedbackMatch(parsed,row,{detail:{question:'other',response:examples[0].sql_query}}),'question');
  assert.equal(feedbackMatch(parsed,{question:'unrelated'},null),null);
  assert.equal(feedbackMatch(parseShowprompt('unknown'),row,null),null);
  assert.equal(feedbackMatch(parseShowprompt(source(system,[])),row,null),null);
});

class Element {
  constructor(tag){this.tagName=tag;this.children=[];this.events={};this.attributes={};this.text='';this.value='';}
  set textContent(v){this.text=String(v);this.children=[];}
  get textContent(){return this.text+this.children.map(c=>c.textContent).join('');}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.text='';this.children=[...nodes];}
  addEventListener(name,fn){this.events[name]=fn;}
  setAttribute(name,value){this.attributes[name]=value;}
  all(){return this.children.flatMap(c=>[c,...c.all()]);}
  querySelectorAll(selector){return this.all().filter(e=>selector.split(',').includes(e.tagName));}
}
test('rendered panel wires exact question, table details and paging without implicit calls',async()=>{
  const previous=globalThis.document;globalThis.document={createElement:tag=>new Element(tag)};
  try{
    const elements=Object.fromEntries(['load','body','stale'].map(key=>[key,new Element(key==='load'?'button':'div')]));
    const root={querySelector:selector=>elements[selector.match(/data-inspect-(\w+)/)[1]]};
    let busy=false,question='샘플게임 AU / 사업 AU';const calls=[];
    const value={id:'snapshot',profile:{selection:{owner:'APP',name:'P'},provider:'oci',model:'model',version:'v1'},question,checkedAt:'2026-09-22T09:00:00Z',settings:{comments:'true',annotations:'true',object_list:'[]'},objects:[{owner:'APP',name:'T'}],tables:[],feedback:{search:question,page:1,checkedAt:'2026-09-22T09:00:00Z',rows:{items:[{id:'ROW',question,type:'negative',feedback:'fix'}],hasNext:true}},feedbackDetails:[]};
    const module=mountInspection(root,{post:async(path,data)=>{calls.push({path,data});return value;},isBusy:()=>busy,setBusy:v=>{busy=v;},selected:()=> 'P',question:()=>question,prompt:()=>null,message:()=>{}});
    module.restore(value);assert.equal(calls.length,0);
    assert.equal(elements.body.querySelectorAll('input')[0].value,question);
    await elements.load.events.click();assert.deepEqual(calls[0],{path:'inspection',data:{profile:'P',question}});
    const find=label=>elements.body.all().find(e=>e.tagName==='button'&&e.textContent===label);
    await find('테이블·컬럼 조회').events.click();assert.deepEqual(calls[1],{path:'inspection/table',data:{id:'snapshot',owner:'APP',name:'T'}});
    await find('다음').events.click();assert.equal(calls[2].data.search,question);assert.equal(calls[2].data.page,2);
    await find('Feedback 원문 조회').events.click();assert.equal(calls[3].data.rowId,'ROW');
    question='changed';module.controls();assert.ok(elements.body.querySelectorAll('button,input').every(e=>e.disabled));assert.match(elements.stale.textContent,/바뀌었습니다/);
    module.invalidate();assert.equal(elements.body.hidden,true);
  }finally{globalThis.document=previous;}
});
test('prompt renderer shows structured columns and SQL without creating injected elements',()=>{
  const previous=globalThis.document;globalThis.document={createElement:tag=>new Element(tag)};
  try{const host=new Element('div');renderPromptInspection(host,{text:source(system,[{user_prompt:'<script>question</script>',sql_query:'<img src=x onerror=alert(1)>'}]),question:'q'});
    assert.match(host.textContent,/GUID/);assert.match(host.textContent,/<img src=x/);assert.equal(host.all().some(e=>['img','script'].includes(e.tagName)),false);
    renderPromptInspection(host,{text:'unsupported',question:'q'});assert.match(host.textContent,/구조화하지 못했습니다/);
  }finally{globalThis.document=previous;}
});
test('prompt rendering highlights included definitions and examples with text labels',()=>{
  const previous=globalThis.document;globalThis.document={createElement:tag=>new Element(tag)};
  try{const host=new Element('div');renderPromptInspection(host,{text:source(),question:examples[0].user_prompt});
    const marked=host.all().filter(e=>e.attributes['data-ref-state']);
    assert.equal(marked.filter(e=>e.tagName==='details'&&e.attributes['data-ref-state']==='definition').length,2);
    assert.equal(marked.filter(e=>e.tagName==='details'&&e.attributes['data-ref-state']==='feedback').length,2);
    assert.match(host.textContent,/프롬프트 정의 포함/);assert.match(host.textContent,/프롬프트 예제 포함/);assert.match(host.textContent,/과거 실제 전송/);
    renderPromptInspection(host,{text:'unsupported'});assert.equal(host.all().filter(e=>e.attributes['data-ref-state']).length,0);
  }finally{globalThis.document=previous;}
});
test('current catalog highlights exact included columns/comments and fully matched Feedback only',()=>{
  const previous=globalThis.document;globalThis.document={createElement:tag=>new Element(tag)};
  try{
    const elements=Object.fromEntries(['load','body','stale'].map(key=>[key,new Element(key==='load'?'button':'div')]));
    const root={querySelector:selector=>elements[selector.match(/data-inspect-(\w+)/)[1]]};
    const profile={selection:{owner:'DEMO_APP',name:'P'},provider:'oci',model:'m',version:'v1'};
    const definition=parseShowprompt(source()).tables[0],stamp='2026-09-22T12:00:00Z';let question=examples[0].user_prompt,compatible=true;
    let prompt={profile,question,text:source(),requestedAt:stamp};
    const value={id:'s',profile,question,checkedAt:stamp,settings:{},objects:[{owner:'DEMO_APP',name:'USERS'},{owner:'OTHER',name:'USERS'}],tables:[{...definition,type:'TABLE',checkedAt:stamp,error:null,columns:definition.columns.map((c,i)=>i===1?{...c,comment:'changed comment'}:c).concat({name:'NOT_IN_PROMPT',dataType:'DATE',comment:'new'}),annotations:[{column:'GUID',name:'note',value:'annotation not checked'}],annotationStatus:'LOADED'}],feedback:{search:question,page:1,checkedAt:stamp,rows:{items:[{id:'ROW',question,type:'negative'},{id:'ROW2',question:examples[1].user_prompt,type:'negative'}],hasNext:false}},feedbackDetails:[{id:'ROW',checkedAt:stamp,detail:{question,response:examples[0].sql_query,sqlText:'SELECT AI old'}}]};
    const module=mountInspection(root,{post:()=>assert.fail('No implicit call'),isBusy:()=>false,setBusy:()=>{},selected:()=> 'P',question:()=>question,prompt:()=>prompt,promptCompatible:()=>compatible,message:()=>{}});
    module.restore(value);
    const marked=()=>elements.body.all().filter(e=>e.attributes['data-ref-state']);
    assert.equal(marked().filter(e=>e.tagName==='details'&&e.attributes['data-ref-state']==='definition').length,1);
    assert.ok(marked().some(e=>e.tagName==='td'&&e.textContent.startsWith('GUID')));
    assert.ok(marked().some(e=>e.tagName==='td'&&e.textContent==='식별자, 설명'));
    assert.equal(marked().some(e=>e.textContent==='changed comment'||e.textContent==='annotation not checked'||e.textContent.startsWith('NOT_IN_PROMPT')),false);
    assert.equal(marked().filter(e=>e.tagName==='details'&&e.attributes['data-ref-state']==='feedback').length,1);
    assert.equal(marked().filter(e=>e.tagName==='details'&&e.attributes['data-ref-state']==='question').length,1);
    elements.body.querySelectorAll('input')[0].value='검색어 유지';
    compatible=false;module.controls();assert.equal(marked().length,0);assert.equal(elements.body.querySelectorAll('input')[0].value,'검색어 유지');
    compatible=true;module.controls();assert.ok(marked().length>0);
    question='changed';module.controls();assert.equal(marked().length,0);
    question=value.question;module.controls();assert.ok(marked().length>0);
    prompt={...prompt,profile:{...profile,version:'v2'}};module.controls();assert.equal(marked().length,0);
    prompt={...prompt,profile,error:'unavailable'};module.controls();assert.equal(marked().length,0);
    prompt={...prompt,error:null,text:'unknown format'};module.controls();assert.equal(marked().length,0);
    module.invalidate();assert.equal(elements.body.hidden,true);
  }finally{globalThis.document=previous;}
});
test('highlight CSS scopes colors to marked content and provides non-color state markers',()=>{
  const css=fs.readFileSync('src/main/resources/static/css/common.css','utf8');
  for(const state of ['definition','feedback','question','different'])assert.ok(css.includes(`.app-inspection .app-ref-badge.app-ref-${state}`));
  assert.match(css,/details\[data-ref-state\] > summary/);assert.match(css,/forced-colors: active/);
  const main=fs.readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8');assert.match(main,/promptCompatible:\(\)=>evidence.ready\(\)&&evidence.matches\(snapshot\?\.evidence\)/);
});
test('SQL references match owner and name without guessing unqualified names',()=>{
  const analysis={status:'PARSED',tables:[{owner:'APP',name:'T',sqlName:'APP.T'},{owner:null,name:'UNQUALIFIED',sqlName:'UNQUALIFIED'}]};
  assert.equal(sqlTable(analysis,{owner:'APP',name:'T'}),analysis.tables[0]);
  for(const object of [{owner:'OTHER',name:'T'},{owner:'APP',name:'t'},{owner:'APP',name:'UNQUALIFIED'}])assert.equal(sqlTable(analysis,object),null);
  assert.equal(sqlTable({...analysis,status:'UNSUPPORTED'},{owner:'APP',name:'T'}),null);
});
test('generated SQL references highlight catalog tables WITHOUT any SHOWPROMPT and survive restore',()=>{
  const previous=globalThis.document;globalThis.document={createElement:tag=>new Element(tag)};
  try{
    const elements=Object.fromEntries(['load','body','stale'].map(key=>[key,new Element(key==='load'?'button':'div')]));
    const root={querySelector:selector=>elements[selector.match(/data-inspect-(\w+)/)[1]]};
    const profile={selection:{owner:'DEMO_APP',name:'P'},provider:'oci',model:'m',version:'v1'},stamp='2026-09-22T12:00:00Z';
    let question='샘플게임 2026-08-03 표준 AU / 사업 AU 알려줘',compatible=true;
    const names=['DEMO_NATIVE_GAME_USER_CTAS_BUILD','DEMO_NATIVE_GAME_BIZ_USER'];
    let generated={id:'sql1',action:'SQL',profile,question,requestedAt:stamp,error:'ORA-20004',sqlReferences:{status:'PARSED',source:'REJECTED_RESPONSE',tables:names.map(name=>({owner:'DEMO_APP',name,sqlName:'DEMO_APP.'+name}))}};
    const value={id:'catalog',profile,question,checkedAt:stamp,settings:{},objects:[...names.map(name=>({owner:'DEMO_APP',name})),{owner:'DEMO_APP',name:'NOT_USED'},{owner:'OTHER',name:names[0]}],tables:[],feedback:null,feedbackDetails:[]};
    const module=mountInspection(root,{post:()=>assert.fail('No implicit request'),isBusy:()=>false,setBusy:()=>{},selected:()=> 'P',question:()=>question,prompt:()=>null,promptCompatible:()=>false,generated:()=>generated,generatedCompatible:()=>compatible,message:()=>{}});
    const used=()=>elements.body.all().filter(e=>e.tagName==='details'&&e.attributes['data-ref-state']==='sql');
    module.restore(value);assert.equal(used().length,2);assert.ok(used().every(e=>names.some(n=>e.children[0].textContent.includes(n))));
    assert.match(elements.body.textContent,/거절 응답/);assert.doesNotMatch(elements.body.textContent,/강조 비교 대기/);
    module.restore(value);assert.equal(used().length,2);
    question='other';module.controls();assert.equal(used().length,0);
    question=value.question;module.controls();assert.equal(used().length,2);
    compatible=false;module.controls();assert.equal(used().length,0);
    compatible=true;module.controls();assert.equal(used().length,2);
    generated={...generated,profile:{...profile,version:'v2'}};module.controls();assert.equal(used().length,0);
    generated={...generated,profile,sqlReferences:{status:'UNSUPPORTED',source:'RESPONSE',tables:[]}};module.controls();assert.equal(used().length,0);assert.match(elements.body.textContent,/분석하지 못했습니다/);
    generated=null;module.controls();assert.equal(used().length,0);assert.match(elements.body.textContent,/프롬프트 보기는 필요하지 않습니다/);
  }finally{globalThis.document=previous;}
});
test('generated SQL reference summary preserves unsupported and empty states and never interprets names as HTML',()=>{
  const previous=globalThis.document;globalThis.document={createElement:tag=>new Element(tag)};
  try{
    const host=new Element('div'),outcome={id:'r',action:'SQL',requestedAt:'2026-09-22T12:00:00Z',sqlReferences:{status:'PARSED',source:'RESPONSE',tables:[{owner:'APP',name:'<img>',sqlName:'APP."<img>"'},{owner:null,name:'T',sqlName:'T'}]}};
    renderSqlReferences(host,outcome);assert.match(host.textContent,/<img>/);assert.equal(host.all().some(e=>e.tagName==='img'),false);assert.match(host.textContent,/소유자 미지정/);
    renderSqlReferences(host,{...outcome,sqlReferences:{status:'PARSED',tables:[]}});assert.match(host.textContent,/테이블 참조가 없습니다/);
    renderSqlReferences(host,{...outcome,sqlReferences:{status:'UNSUPPORTED',tables:[]}});assert.match(host.textContent,/분석하지 못했습니다/);assert.doesNotMatch(host.textContent,/테이블 참조가 없습니다/);
  }finally{globalThis.document=previous;}
});
test('SQL reference wiring is independent from prompt completion and execution eligibility',()=>{
  const main=fs.readFileSync('src/main/resources/static/js/select-ai-test.mjs','utf8'),template=fs.readFileSync('src/main/resources/templates/ai-test.html','utf8');
  assert.match(main,/generated:\(\)=>latest,generatedCompatible:\(\)=>evidence.ready\(\)&&evidence.matches\(latest\?\.evidence\)/);
  assert.match(main,/renderSqlReferences\(get\('sql-references'\),outcome\)/);assert.match(template,/data-test-sql-references/);
  assert.match(main,/Boolean\(outcome.error\)\|\|!isSqlResponse\(outcome.text\)/);
});
