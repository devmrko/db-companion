import {t} from './i18n.mjs';
const tr=(key,...args)=>t('ords.test.'+key,key,...args);
const node=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
export const readMethod=method=>['GET','HEAD','OPTIONS'].includes(method);
export function pairs(text,separator){
  return text.split(/\r?\n/).filter(line=>line.trim()).map(line=>{const split=line.indexOf(separator);if(split<1)throw new Error(tr('pairsInvalid'));return {name:line.slice(0,split).trim(),value:line.slice(split+1).trim()};});
}
export function requestUrl(prepared,parameters,query){
  const path=prepared.path.split('/').map(part=>encodeURIComponent(part.startsWith(':')?(parameters[part.slice(1)]??''):part)).join('/');
  const suffix=new URLSearchParams(query.map(p=>[p.name,p.value])).toString();return prepared.baseUrl+path+(suffix?'?'+suffix:'');
}
const quote=value=>"'"+value.replaceAll("'","'\\''")+"'";
// Deliberately export no user-entered values, not even custom headers/query/body.
export function curlTemplate(prepared,input){
  const parameters=Object.fromEntries(prepared.parameters.map(name=>[name,'PATH_VALUE']));
  const query=input.query.map((_p,i)=>({name:'QUERY_NAME_'+(i+1),value:'QUERY_VALUE'}));
  let curl='curl --request '+prepared.method+' --url '+quote(requestUrl(prepared,parameters,query));
  if(input.headers.length)curl+=' \\\n  --header '+quote('HEADER_NAME: HEADER_VALUE');
  if(input.body)curl+=' \\\n  --header '+quote('Content-Type: application/json')+' \\\n  --data-raw '+quote('REQUEST_BODY');
  return curl;
}
export function formatBody(value,pretty){if(pretty)try{return JSON.stringify(JSON.parse(value),null,2);}catch{}return value;}
export function mountOrdsApiTest(root,api,onBusy){
  const get=name=>root.querySelector('[data-ords-test-'+name+']');let prepared=null,selection=null,used=false,busy=false,generation=0,lastResult=null,timer=null;
  const status=text=>{get('message').textContent=text;};
  function controls(active){busy=active;get('fields').disabled=active;get('close').disabled=active;get('run').disabled=active||!prepared;get('curl').disabled=active||!prepared;root.setAttribute('aria-busy',String(active));onBusy(active);}
  function authFields(){const auth=get('auth').value;get('username-field').hidden=auth!=='BASIC';get('secret-field').hidden=auth==='NONE';get('secret-label').textContent=tr(auth==='BASIC'?'password':'token');get('username').value='';get('secret').value='';}
  function payload(){return {token:prepared?.token,parameters:Object.fromEntries([...get('parameters').querySelectorAll('input')].map(n=>[n.name,n.value])),query:pairs(get('query').value,'='),headers:pairs(get('headers').value,':'),body:readMethod(prepared.method)?'':get('body').value,auth:{type:get('auth').value,username:get('username').value,secret:get('secret').value},timeoutSeconds:Number(get('timeout').value),confirmed:false};}
  function renderUrl(){if(!prepared)return;try{get('url').value=requestUrl(prepared,payload().parameters,payload().query);}catch{get('url').value=prepared.baseUrl+prepared.path;}}
  function resultBody(){if(lastResult)get('response-body').textContent=formatBody(lastResult.body,get('pretty').checked);}
  async function prepare(){const current=generation;const data=await api('/test/prepare',selection);if(current!==generation)return false;prepared=data;used=false;get('method').textContent=data.method;get('base').value=data.baseUrl;renderUrl();return true;}
  async function open(target){
    if(busy)return;close();selection=target;root.hidden=false;controls(true);status(tr('preparing'));const current=generation;
    try{if(!await prepare())return;get('parameters').replaceChildren();for(const name of prepared.parameters){const label=node('label'),input=node('input');input.name=name;input.required=true;input.maxLength=2000;input.className='form-control';input.autocomplete='off';label.append(node('span',name),input);get('parameters').append(label);}get('path-section').hidden=!prepared.parameters.length;get('body-field').hidden=readMethod(prepared.method);get('timeout').value=prepared.timeoutSeconds;get('heading').textContent=tr('title')+' · '+target.method+' · '+target.module+' / '+target.pattern;renderUrl();status('');}
    catch(ex){if(current===generation)status(ex.message);}finally{if(current===generation)controls(false);}
    root.scrollIntoView({block:'nearest',behavior:'smooth'});
  }
  function close(){if(busy)return;++generation;prepared=null;selection=null;lastResult=null;used=false;get('form').reset();get('parameters').replaceChildren();get('base').value='';get('url').value='';get('response-body').textContent='';get('response-headers').textContent='';get('result').hidden=true;root.hidden=true;status('');authFields();}
  get('form').addEventListener('submit',async event=>{
    event.preventDefault();if(busy||!prepared)return;let input;
    try{input=payload();}catch(ex){status(ex.message);return;}
    if(!readMethod(prepared.method)&&!window.confirm(tr('writeConfirm',prepared.method,get('url').value)))return;
    input.confirmed=!readMethod(prepared.method);controls(true);status(tr('running'));get('result').hidden=true;lastResult=null;
    const started=performance.now();timer=setInterval(()=>status(tr('runningTime',((performance.now()-started)/1000).toFixed(1))),100);
    try{
      if(used){await prepare();input.token=prepared.token;}used=true;
      const response=await api('/test/run',input);lastResult=response;get('result').hidden=false;
      get('response-status').textContent=response.status?'HTTP '+response.status:tr('noResponse');get('response-time').textContent=(response.elapsedMillis/1000).toFixed(2)+' s';
      get('response-headers').textContent=JSON.stringify(response.headers,null,2);resultBody();
      status(response.error?t('ords.'+response.error,response.error):response.truncated?tr('truncated'):response.binary?tr('binary'):tr('finished'));
    }catch(ex){status(ex.message+' '+tr('noRetry'));}finally{clearInterval(timer);timer=null;controls(false);}
  });
  get('auth').addEventListener('change',authFields);get('pretty').addEventListener('change',resultBody);
  get('form').addEventListener('input',()=>{if(!busy){get('result').hidden=true;lastResult=null;renderUrl();}});
  get('curl').addEventListener('click',async()=>{if(busy||!prepared)return;try{await navigator.clipboard.writeText(curlTemplate(prepared,payload()));status(tr('copied'));}catch{status(tr('copyFailed'));}});
  get('close').addEventListener('click',close);authFields();return {open,close};
}
