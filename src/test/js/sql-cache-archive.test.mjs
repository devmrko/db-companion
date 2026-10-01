import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';
import {archiveControls,archiveAccessControls,validInterval} from '../../main/resources/static/js/sql-cache-archive.mjs';
const status={table:'READY',job:'READY',enabled:true,state:'SCHEDULED',sourceAccess:'READY'};
test('no automatic registration while loading or unavailable',()=>{assert.equal(archiveControls(null).INSTALL,false);assert.equal(archiveControls(status,true).RUN,false);assert.equal(archiveControls({...status,table:'CONFLICT'}).INSTALL,false);});
test('missing source does not prevent archive search or pausing',()=>{const c=archiveControls({...status,sourceAccess:'ORA-00942'});assert.equal(c.search,true);assert.equal(c.PAUSE,true);assert.equal(c.RESUME,false);assert.equal(c.RUN,false);});
test('install resume pause and pending capture reflect actual job state',()=>{assert.equal(archiveControls({...status,table:'MISSING',job:'MISSING'}).INSTALL,true);assert.equal(archiveControls({...status,enabled:false}).RESUME,true);assert.equal(archiveControls({...status,enabled:false}).PAUSE,false);assert.equal(archiveControls({...status,state:'RUNNING'}).RUN,false);});
test('interval validation',()=>{for(const v of [0,9,60.1,3601,'x'])assert.equal(validInterval(v),false);for(const v of [10,60,'300',3600])assert.equal(validInterval(v),true);});
test('grant controls require an inspected matching user with missing privileges',()=>{
 const a={username:'DEMO_APP',read:false,select:false,createTable:true,createJob:false};
 assert.equal(archiveAccessControls(null,'DEMO_APP').grant,false);assert.equal(archiveAccessControls(a,'OTHER').grant,false);
 assert.equal(archiveAccessControls(a,'DEMO_APP').grant,true);assert.equal(archiveAccessControls(a,'DEMO_APP',true).grant,false);
 assert.equal(archiveAccessControls({...a,select:true,createJob:true},'DEMO_APP').grant,false);assert.equal(archiveAccessControls(a,'').check,false);
});
test('archive UI uses text rendering, token confirmation, and independent query filters',()=>{const s=fs.readFileSync('src/main/resources/static/js/sql-cache-archive.mjs','utf8');assert.ok(!s.includes('innerHTML'));assert.match(s,/preview\.token/);assert.match(s,/consent:true/);assert.match(s,/queryFailed/);assert.match(s,/clearRows/);});
test('all four UI languages include archive labels',()=>{const data=JSON.parse(fs.readFileSync('tools/i18n/feature-sql-cache-archive.json','utf8'));for(const labels of Object.values(data)){assert.equal(labels.length,4);assert.ok(labels.every(s=>s.length));}});
