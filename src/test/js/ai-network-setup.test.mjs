import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {networkCanApply} from '../../main/resources/static/js/ai-network-setup.mjs';
test('outbound access needs preview and consent and cannot double submit',()=>{
 assert.equal(networkCanApply({token:'t'},true,false),true);
 for(const args of [[null,true,false],[{},false,false],[{},true,true]])assert.equal(networkCanApply(...args),false);
});
test('network form carries CSRF and is rendered only for ADMIN',()=>{
 const html=fs.readFileSync(new URL('../../main/resources/templates/account-setup.html',import.meta.url),'utf8');
 assert.match(html,/<section th:if="\$\{accountAdmin\}" id="ai-network"/);
 assert.match(html,/data-an-csrf th:value="\$\{_csrf.token\}" th:attr="data-csrf-header=\$\{_csrf.headerName\}"/);
});
