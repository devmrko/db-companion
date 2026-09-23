import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {versionOf, compareVersions, auditControls} from '../../main/resources/static/js/profile-history.mjs';

const request = (value, quality = 'LITERAL', attribute = 'additional_instructions', profile = 'P') => ({
  profile, kind: 'REQUEST', payload: JSON.stringify({returnCode: 0, request: {value, quality, attribute}})
});
test('audit unknown and archive existence never imply OFF or collection permission', () => {
  assert.deepEqual(auditControls({installed: true, enabled: null, canManage: false, canCollect: false}),
    {unknown: true, checked: false, toggleDisabled: true, collectDisabled: true});
  assert.equal(auditControls(null).unknown, true);
  assert.equal(auditControls({installed: true, enabled: false, canManage: false}).collectDisabled, true);
  assert.equal(auditControls({installed: true, enabled: false, canManage: false, canCollect: true}).collectDisabled, false);
  assert.equal(auditControls({installed: false, enabled: true, canManage: true, canCollect: false}).toggleDisabled, false);
  const allowed = {installed: true, enabled: true, canManage: true, canCollect: true};
  assert.equal(auditControls(allowed).checked, true);
  assert.equal(auditControls(allowed, true).toggleDisabled, true);
  assert.equal(auditControls(allowed, true).collectDisabled, true);
});
test('unknown audit switch has a scoped neutral appearance instead of Bootstrap ON blue', () => {
  const css = readFileSync(new URL('../../main/resources/static/css/common.css', import.meta.url), 'utf8');
  const rule = css.match(/\[data-profile-history\] \[data-ph-toggle\]:indeterminate\s*\{([^}]+)\}/)?.[1];
  assert.ok(rule);
  assert.match(rule, /background-color: var\(--app-accent-soft\)/);
  assert.match(rule, /background-image: linear-gradient\(var\(--app-muted\), var\(--app-muted\)\)/);
  assert.match(rule, /background-position: center/);
});
test('requests explicitly remain unconfirmed, including zero return codes and matching bind lengths', () => {
  assert.match(versionOf(request('값')).notice, /적용 미확인/);
  assert.match(versionOf(request('일부', 'BIND_UNVERIFIED')).notice, /전체 여부 미확인/);
  assert.equal(versionOf(request(null, 'UNAVAILABLE')), null);
  assert.equal(versionOf({kind: 'UNCLASSIFIED', payload: '{}'}), null);
  assert.equal(versionOf({kind: 'REQUEST', payload: 'broken'}), null);
  assert.match(versionOf(request(null)).notice, /SQL NULL/);
  assert.match(versionOf(request('')).notice, /빈 문자열/);
  assert.notEqual(versionOf(request(null)).notice, versionOf(request('(SQL NULL)')).notice);
});
test('same profile and attribute may be compared across pages; other meanings cannot', () => {
  const result = compareVersions(request('이전 👩‍💻'), request('다음 👩‍💻'));
  assert.equal(result.parts.filter(p => p.kind !== 'added').map(p => p.text).join(''), '이전 👩‍💻');
  assert.throws(() => compareVersions(request('a'), request('b', 'LITERAL', 'comments')));
  assert.throws(() => compareVersions(request('a'), request('b', 'LITERAL', 'additional_instructions', 'OTHER')));
  assert.throws(() => compareVersions(request('a'), {profile: 'P', kind: 'SNAPSHOT', payload: '{"exists":true}'}));
});
test('snapshots preserve absence, null, and object state without inferring intermediate versions', () => {
  const absent = {profile: 'P', kind: 'SNAPSHOT', payload: '{"exists":false}'};
  const present = {profile: 'P', kind: 'SNAPSHOT', payload: '{"exists":true,"attributes":{"instruction":null}}'};
  assert.match(compareVersions(absent, present).left.text, /false/);
  assert.match(compareVersions(absent, present).right.text, /null/);
});
test('history module is lazy, uses CSRF and text rendering, and offers no SQL execution or restoration', () => {
  const js = readFileSync(new URL('../../main/resources/static/js/profile-history.mjs', import.meta.url), 'utf8');
  assert.ok(!js.includes('innerHTML'));
  assert.match(js, /csrf\.dataset\.csrfHeader/);
  assert.match(js, /response\.redirected/);
  assert.match(js, /confirm\(prompt\)/);
  assert.ok(!js.includes('restore'));
  assert.match(js, /textContent/);
  assert.match(js, /\{profile, scope: 'profile', page: targetPage\}/);
  assert.ok(!js.includes("find('filter')"));
  const html = readFileSync(new URL('../../main/resources/templates/fragments/profile-history.html', import.meta.url), 'utf8');
  assert.ok(!html.includes('수집 범위와 값의 의미'));
  assert.ok(!html.includes('data-ph-scope'));
  assert.ok(!html.includes('data-ph-profile>'));
});
