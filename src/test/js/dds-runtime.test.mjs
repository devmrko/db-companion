import test from 'node:test';
import assert from 'node:assert/strict';
import {runtimeSummary,readinessChecks} from '../../main/resources/static/js/dds-runtime.mjs';
test('missing, failed lookups and configured metadata remain distinct',()=>{
 assert.deepEqual(readinessChecks({}).map(r=>r.state),['UNKNOWN','UNKNOWN','UNKNOWN']);
 assert.deepEqual(readinessChecks({provider:'OCI_IAM',oauthConfig:'PRESENT',signingCredential:'PRESENT'}).map(r=>r.state),['CONFIGURED','CONFIGURED','CONFIGURED']);
 assert.deepEqual(readinessChecks({provider:'UNSET',oauthConfig:'MISSING',signingCredential:'UNVERIFIED ORA-01031'}).map(r=>r.state),['ACTION','ACTION','UNKNOWN']);
});
test('runtime observations retain failures and do not claim recall isolation',()=>{
 const text=runtimeSummary({memoryScope:'RECORD_ACCESS_ONLY',steps:[{requestedUser:'A',endUser:'B',status:'IDENTITY_MISMATCH',contextCleared:true}]});
 assert.match(text,/IDENTITY_MISMATCH/);assert.match(text,/record A=—, B=—/);assert.doesNotMatch(text,/passed|isolated/i);
});
