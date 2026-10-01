import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';
import {pairs,requestUrl,curlTemplate,formatBody,readMethod} from '../../main/resources/static/js/ords-api-test.mjs';
const prepared={method:'POST',baseUrl:'https://example.invalid/ords/',path:'app/demo/:id',parameters:['id']};
test('named parameters and query values are encoded; duplicate query names are preserved',()=>{
 assert.equal(requestUrl(prepared,{id:'a/b'},pairs('q=a&b\nq=한글','=')),'https://example.invalid/ords/app/demo/a%2Fb?q=a%26b&q=%ED%95%9C%EA%B8%80');
 assert.deepEqual(pairs('X-Url: https://example.invalid\n\n',':'),[{name:'X-Url',value:'https://example.invalid'}]);assert.throws(()=>pairs('invalid','='));
});
test('curl export contains no credentials or user-entered values',()=>{
 const curl=curlTemplate(prepared,{parameters:{id:'secret-path'},query:[{name:'api_key',value:'private-query'}],headers:[{name:'X-Key',value:'private-header'}],body:'{"secret":"private-body"}',auth:{type:'BASIC',username:'private-user',secret:'private-password'}});
 assert.match(curl,/curl --request POST/);assert.match(curl,/PATH_VALUE/);assert.match(curl,/QUERY_VALUE/);assert.match(curl,/REQUEST_BODY/);assert.doesNotMatch(curl,/private-|secret-path|Authorization|api_key/);
});
test('response formatting never executes HTML and malformed JSON stays raw',()=>{
 assert.equal(formatBody('{"a":1}',true),'{\n  "a": 1\n}');assert.equal(formatBody('<script>bad()</script>',true),'<script>bad()</script>');assert.equal(formatBody('{"a":1}',false),'{"a":1}');
 const js=fs.readFileSync('src/main/resources/static/js/ords-api-test.mjs','utf8');assert.doesNotMatch(js,/innerHTML|eval\(|insertAdjacentHTML|localStorage|sessionStorage/);assert.match(js,/clearInterval\(timer\)/);assert.match(js,/get\('form'\)\.reset\(\)/);assert.match(js,/window\.confirm/);assert.match(js,/used=true/);
 for(const method of ['POST','PUT','DELETE','PATCH'])assert.equal(readMethod(method),false);
});
test('API test UI includes fields, progress and scoped hidden behavior',()=>{
 const html=fs.readFileSync('src/main/resources/templates/ords.html','utf8'),css=fs.readFileSync('src/main/resources/static/css/common.css','utf8');
 for(const name of ['base','url','parameters','auth','query','headers','body','timeout','run','response-body','response-headers'])assert.ok(html.includes('data-ords-test-'+name));
 assert.match(html,/type="password"[^>]*data-ords-test-secret/);assert.match(css,/\.app-ords \[hidden\] \{ display: none !important/);assert.match(css,/prefers-reduced-motion/);
});
