import test from 'node:test';
import assert from 'node:assert/strict';
import {canGrant} from '../../main/resources/static/js/semantic-access.mjs';
test('grant confirmation is bound to user and expiration and cannot run while busy',()=>{
 const p={username:'APP',expires:new Date(2000).toISOString()};
 assert.equal(canGrant(p,'APP',true,false,1000),true);
 for(const [user,consent,busy,now] of [['OTHER',true,false,1000],['APP',false,false,1000],['APP',true,true,1000],['APP',true,false,2000]])assert.equal(canGrant(p,user,consent,busy,now),false);
 assert.equal(canGrant(null,'APP',true,false,1000),false);
});
