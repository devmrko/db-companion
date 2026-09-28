import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {profileField} from './profile-fields.mjs';
const tr=(key,...args)=>t('creation.'+key,key,...args);
const el=(tag,text)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;return n;};
export const defaults=kind=>({PROFILE:{provider:'oci',credential_name:'',model:'',region:'',oci_compartment_id:'',comments:true,annotations:false,conversation:false,object_list:[]},TEAM:{agents:[{name:'',task:''}],process:'sequential'},AGENT:{profile_name:'',role:''},TASK:{instruction:'',tools:[]},TOOL:{tool_type:'SQL',tool_params:{profile_name:''}}}[kind]);
export function parseAttributes(text){
  const data=JSON.parse(text);if(!data||Array.isArray(data)||typeof data!=='object')throw new Error(tr('attributesInvalid'));
  const unquoted=text.replace(/"(?:\\.|[^"\\])*"/g,'""');
  for(const match of unquoted.matchAll(/-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g)){
    const number=Number(match[0]),significant=match[0].split(/[eE]/)[0].replace(/[-.]/g,'').replace(/^0+/,'').replace(/0+$/,'');
    if(!Number.isFinite(number)||Number.isInteger(number)&&!Number.isSafeInteger(number)||significant.length>15)throw new Error(tr('preciseJson'));
  }return data;
}
export const needsConsent=preview=>preview?.input?.kind==='PROFILE'||!!preview?.feedback?.length;
export const fieldValue=(current,text,checked)=>typeof current==='boolean'?checked:typeof current==='object'||typeof current==='number'?parseAttributes('{"value":'+text+'}').value:text;
export const canCreate=(preview,confirmed,consent,busy,attempted)=>!!preview&&confirmed&&(!needsConsent(preview)||consent)&&!busy&&!attempted;
export function resultUrl(kind,schema,name){return kind==='PROFILE'?'/ai-profiles/detail?'+new URLSearchParams({profile:name}):kind==='TEAM'?'/ai-agents/team?'+new URLSearchParams({schema,team:name}):'/ai-agents/object?'+new URLSearchParams({schema,kind,name});}
export async function copySequence(preview,start,send,onProgress,stopped){
  let result=start;while(result.verified&&!result.done&&!stopped()){
    result=await send({token:preview.token,index:result.copied,consent:true});onProgress(result);
  }return result;
}
if(typeof document!=='undefined')document.querySelectorAll('[data-ai-creation]').forEach(root=>{
  const get=k=>root.querySelector(`[data-ac-${k}]`),post=(path,data)=>assistantApi(root.dataset.base+'/'+path,assistantPost(get('csrf'),data));
  let form=null,preview=null,busy=false,attempted=false,stopped=false,kind='',source='',config={};
  const message=(text,error=false)=>{get('message').textContent=text;get('message').className=error?'app-alert is-error':'app-filter-message';};
  const controls=()=>{get('fields').disabled=busy||!form?.historyReady||attempted;get('install').disabled=busy;get('close').disabled=busy;get('confirm').disabled=busy||attempted;get('consent').disabled=busy||attempted;get('create').disabled=!canCreate(preview,get('confirm').checked,get('consent').checked,busy,attempted);root.setAttribute('aria-busy',String(busy));};
  const invalidate=()=>{preview=null;get('review').hidden=true;get('confirm').checked=false;get('consent').checked=false;controls();};
  const sync=()=>{get('attributes').value=JSON.stringify(config,null,2);invalidate();};
  function fields(){get('typed').replaceChildren();
    for(const [name,value]of Object.entries(config)){
      const label=el('label'),title=el('span',name);label.append(title);const spec=profileField(name);let input;
      const choices=name==='credential_name'?form.credentials:form.references[name];
      if(typeof value==='boolean'){input=el('input');input.type='checkbox';input.checked=value;input.setAttribute('role','switch');}
      else if(choices||name==='provider'||name==='tool_type'){
        input=el('select');const list=choices??(name==='provider'?spec.choices:['SQL','RAG','WEBSEARCH','NOTIFICATION']);for(const v of new Set(['',...list,String(value)])){const o=el('option',v||'—');o.value=v;input.append(o);}input.value=value;input.className='form-select';
      }else{input=el(typeof value==='object'||String(value).length>100||['role','instruction','additional_instructions'].includes(name)?'textarea':'input');input.className='form-control';if(input.tagName==='TEXTAREA')input.rows=4;input.value=typeof value==='object'?JSON.stringify(value,null,2):String(value);}
      if(kind==='PROFILE'){const help=el('details');help.className='app-creation-inline-help';help.append(el('summary','?'),el('p',spec.help));label.append(help);}
      input.setAttribute('aria-label',name);label.append(input);get('typed').append(label);
      input.addEventListener('input',()=>{invalidate();try{config[name]=fieldValue(value,input.value,input.checked);get('attributes').value=JSON.stringify(config,null,2);input.setCustomValidity('');}catch(ex){input.setCustomValidity(ex.message||tr('attributesInvalid'));}});
    }
  }
  async function load(){form=await assistantApi(root.dataset.base+'/form?'+new URLSearchParams({schema:root.dataset.schema,kind,source}));get('description').value=form.description;get('attributes').value=source?form.attributes:JSON.stringify(defaults(kind),null,2);get('references').textContent=JSON.stringify(form.references,null,2);get('setup').hidden=form.historyReady;
    try{config=parseAttributes(get('attributes').value);fields();}catch(ex){get('typed').replaceChildren(el('p',ex.message));get('json-panel').open=true;}
  }
  document.querySelectorAll('[data-ai-create]').forEach(button=>button.addEventListener('click',async()=>{
    if(busy)return;kind=button.dataset.aiCreate;source=button.dataset.source??'';form=null;attempted=false;stopped=false;preview=null;get('form').reset();get('review').hidden=true;get('link').hidden=true;get('copy-label').hidden=kind!=='PROFILE'||!source;get('copy').checked=kind==='PROFILE'&&!!source;get('name').value='';get('status').value='DISABLED';get('target').textContent=`${root.dataset.schema} · ${kind}${source?' · '+source+' →':''}`;
    const help=get('sql-help');
    if(help){help.dataset.sqlHelpFor=kind==='PROFILE'?'profiles':'agents';help.dataset.sqlHelpOperation=kind==='PROFILE'?'create':'create-'+kind.toLowerCase();}
    root.showModal();busy=true;controls();message(tr('loading'));try{await load();message('');}catch(ex){message(ex.message,true);}finally{busy=false;controls();}
  }));
  get('install').addEventListener('click',async()=>{if(busy)return;busy=true;controls();try{await post('install',{schema:root.dataset.schema});await load();message(tr('historyReady'));}catch(ex){message(ex.message,true);}finally{busy=false;controls();}});
  get('fields').addEventListener('input',invalidate);
  get('attributes').addEventListener('input',()=>{invalidate();get('typed').replaceChildren();});
  get('apply-json').addEventListener('click',()=>{try{config=parseAttributes(get('attributes').value);fields();sync();message('');}catch(ex){message(ex.message,true);}});
  get('form').addEventListener('submit',async event=>{event.preventDefault();if(busy||attempted)return;busy=true;controls();message(tr('loading'));try{
    const attributes=get('attributes').value;
    preview=await post('preview',{schema:root.dataset.schema,kind,source,sourceVersion:form.sourceVersion,name:get('name').value,description:get('description').value,status:get('status').value,attributes,copyFeedback:get('copy').checked});
    get('payload').textContent=JSON.stringify({...preview.input,sourceVersion:undefined,attributes:undefined},null,2)+'\n'+preview.input.attributes;
    get('feedback-detail').hidden=!preview.feedback.length;get('feedback-count').textContent=tr('feedbackCount',preview.feedback.length);get('feedback').textContent=JSON.stringify(preview.feedback.map(r=>({question:r.question,attributes:JSON.parse(r.attributes)})),null,2);get('consent-label').hidden=!needsConsent(preview);get('consent').checked=false;get('confirm').checked=false;get('review').hidden=false;message('');
  }catch(ex){preview=null;get('review').hidden=true;message(ex.message,true);}finally{busy=false;controls();}});
  function progress(result){
    const detail=result.detail.split('\n').map(v=>v.startsWith('creation.')?tr(v.slice(9)):v).join('\n');
    message(`${result.stage} · ${tr('progress',result.copied,result.total)}${detail?'\n'+detail:''}`,!result.verified);
    get('link').href=resultUrl(kind,root.dataset.schema,result.name);get('link').hidden=false;
  }
  get('create').addEventListener('click',async()=>{
    if(!canCreate(preview,get('confirm').checked,get('consent').checked,busy,attempted))return;busy=true;attempted=true;stopped=false;controls();get('stop').disabled=false;get('stop').hidden=!preview.feedback.length;
    try{const first=await post('create',{token:preview.token,confirmed:true,consent:get('consent').checked});progress(first);
      const result=await copySequence(preview,first,data=>post('copy',data),progress,()=>stopped);
      if(result.done)message(tr('completed',result.name,result.copied));else if(stopped&&result.verified)message(tr('stopped',result.copied,result.total));
    }catch(ex){message(tr('uncertain')+'\n'+ex.message,true);}finally{busy=false;get('stop').hidden=true;controls();}
  });
  get('stop').addEventListener('click',()=>{stopped=true;get('stop').disabled=true;});
  for(const key of ['confirm','consent'])get(key).addEventListener('change',controls);
  get('close').addEventListener('click',()=>{if(!busy)root.close();});root.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
  window.addEventListener('beforeunload',e=>{if(root.open&&(busy||!attempted)){e.preventDefault();e.returnValue='';}});
});
