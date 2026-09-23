import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {archiveCanSave,archiveCanSetup,archiveJson} from '../../main/resources/static/js/ontology-archive.mjs';

test('saving needs owner, chosen path and both stores ready',()=>{
  const state={owner:true,records:'READY',rdf:'READY',api:true};assert.equal(archiveCanSave(state,true),true);
  for(const change of [{owner:false},{records:'MISSING'},{rdf:'MISSING'},{rdf:'CHECK_REQUIRED'},{rdf:'UNAVAILABLE'}])assert.equal(archiveCanSave({...state,...change},true),false);
  assert.equal(archiveCanSave(state,false),false);assert.equal(Boolean(archiveCanSave(null,true)),false);
});
test('partial installation is not recreated by a button',()=>{
  const state={owner:true,records:'READY',rdf:'MISSING',api:true};assert.equal(archiveCanSetup(state),true);
  for(const change of [{owner:false},{records:'MISMATCH'},{rdf:'CHECK_REQUIRED'},{rdf:'READY'},{api:false}])assert.equal(archiveCanSetup({...state,...change}),false);
});
test('JSON display preserves multilingual content and graph identity',()=>{
  const value={question:'사용자 권역?',graph:{namedGraph:'urn:uuid:x/result'},content:'x'.repeat(25000)};
  assert.deepEqual(JSON.parse(archiveJson(value)),value);
});
test('archive UI only renders text and never automatically installs or saves',()=>{
  const source=readFileSync(new URL('../../main/resources/static/js/ontology-archive.mjs',import.meta.url),'utf8');
  assert.ok(source.includes('.textContent'));assert.ok(source.includes("get('save').addEventListener('click'"));
  for(const unsafe of ['innerHTML','insertAdjacentHTML','window.confirm','localStorage','sessionStorage','/generate','/execute'])assert.equal(source.includes(unsafe),false,unsafe);
  assert.ok(source.includes('token:value.token,confirmed:true'));assert.ok(source.includes("prepared=null;get('consent').checked=false"));
});
