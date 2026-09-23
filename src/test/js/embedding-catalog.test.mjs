import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {publicCatalog,publicModelState,MANUAL_MODEL,publicRegions,publicRegionState,MANUAL_REGION} from '../../main/resources/static/js/embedding-catalog.mjs';
import {searchInput} from '../../main/resources/static/js/vector-search.mjs';

test('public text catalogue records sources and date, without environment settings or availability claims',()=>{
  const catalog=publicCatalog('ocigenai');
  assert.match(catalog.reviewedAt,/^\d{4}-\d{2}-\d{2}$/);
  assert.equal(catalog.models.length,5);assert.equal(new Set(catalog.models.map(m=>m.name)).size,5);
  assert.equal(catalog.models.filter(m=>m.deprecated).length,4);
  for(const model of catalog.models){assert.equal(new URL(model.source).hostname,'docs.oracle.com');assert.match(model.name,/^cohere\.embed-/);}
  assert.doesNotMatch(JSON.stringify(catalog),/TENANT|chicago|ocid1|credential|compartment|region|ACTIVE|default/i);
  catalog.models[0].name='mutated';catalog.models.push({});
  assert.equal(publicCatalog('ocigenai').models.length,5);
  assert.equal(publicCatalog('ocigenai').models[0].name,'cohere.embed-v4.0');
  assert.equal(publicCatalog('database'),null);assert.equal(publicCatalog('cohere'),null);
});
test('no default model, known names, manual editing and future models remain distinct',()=>{
  assert.deepEqual(publicModelState('ocigenai',''),{choice:'',manual:false,model:null});
  const state=publicModelState('ocigenai','cohere.embed-v4.0');
  assert.equal(state.choice,'cohere.embed-v4.0');assert.equal(state.manual,false);
  assert.equal(publicModelState('ocigenai',state.choice,true).choice,MANUAL_MODEL);
  assert.equal(publicModelState('ocigenai','',true).manual,true);
  assert.deepEqual(publicModelState('ocigenai','future.embedding-v9'),{choice:MANUAL_MODEL,manual:true,model:null});
  assert.equal(publicModelState('openai','cohere.embed-v4.0').manual,true);
});
test('public models and new names work with unrelated schemas regions and credentials, without compartment',()=>{
  for(const [schema,table,region,credential] of [['FINANCE','ARTICLE_VECTORS','eu-frankfurt-1','EU_EMBED'],['RESEARCH','PAPERS','ap-osaka-1','LAB_CRED']]) {
    for(const model of ['cohere.embed-v4.0','future.embedding-v9']) {
      const selection={schema,table,vector:'VECTOR_DATA',content:'BODY'};
      const values={provider:'ocigenai',model,modelOwner:'',text:'query',region,credential,inputType:'search_query',metric:'COSINE',k:7,externalConsent:true};
      assert.deepEqual(searchInput(selection,values),{selection,...values});
      assert.equal('compartment' in searchInput(selection,values),false);
    }
  }
});
test('public selection does not call APIs, persist settings or change execution options',()=>{
  const code=fs.readFileSync('src/main/resources/static/js/vector-search.mjs','utf8');
  const handler=code.slice(code.indexOf("publicSelect.addEventListener('change'"),code.indexOf("for(const name of ['vector','content'])"));
  assert.match(handler,/find\('model'\)\.value=publicSelect.value/);
  assert.doesNotMatch(handler,/api\(|fetch\(|submit|credential|region|consent|input-type|metric|localStorage|sessionStorage/);
  const catalogCode=fs.readFileSync('src/main/resources/static/js/embedding-catalog.mjs','utf8');
  assert.doesNotMatch(catalogCode,/fetch\(|localStorage|sessionStorage/);
});
test('OCI regions are a sourced OC1 list with no default, not a customer configuration',()=>{
  const catalog=publicRegions();assert.equal(catalog.realm,'OC1');
  assert.equal(new URL(catalog.source).hostname,'docs.oracle.com');
  assert.match(catalog.reviewedAt,/^\d{4}-\d{2}-\d{2}$/);
  assert.equal(catalog.regions.length,11);assert.equal(new Set(catalog.regions.map(r=>r.id)).size,11);
  assert.ok(catalog.regions.some(r=>r.id==='ap-osaka-1'));assert.ok(catalog.regions.some(r=>r.id==='me-dubai-1'));
  assert.ok(catalog.regions.every(r=>/^[a-z]{2}-[a-z]+-[1-9][0-9]?$/.test(r.id)));
  assert.ok(!catalog.regions.some(r=>['uk-gov-london-1','eu-frankfurt-2'].includes(r.id)));
  catalog.regions[0].id='mutated';assert.notEqual(publicRegions().regions[0].id,'mutated');
  assert.doesNotMatch(JSON.stringify(catalog),/TENANT|ocid1|credential|compartment|default/i);
  assert.deepEqual(publicRegionState(''),{choice:'',manual:false});
  assert.deepEqual(publicRegionState('ap-osaka-1'),{choice:'ap-osaka-1',manual:false});
  assert.equal(publicRegionState('ap-osaka-1',true).choice,MANUAL_REGION);
  assert.deepEqual(publicRegionState('ap-newregion-1'),{choice:MANUAL_REGION,manual:true});
});
test('region changes only update existing region value, preserving the model and not running requests',()=>{
  const code=fs.readFileSync('src/main/resources/static/js/vector-search.mjs','utf8');
  const handler=code.slice(code.indexOf("regionSelect.addEventListener('change'"),code.indexOf("find('model').addEventListener('input'"));
  assert.match(handler,/find\('region'\)\.value=regionSelect.value/);assert.match(handler,/syncRegion\(\)/);
  assert.doesNotMatch(handler,/api\(|fetch\(|submit|localStorage|sessionStorage|find\('model'\)\.value/);
});
