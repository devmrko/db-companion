import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createEditGuard} from '../../main/resources/static/js/edit-guard.mjs';

test('a clean screen never asks for confirmation',()=>{
  const guard=createEditGuard();guard.register(()=>false);
  assert.equal(guard.canLeave(()=>assert.fail('unnecessary confirmation')),true);
});
test('definition and relationship edits are read independently and cancellation blocks leaving',()=>{
  const guard=createEditGuard();let definition=false,relationship=true;
  guard.register(()=>definition||relationship);
  assert.equal(guard.canLeave(()=>false),false);
  assert.equal(guard.canLeave(()=>true),true);
  relationship=false;definition=true;
  assert.equal(guard.canLeave(()=>false),false);
});
test('saving or discarding clears the warning using current editor state',()=>{
  const guard=createEditGuard();let dirty=true;guard.register(()=>dirty);
  assert.equal(guard.hasChanges(),true);dirty=false;
  assert.equal(guard.canLeave(()=>assert.fail('saved editor is clean')),true);
});
test('existing form edits still warn and readers can be removed',()=>{
  const guard=createEditGuard(),remove=guard.register(()=>true);
  remove();assert.equal(guard.hasChanges(),false);
  assert.equal(guard.canLeave(()=>false,true),false);
  assert.equal(guard.canLeave(()=>true,true),true);
});
test('language and schema controls consult the registered ontology state and restore cancelled selections',()=>{
  const common=readFileSync('src/main/resources/static/js/common.js','utf8');
  const ontology=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');
  assert.match(ontology,/editGuard\.register\(\(\)=>dirty\|\|relationships\.dirty\(\)\)/);
  assert.equal((common.match(/editGuard\.canLeave/g)||[]).length,2);
  assert.equal((common.match(/select\.value\s*=\s*original/g)||[]).length,2);
  assert.ok(common.includes('edited)')); // Existing form/dialog handling is retained.
});
