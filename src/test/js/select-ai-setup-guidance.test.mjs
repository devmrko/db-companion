import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const guidance = JSON.parse(readFileSync('tools/i18n/feature-console-guidance.json', 'utf8'));

test('API signing-key guidance always uses the DBMS_CLOUD signing-key overload', () => {
  for (const sql of guidance['selectAiSetup.apiKeySql']) {
    for (const parameter of ['credential_name', 'user_ocid', 'tenancy_ocid', 'private_key', 'fingerprint']) {
      assert.match(sql, new RegExp(`${parameter}\\s*=>`));
    }
    assert.doesNotMatch(sql, /\b(?:username|password)\s*=>/i);
    assert.doesNotMatch(sql, /["'](?:user_ocid|tenancy_ocid|fingerprint)["']\s*:/i);
  }
});

test('profile guidance keeps create, session activation, and SHOWSQL as distinct steps', () => {
  for (const sql of guidance['selectAiSetup.profileSql']) {
    assert.match(sql, /DBMS_CLOUD_AI\.CREATE_PROFILE/i);
    assert.match(sql, /DBMS_CLOUD_AI\.SET_PROFILE/i);
    assert.match(sql, /SELECT\s+AI\s+SHOWSQL/i);
    assert.match(sql, /user_cloud_ai_profile_attributes/i);
  }
});
