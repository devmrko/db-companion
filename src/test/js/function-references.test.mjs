import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {sourceTokens,sourceLines,packageFunctionIndex,referenceTokens} from '../../main/resources/static/js/source-viewer.mjs';
const entries=[
  {object:'DX_SELECT_AI_SQL_OWNER',member:'GENERATE_SQL',reference:'"ADMIN"."DX_SELECT_AI_SQL_OWNER"."GENERATE_SQL"'},
  {object:'P',member:'F',reference:'"ADMIN"."P"."F"'},
  {object:'STANDALONE',member:null,reference:'"ADMIN"."STANDALONE"'}
];
const index=packageFunctionIndex('ADMIN',entries);
const linked=(source,owner='ADMIN',catalog=index)=>referenceTokens(sourceTokens(source),owner,catalog).filter(token=>token.reference);
test('actual qualified ASK call resolves only the function token using DB catalogue names',()=>{
  const source='l_sql := admin.dx_select_ai_sql_owner.generate_sql(p_question);';
  assert.deepEqual(linked(source).map(t=>[t.text,t.reference]),[['generate_sql',entries[0].reference]]);
  assert.equal(linked(source)[0].start,source.indexOf('generate_sql'));
});
test('unqualified packages resolve against source owner, never the browsing schema by accident',()=>{
  assert.equal(linked('x := p.f(1);').length,1);
  assert.equal(linked('x := p.f(1);','OTHER').length,0);
  assert.equal(linked('x := ADMIN.P.F(1);','OTHER').length,1);
  assert.equal(linked('x := OTHER.P.F(1);').length,0);
});
test('ordinary and Oracle q strings, comments and dynamic SQL remain plain source',()=>{
  const source=`-- admin.p.f(1)\n/* p.f(2) */\nx := 'p.f(3)'; y := N'admin.p.f(4)'; z:=q'[p.f(5)]';\nexecute immediate nq'!admin.p.f(6)!';\nx:=p.f(7);`;
  assert.equal(linked(source).length,1);assert.equal(linked(source)[0].start,source.lastIndexOf('f(7)'));
});
test('comments and newlines around dot separators preserve genuine call recognition',()=>{
  assert.equal(linked('x:=admin /*note*/ . p\n . f /*args*/ (1);').length,1);
});
test('quoted case-sensitive Unicode and escaped quotes match exactly without rewriting source',()=>{
  const catalog=packageFunctionIndex('Mixed.Owner',[{object:'P"KG',member:'Fn.명',reference:'"Mixed.Owner"."P""KG"."Fn.명"'}]);
  const source='x := "Mixed.Owner"."P""KG"."Fn.명"(1);';
  assert.equal(linked(source,'Mixed.Owner',catalog).length,1);
  assert.equal(linked('x := "mixed.owner"."P""KG"."Fn.명"(1);','Mixed.Owner',catalog).length,0);
  const lines=sourceLines(source,{owner:'Mixed.Owner',index:catalog});assert.equal(lines.flat().map(p=>p.text).join(''),source);
});
test('unknown functions, variables, standalone calls, DB links and longer chains are excluded',()=>{
  for(const source of ['p.unknown(1);','p.f;','admin.p.f;','f(1);','standalone(1);','admin.p.f@REMOTE(1);','X.ADMIN.P.F(1);',':p.f(1);',':admin.p.f(1);'])assert.equal(linked(source).length,0,source);
});
test('local variables, parameters and local routines shadow catalog roots conservatively',()=>{
  for(const source of [
    'declare p object_type; begin p.f(1); end;',
    'function ask(p in object_type) return clob is begin return p.f(1); end;',
    'function p return some_type is begin null; end; begin p.f(1); end;',
    'declare admin object_type; begin admin.p.f(1); end;',
    'for p in c loop p.f(1); end loop;'
  ])assert.equal(linked(source).length,0,source);
});
test('catalog requires package membership and suppresses conflicting references',()=>{
  assert.equal(index.size,2);
  const conflict=packageFunctionIndex('ADMIN',[entries[1],{...entries[1],reference:'OTHER'},entries[1]]);
  assert.equal(linked('p.f(1);','ADMIN',conflict).length,0);
  const copy=packageFunctionIndex('ADMIN',[entries[1],entries[1]]);assert.equal(linked('p.f(1);','ADMIN',copy).length,1);
});
test('link metadata never alters exact source, CRLF, indentation or large-source fallback',()=>{
  const text='-- 한국어\r\n\tl_sql := admin.p.f(\r\n  1);\n';
  const lines=sourceLines(text,{owner:'ADMIN',index});
  assert.equal(lines.map(line=>line.map(part=>part.text).join('')).join('\n'),text);
  assert.equal(lines.flat().filter(part=>part.reference).length,1);
  const large=' '.repeat(250001)+'admin.p.f(1);';const plain=sourceLines(large,{owner:'ADMIN',index});
  assert.equal(plain.flat().filter(part=>part.reference).length,0);assert.equal(plain.flat().map(part=>part.text).join(''),large);
});
test('native anchors preserve browser navigation and no extra network request resolves symbols',()=>{
  const viewer=fs.readFileSync('src/main/resources/static/js/source-viewer.mjs','utf8');
  assert.match(viewer,/el\('a',part.text,'app-source-link'\)/);assert.match(viewer,/link.href=references.href\(part.reference\)/);
  assert.match(viewer,/sourceLines\(text,wrapped\?null:references\)/);
  assert.doesNotMatch(viewer,/fetch\(|innerHTML|eval\(|window.open|localStorage|sessionStorage/);
  const page=fs.readFileSync('src/main/resources/static/js/functions.mjs','utf8');
  assert.match(page,/packageFunctionIndex\(root.dataset.schema,items\)/);assert.match(page,/list\(\).then\(/);
});
