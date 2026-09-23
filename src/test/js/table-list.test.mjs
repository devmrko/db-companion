import test from 'node:test';
import assert from 'node:assert/strict';
import { pageOf, profileTables } from '../../main/resources/static/js/table-list.mjs';

test('profile intersects schema and table names before search and pagination', () => {
  const items = [{name: 'ORDERS'}, {name: 'CUSTOMERS'}, {name: 'MixedCase'}];
  assert.deepEqual(profileTables(items, 'APP', '[{"owner":"app","name":"orders"},{"owner":"OTHER","name":"CUSTOMERS"}]'), [items[0]]);
  assert.deepEqual(profileTables(items, 'APP', '[{"owner":"APP"}]'), items);
  assert.deepEqual(profileTables(items, 'APP', JSON.stringify([{owner:'APP',name:'"MixedCase"'}])), [items[2]]);
  assert.deepEqual(profileTables(items, 'APP', '[]'), []);
  assert.equal(pageOf(profileTables(items, 'APP', '[{"owner":"APP"}]'), 'customer').total, 1);
  assert.throws(() => profileTables(items, 'APP', null));
  assert.throws(() => profileTables(items, 'APP', '{bad'));
  assert.throws(() => profileTables(items, 'APP', '[{"name":"ORDERS"}]'));
});

test('ten entries per page, remainder, bounds, and stable input', () => {
  const items = Array.from({length: 23}, (_, i) => ({name: `TABLE_${i}`, description: null}));
  assert.equal(pageOf(items).items.length, 10);
  assert.equal(pageOf(items, '', 2).items[0], items[10]);
  const last = pageOf(items, '', 99);
  assert.deepEqual([last.page, last.pages, last.from, last.to, last.items.length], [3, 3, 21, 23, 3]);
  assert.equal(pageOf(items, '', 0).page, 1);
  assert.equal(items.length, 23);
});

test('contains on full name or description, case insensitive and literal', () => {
  const items = [{name: 'ORDERS', description: '아주 긴 설명의 뒷부분에 있는 결제내역'},
    {name: 'SALES_2026', description: null}, {name: 'OTHER', description: '100% [value]'}];
  assert.equal(pageOf(items, ' rDe ').items[0], items[0]);
  assert.equal(pageOf(items, '결제내역').items[0], items[0]);
  assert.equal(pageOf(items, '2026').items[0], items[1]);
  assert.equal(pageOf(items, '[value]').items[0], items[2]);
  assert.equal(pageOf(items, '.*').total, 0);
  assert.equal(pageOf(items, '').total, 3);
});

test('empty results and exact page boundary', () => {
  const empty = pageOf([], '', 2);
  assert.deepEqual([empty.total, empty.pages, empty.from, empty.to], [0, 0, 0, 0]);
  const items = Array.from({length: 20}, () => ({name: 'X'}));
  assert.equal(pageOf(items, '', 2).items.length, 10);
  assert.equal(pageOf(items, 'missing', 2).page, 1);
});
