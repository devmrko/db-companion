import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {preflightRequest} from '../../main/resources/static/js/profile-preflight.mjs';

test('preflight posts exact selected profile with CSRF and no mutation action',()=>{
  const request=preflightRequest({header:'X-CSRF-TOKEN',value:'token'},'APP','PROFILE');
  assert.equal(request.method,'POST');assert.equal(request.headers['X-CSRF-TOKEN'],'token');
  assert.deepEqual(JSON.parse(request.body),{schema:'APP',profile:'PROFILE'});
});
test('preflight renders its point-in-time fingerprint and stale marker without HTML injection',()=>{
  const source=readFileSync('src/main/resources/static/js/profile-preflight.mjs','utf8');
  assert.match(source,/data\.checkedAt/);assert.match(source,/data\.fingerprint/);assert.match(source,/profilePreflight\.stale/);
  assert.match(source,/profilePreflight\.tone\.'/);assert.match(source,/stamp\.textContent/);assert.doesNotMatch(source,/innerHTML/);
});
