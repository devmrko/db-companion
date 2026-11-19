import test from 'node:test';
import assert from 'node:assert/strict';
import {recoveryNote,recoveryChoices} from '../../main/resources/static/js/problem-recovery.mjs';
import {comparisonRows} from '../../main/resources/static/js/select-ai-comparison-view.mjs';
test('manual recovery notes are not selectable execution evidence',()=>{
  const note={id:'note',snapshotKind:'MANUAL_RECOVERY',conditions:JSON.stringify({recovery:{action:'changed definition',retestId:''}})};
  assert.equal(recoveryNote(note).retestId,'');
  assert.deepEqual(recoveryChoices({attempts:[{id:'attempt'},note]}),[{id:'attempt'}]);
  assert.equal(recoveryNote({...note,conditions:'broken'}),null);
  assert.equal(recoveryNote({...note,snapshotKind:'MANUAL_ENTRY'}),null);
});
test('comparison exposes extra glossary absence rather than claiming no business context',()=>{
  const row=comparisonRows({left:{id:'a'},right:{id:'b',glossary:{hash:'h',selected:[{term:{revision:7,criteria:'AMOUNT-REFUND'}}]}}}).find(r=>r.key==='glossary');
  assert.equal(row.left.status,'NO_EXTRA_ATTACHMENT');assert.equal(row.right.status,'ATTACHED_SNAPSHOT');assert.equal(row.same,false);
});
