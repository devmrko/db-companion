import test from 'node:test';
import assert from 'node:assert/strict';
import {databaseEmbeddingValue,ociEmbeddingQuery} from '../../main/resources/static/js/embedding-picker.mjs';
test('DB model selection preserves qualified identifier',()=>assert.equal(databaseEmbeddingValue('APP.EMBED'),'database: APP.EMBED'));
test('OCI catalogue requires connection attributes, never inference',()=>{
  const settings={provider:'oci',region:'us-chicago-1',credential_name:'CRED',oci_compartment_id:'compartment'};
  assert.deepEqual(ociEmbeddingQuery(settings),{region:'us-chicago-1',credential:'CRED',compartment:'compartment'});
  for(const key of Object.keys(settings))assert.throws(()=>ociEmbeddingQuery({...settings,[key]:''}));
});
