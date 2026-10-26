import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {canCreateFunction,canTestFunction,searchFlow} from '../../main/resources/static/js/glossary-function.mjs';
import {isSql} from '../../main/resources/static/js/code-view.mjs';
import {sourceLines} from '../../main/resources/static/js/source-viewer.mjs';

test('only a missing function with ready dictionary, Text and privilege can be created',()=>{
  const status={canCreate:true,dictionary:'READY',text:'READY',function:'MISSING'};
  assert.equal(canCreateFunction(status),true);
  assert.equal(canCreateFunction({...status,function:'LEGACY'}),true);
  assert.equal(canCreateFunction({...status,function:'INCOMPLETE'}),true);
  for(const value of [null,{...status,canCreate:false},{...status,function:'CONFLICT'},{...status,function:'READY'},
    {...status,text:'CHECK_REQUIRED'},{...status,dictionary:'MISSING'}])assert.equal(canCreateFunction(value),false);
  assert.equal(canTestFunction({...status,function:'READY',canCreate:false}),true);
  assert.equal(canTestFunction(status),false);
});
test('common code display preserves SQL including comments, quoted text and HTML-like literals',()=>{
  for(const source of ["SELECT '<script>bad()</script>' FROM dual","-- comment\nBEGIN f(); END;",
    "/* example */\nCREATE FUNCTION f RETURN CLOB AUTHID CURRENT_USER AS BEGIN RETURN q'[<b>text</b>]'; END;"]){
    assert.equal(isSql(source),true);
    assert.equal(sourceLines(source).map(parts=>parts.map(p=>p.text).join('')).join('\n'),source);
  }
  for(const value of ['{"sql":"SELECT 1"}','Revenue definition','urn:uuid:example',''])assert.equal(isSql(value),false);
});
test('the visible pipeline is taken from the actual package code, not invented steps',()=>{
  const body="CREATE PACKAGE BODY X AS\n  FUNCTION validate_question RETURN VARCHAR2;\n  FUNCTION SEARCH(p_question CLOB) RETURN CLOB IS\nBEGIN\n  RETURN build_search_response(p_question);\nEND;\nEND DBC_GLOSSARY_API;";
  assert.equal(searchFlow([{type:'PACKAGE BODY',sql:body}]),"FUNCTION SEARCH(p_question CLOB) RETURN CLOB IS\nBEGIN\n  RETURN build_search_response(p_question);\nEND;");
  assert.equal(searchFlow([]),'');
});
test('package labels use property-safe translated keys in all four languages',()=>{
  const messages=JSON.parse(fs.readFileSync(new URL('../../../tools/i18n/feature-glossary-function.json',import.meta.url),'utf8'));
  assert.equal(Object.keys(messages).some(key=>/\s/.test(key)),false);
  for(const type of ['FUNCTION','PACKAGE','PACKAGE_BODY'])assert.equal(messages['glossaryFunction.part.'+type].length,4);
});
