import test from 'node:test';
import assert from 'node:assert/strict';
import {references, scalar} from '../../main/resources/static/js/catalog-preview.mjs';

test('agent profile and custom tool routine references are scoped by component kind', () => {
  const attributes = [{name: 'profile_name', value: 'MY_PROFILE'}, {name: 'function', value: 'APP.FETCH_DATA'}];
  assert.deepEqual(references('Agent', attributes), [{attribute: 'profile_name', type: 'profile', name: 'MY_PROFILE'}]);
  assert.equal(references('Supervisor Agent', attributes)[0].name, 'MY_PROFILE');
  assert.deepEqual(references('Tool', attributes), [{attribute: 'function', type: 'routine', name: 'APP.FETCH_DATA'}]);
  assert.deepEqual(references('Task', attributes), []);
});

test('SQL tool profile comes only from declared tool_params', () => {
  assert.deepEqual(references('Tool', [{name: 'TOOL_TYPE', value: '"SQL"'},
    {name: 'tool_params', value: '{"profile_name":"MY_SQL_PROFILE","other":"x"}'}]),
  [{attribute: 'tool_params', type: 'profile', name: 'MY_SQL_PROFILE'}]);
  assert.deepEqual(references('Tool', [{name: 'tool_type', value: 'SQL'}, {name: 'tool_params', value: 'invalid'}]), []);
  assert.deepEqual(references('Tool', [{name: 'tool_type', value: 'SQL'}, {name: 'tool_params', value: 'null'}]), []);
  assert.deepEqual(references('Tool', [{name: 'tool_type', value: 'RAG'}, {name: 'tool_params', value: '{"profile_name":"P"}'}]), []);
});

test('missing and scalar JSON attributes do not invent targets', () => {
  assert.equal(scalar('"APP.FN"'), 'APP.FN');
  assert.equal(scalar('APP.FN'), 'APP.FN');
  assert.deepEqual(references('Tool', []), []);
  assert.deepEqual(references('Agent', [{name:'profile_name', value:''}]), []);
});
