import test from 'node:test';
import assert from 'node:assert/strict';
import {pageProfile} from '../../main/resources/static/js/floating-assistant.mjs';
test('floating assistant takes actual profile context before query context',()=>{
  const location={href:'https://example.test/ai-profiles/detail?profile=QUERY_PROFILE'};
  assert.equal(pageProfile(location,{querySelector:()=>({dataset:{profile:'PAGE_PROFILE'}})}),'PAGE_PROFILE');
  assert.equal(pageProfile(location,{querySelector:()=>null}),'QUERY_PROFILE');
  assert.equal(pageProfile({href:'https://example.test/'},{querySelector:()=>null}),'');
});
