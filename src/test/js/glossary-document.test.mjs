import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validDocumentFile,documentRows} from '../../main/resources/static/js/glossary-document.mjs';
test('document file bounds and extensions are explicit',()=>{
  for(const name of ['a.txt','a.md','A.DOCX','a.pdf'])assert.equal(validDocumentFile({name,size:100}),true);
  for(const file of [null,{name:'a.pdf',size:0},{name:'a.pdf',size:4_000_001},{name:'a.html',size:100},{name:'a.docx.exe',size:100}])assert.equal(validDocumentFile(file),false);
});
test('only reviewed candidates can be submitted once each',()=>{
  const analysis={candidates:[{},{}]};assert.equal(documentRows(analysis,[0,1]),true);
  for(const rows of [[],[0,0],[2],[-1],[null],['0']])assert.equal(documentRows(analysis,rows),false);
  assert.equal(documentRows(null,[0]),false);
});
test('source and AI output are text; distinct consent and review steps guard writes',()=>{
  const script=readFileSync(new URL('../../main/resources/static/js/glossary-document.mjs',import.meta.url),'utf8');
  assert.doesNotMatch(script,/innerHTML|insertAdjacentHTML|eval\(/);
  assert.match(script,/textContent=text/);
  assert.match(script,/!preview\|\|!consent.checked/);
  assert.match(script,/!saveConsent.checked/);
  assert.match(script,/renderImportPreview\(diff,review/);
  assert.match(script,/invalidatePreview/);
  assert.match(script,/invalidateReview/);
});
