import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {sourceTokens,sourceLines,matchingLines,declarationLine} from '../../main/resources/static/js/source-viewer.mjs';
import {functionUrl,refreshFunctions} from '../../main/resources/static/js/functions.mjs';
import {pageOf} from '../../main/resources/static/js/table-list.mjs';
test('PL/SQL display lexer preserves exact source including quoted names, comments and Oracle q literals',()=>{
  const text=`create or replace function "Fn.명" return varchar2 as\r\n-- function fake\n/* <script> */\n v varchar2(20):=q'[one 'two'\nthree]';\n n nvarchar2(20):=n'한글''日本語';\nbegin return v; end;\n`;
  const tokens=sourceTokens(text);assert.equal(tokens.map(t=>t.text).join(''),text);
  assert.equal(sourceLines(text).map(line=>line.map(token=>token.text).join('')).join('\n'),text);
  assert.ok(tokens.some(t=>t.kind==='keyword'&&t.text==='function'));
  assert.ok(tokens.some(t=>t.kind==='string'&&t.text.startsWith("q'[")));
  assert.ok(tokens.some(t=>t.kind==='comment'&&t.text.includes('<script>')));
  assert.ok(tokens.some(t=>t.kind==='identifier'&&t.text==='"Fn.명"'));
});
test('unterminated strings and comments never discard source and q delimiters are paired',()=>{
  for(const text of ["q'{abc''def}'","nq'<abc>'","q'!abc!'","'unterminated","/* unfinished","-- line","\"a\"\"b\"","","--\n"])assert.equal(sourceTokens(text).map(t=>t.text).join(''),text);
});
test('member navigation skips comments and strings and respects quoted identifiers',()=>{
  const text="-- function F return number;\nX := 'function F';\nprocedure G is begin null; end;\nfunction F return number is\nbegin return 1; end;";
  assert.equal(declarationLine(text,'F'),4);assert.equal(declarationLine(text,'G'),3);assert.equal(declarationLine(text,'missing'),1);
  assert.equal(declarationLine('package P as\nfunction "f" return number;','f'),2);
  assert.equal(declarationLine('package P as\nfunction "f" return number;','F'),1);
});
test('search uses literal matching with CRLF and counts matching lines',()=>{
  assert.deepEqual(matchingLines('ABC\r\nabc abc\nother','AbC'),[1,2]);assert.deepEqual(matchingLines('select .*\nother','.*'),[1]);assert.deepEqual(matchingLines('abc',''),[]);
});
test('large source loses highlighting but never loses characters',()=>{
  const text='-- long\n'+'가'.repeat(300000)+'\n';assert.equal(sourceLines(text).map(line=>line.map(t=>t.text).join('')).join('\n'),text);
  assert.equal(declarationLine(text,'F'),1);
});
test('function links quote resolved names and preserve context and package member',()=>{
  const url=new URL(functionUrl('/db/functions','APP',{owner:'Other.Schema',object:'P"KG',member:'F<>#'}),'http://localhost');
  assert.equal(url.pathname,'/db/functions');assert.equal(url.searchParams.get('schema'),'APP');assert.equal(url.searchParams.get('name'),'"Other.Schema"."P""KG"."F<>#"');
  const standalone=new URL(functionUrl('/db/functions','APP',{owner:'APP',object:'F',member:null}),'http://localhost');assert.equal(standalone.searchParams.get('name'),'"APP"."F"');
});
test('function filtering is contains and uses ten-row client pages',()=>{
  const items=Array.from({length:23},(_,i)=>({name:'PKG.F_'+i}));assert.equal(pageOf(items,'',1).items.length,10);assert.equal(pageOf(items,'',3).items.length,3);assert.equal(pageOf(items,'f_2',1).total,4);
});
test('explicit refresh waits for list invalidation before requesting current detail',async()=>{
  const order=[];let release;const listFinished=new Promise(resolve=>{release=resolve;});
  const result=refreshFunctions(async force=>{assert.equal(force,true);order.push('list');await listFinished;order.push('list-done');return true;},async reference=>{order.push('detail:'+reference);},'APP.FN');
  assert.deepEqual(order,['list']);release();assert.equal(await result,true);assert.deepEqual(order,['list','list-done','detail:APP.FN']);
});
test('failed refresh never reuses detail and no selection does not prefetch source',async()=>{
  let calls=0;assert.equal(await refreshFunctions(async()=>false,async()=>{calls++;},'APP.FN'),false);assert.equal(calls,0);
  assert.equal(await refreshFunctions(async()=>true,async()=>{calls++;},''),true);assert.equal(calls,0);
  await assert.rejects(refreshFunctions(async()=>{throw new Error('reload failed');},async()=>{calls++;},'APP.FN'),/reload failed/);assert.equal(calls,0);
});
test('viewer and function screen render source as text and never send writes',()=>{
  const viewer=fs.readFileSync('src/main/resources/static/js/source-viewer.mjs','utf8'),page=fs.readFileSync('src/main/resources/static/js/functions.mjs','utf8');
  assert.doesNotMatch(viewer+page,/innerHTML|eval\(|contentEditable|method:\s*['"]POST|\/execute|\/compile/);
  assert.match(viewer,/clipboard\.writeText\(text\)/);assert.match(viewer,/textContent=text/);assert.match(page,/AbortController/);assert.match(page,/scope|schema/);
});
test('feature message translations are complete and preserve arguments',()=>{
  for(const file of ['feature-functions.json','feature-row-history.json']){
    for(const [key,values]of Object.entries(JSON.parse(fs.readFileSync('tools/i18n/'+file,'utf8')))){
      assert.equal(values.length,4,key);const args=v=>[...new Set(v.match(/\{\d+\}/g)||[])].sort();
      values.forEach((value,i)=>{assert.ok(value.trim());assert.deepEqual(args(value),args(values[0]));if(i)assert.doesNotMatch(value,/[가-힣]/u);});
    }
  }
});
