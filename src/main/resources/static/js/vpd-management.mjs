import {t} from './i18n.mjs';
import {mountVpdFunctionSource,policyFunctionTarget} from './vpd-function-source.mjs';

const label=key=>t(key,key);
export function filterTargets(targets,{kind='ALL',access='ALL',search=''}={}){
  const query=search.trim().toLocaleUpperCase();
  return targets.filter(target=>{
    if(kind!=='ALL'&&target.type!==kind)return false;
    if(query&&!target.name.toLocaleUpperCase().includes(query))return false;
    if(access==='ALL')return true;
    if(access==='OWNED')return target.owned;
    if(access==='GRANTED')return target.grantSources.length>0;
    if(!['SELECT','INSERT','UPDATE','DELETE'].includes(access))return false;
    return target.owned||target.privileges.includes(access)||(access==='SELECT'&&target.privileges.includes('READ'));
  });
}
export class Approval {
  version=0; preview=null;
  invalidate(){this.version++;this.preview=null;return this.version;}
  accept(version,preview){if(version!==this.version)return false;this.preview=preview;return true;}
  allowed(target,consent,gap,now=Date.now()){
    return !!this.preview&&this.preview.target===target&&consent&&(!this.preview.gap||gap)&&now<Date.parse(this.preview.expiresAt);
  }
  consume(target,consent,gap){
    if(!this.allowed(target,consent,gap))throw new Error(label('vpd.confirmRequired'));
    const request={token:this.preview.token,target,confirmed:consent,gapConfirmed:gap};this.invalidate();return request;
  }
}
export function catalogMessage(catalog){
  if(catalog.status==='ACCESS_REQUIRED')return label('vpd.accessRequired');
  if(catalog.status!=='AVAILABLE')return label('vpd.readError');
  return catalog.reason?label(catalog.reason):(catalog.policies.length?'':label('vpd.empty'));
}
export const policyStatements=properties=>[['SEL','SELECT'],['INS','INSERT'],['UPD','UPDATE'],['DEL','DELETE'],['IDX','INDEX']].filter(([key])=>properties[key]==='YES').map(([,name])=>name);
const element=(tag,text,cls)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;};
export function mountVpd(root){
  const get=name=>root.querySelector(`[data-vpd-${name}]`),approval=new Approval();
  const sourceViewer=mountVpdFunctionSource(get('source-dialog'),{schema:root.dataset.schema,url:root.dataset.sourceUrl});
  let catalog=null,selected=null,listVersion=0,functionVersion=0,busy=false,locked=false,preparing=false;
  let targets=[],objectVersion=0,objectsLoading=false;
  const invalidate=()=>{approval.invalidate();get('run').disabled=true;};
  function setOptions(select,values,current=[]){select.replaceChildren();for(const value of values){const option=element('option',value);option.value=value;option.selected=current.includes(value);select.append(option);}}
  async function api(path,body,params={}){
    const url=new URL(root.dataset.base+path,location.origin);url.searchParams.set('schema',root.dataset.schema);
    for(const [key,value]of Object.entries(params))url.searchParams.set(key,value);
    const csrf=get('csrf'),headers={Accept:'application/json'};
    if(body){headers['Content-Type']='application/json';headers[csrf.dataset.csrfHeader]=csrf.value;}
    const response=await fetch(url,{method:body?'POST':'GET',headers,body:body?JSON.stringify(body):undefined,cache:'no-store'});
    if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1','Login required'));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(label('vpd.readError'));
    const data=await response.json();if(!response.ok)throw new Error(data.error||label('vpd.readError'));return data;
  }
  function writable(){return !!catalog?.writable&&!locked;}
  function controls(){
    const allowed=writable()&&(!selected||selected.editable)&&!busy&&!preparing&&!objectsLoading;
    get('fields').disabled=!allowed;get('preview').disabled=!allowed;get('delete').disabled=!allowed;get('toggle').disabled=!allowed;
    get('new').disabled=!writable()||busy||preparing||objectsLoading;
    for(const name of ['table','refresh','kind','access','search'])get(name).disabled=busy||preparing||objectsLoading;
  }
  function draft(){return {schema:root.dataset.schema,table:get('table').value,policy:get('policy').value,
    functionSchema:get('function-schema').value,function:get('function').value,
    statements:[...root.querySelectorAll('[data-vpd-statement]:checked')].map(n=>n.value),
    updateCheck:get('update-check').checked,enabled:get('enabled').checked,policyType:get('type').value,
    longPredicate:get('long').checked,columns:[...get('columns').selectedOptions].map(n=>n.value),allRows:get('all-rows').checked};}
  async function functions(current=''){
    const version=++functionVersion,owner=get('function-schema').value;invalidate();get('function').replaceChildren();get('function-status').textContent=label('vpd.loading');
    try{const values=await api('/functions',null,{owner});if(version!==functionVersion||owner!==get('function-schema').value)return;
      setOptions(get('function'),values.map(v=>v.name),[current]);get('function-status').textContent=values.length?'':label('vpd.noFunctions');
    }catch(ex){if(version===functionVersion)get('function-status').textContent=ex.message;}
  }
  function edit(policy=null){
    invalidate();++functionVersion;selected=policy;get('editor').hidden=false;get('form-status').textContent='';get('form').reset();
    const d=policy?.definition;get('heading').textContent=policy?policy.name:label('vpd.add');
    get('reason').textContent=policy?.reason?label(policy.reason):label('vpd.supported');
    get('policy-function').replaceChildren();get('policy-function').hidden=!policy;
    if(policy){get('policy-function').append(element('span',label('vpd.functionSource')+' · '),functionLink(policy));}
    get('properties').hidden=!policy;get('properties').open=!!policy&&!policy.editable;get('form').hidden=!!policy&&!policy.editable;get('raw').textContent=policy?JSON.stringify({policy:policy.properties,sec_relevant_cols:policy.relevant,namespace_attributes:policy.attributes},null,2):'';
    get('policy').value=d?.policy??policy?.name??'';get('policy').readOnly=!!policy;get('function-schema').value=d?.functionSchema??root.dataset.schema;
    setOptions(get('function'),d?[d.function]:[],d?[d.function]:[]);get('function-status').textContent='';
    get('type').value=d?.policyType??'DYNAMIC';get('update-check').checked=d?.updateCheck??false;get('enabled').checked=d?.enabled??true;
    get('long').checked=d?.longPredicate??false;get('all-rows').checked=d?.allRows??false;setOptions(get('columns'),catalog?.columns??[],d?.columns??[]);
    root.querySelectorAll('[data-vpd-statement]').forEach(n=>n.checked=(d?.statements??['SELECT']).includes(n.value));
    get('toggle').hidden=!policy;get('delete').hidden=!policy;get('toggle').textContent=label(d?.enabled?'vpd.disable':'vpd.enable');controls();if(!policy)functions();
  }
  function functionLink(policy){
    const target=policyFunctionTarget(policy);
    if(!target)return element('span','—');
    const button=element('button',target.label,'app-inline-link');button.type='button';button.dataset.vpdFunctionSource='';button.title=label('vpd.functionSource');
    button.addEventListener('click',()=>{if(!busy&&!preparing)sourceViewer.open(policy);});return button;
  }
  function render(){
    const host=get('list');host.replaceChildren();get('status').textContent=catalogMessage(catalog);
    get('status').className=catalog.status==='AVAILABLE'&&!catalog.reason&&!catalog.policies.length?'workbench-empty':'app-filter-message';
    controls();if(catalog.status!=='AVAILABLE'||!catalog.policies.length)return;
    const table=element('table',undefined,'workbench-table'),head=element('thead'),tr=element('tr');
    table.setAttribute('aria-label',label('workbench.vpd.policies'));
    for(const key of ['vpd.policy','workbench.vpd.group','workbench.vpd.function','workbench.vpd.statements','workbench.vpd.status','workbench.vpd.type']){const th=element('th',label(key));th.scope='col';tr.append(th);}
    head.append(tr);table.append(head);const body=element('tbody');
    for(const policy of catalog.policies){const row=element('tr'),name=element('td'),button=element('button',policy.name,'app-inline-link');button.type='button';button.addEventListener('click',()=>{if(!busy&&!preparing)edit(policy);});name.append(button);row.append(name);
      const p=policy.properties;row.append(element('td',policy.group));const routine=element('td');routine.append(functionLink(policy));row.append(routine);
      const statements=element('td'),tags=element('div',undefined,'app-vpd-permissions');
      for(const statement of policyStatements(p))tags.append(element('span',statement,'workbench-badge'));
      if(!tags.childElementCount)tags.textContent='—';statements.append(tags);
      const state=element('td');state.append(element('span',p.ENABLE==='YES'?label('workbench.vpd.enabled'):p.ENABLE==='NO'?label('workbench.vpd.disabled'):(p.ENABLE||'—'),'workbench-badge'+(p.ENABLE==='YES'?' is-reviewed':'')));
      row.append(statements,state,element('td',p.POLICY_TYPE));body.append(row);
    }table.append(body);host.append(table);
  }
  async function load(){
    sourceViewer.close();
    const version=++listVersion;invalidate();++functionVersion;catalog=null;selected=null;get('editor').hidden=true;get('list').replaceChildren();get('status').className='app-filter-message';get('status').textContent=label('vpd.loading');controls();
    const target=targets.find(item=>item.name===get('table').value);
    get('object-access').textContent=target?[target.type,target.owned?label('vpd.accessOwned'):'',target.privileges.join(', '),target.grantSources.map(source=>label('vpd.grant'+source)).join(' / ')].filter(Boolean).join(' · '):'';
    if(!get('table').value){get('status').textContent=label(targets.length?'vpd.noMatches':'vpd.noTables');return;}
    try{const data=await api('/list',null,{table:get('table').value});if(version!==listVersion)return;catalog=data;render();}
    catch(ex){if(version===listVersion)get('status').textContent=ex.message;}
  }
  async function filter(){
    const current=get('table').value,visible=filterTargets(targets,{kind:get('kind').value,access:get('access').value,search:get('search').value});
    get('table').replaceChildren();
    for(const target of visible){const option=element('option',`[${target.type}] ${target.name}`);option.value=target.name;option.selected=target.name===current;get('table').append(option);}
    get('object-count').textContent=t('vpd.objectCount','{0} / {1}',visible.length,targets.length);await load();
  }
  async function loadObjects(){
    const version=++objectVersion;objectsLoading=true;++listVersion;++functionVersion;invalidate();catalog=null;selected=null;targets=[];
    get('editor').hidden=true;get('list').replaceChildren();get('table').replaceChildren();get('object-access').textContent='';get('object-count').textContent='';get('status').className='app-filter-message';get('status').textContent=label('vpd.loading');controls();
    try{const data=await api('/objects');if(version!==objectVersion)return;targets=data;objectsLoading=false;await filter();}
    catch(ex){if(version===objectVersion)get('status').textContent=ex.message;}
    finally{if(version===objectVersion){objectsLoading=false;controls();}}
  }
  function approvalControls(){get('run').disabled=busy||locked||!approval.allowed(get('confirm-target').value,get('consent').checked,get('gap-consent').checked);}
  async function preview(action){
    if(preparing||busy||!writable())return;
    const version=approval.invalidate();preparing=true;controls();get('form-status').textContent=label('vpd.loading');
    try{
      const data=await api('/preview',{action,draft:draft(),fingerprint:catalog.fingerprint});if(!approval.accept(version,data))return;
      get('target').textContent=`${data.action} · ${data.target} · ${data.expiresAt}`;get('sql').textContent=data.sql;get('recovery').textContent=data.recoverySql;
      get('confirm-target').value='';get('consent').checked=false;get('gap-consent').checked=false;get('gap-warning').hidden=!data.gap;get('gap-label').hidden=!data.gap;get('result').textContent='';get('form-status').textContent='';get('dialog').showModal();approvalControls();
    }catch(ex){get('form-status').textContent=ex.message;}finally{preparing=false;controls();}
  }
  async function execute(){
    if(busy||locked)return;let body;
    try{body=approval.consume(get('confirm-target').value,get('consent').checked,get('gap-consent').checked);}catch(ex){get('result').textContent=ex.message;return;}
    busy=true;get('close').disabled=true;controls();approvalControls();get('result').textContent=label('vpd.loading');
    try{const result=await api('/execute',body);get('result').textContent=label(result.message);get('recovery').textContent=result.recoverySql;locked=result.status!=='VERIFIED';}
    catch(ex){locked=true;get('result').textContent=`${ex.message} ${label('vpd.uncertain')}`;}
    finally{busy=false;get('close').disabled=false;await load();controls();approvalControls();}
  }
  get('table').addEventListener('change',load);get('refresh').addEventListener('click',loadObjects);get('new').addEventListener('click',()=>edit());
  for(const name of ['kind','access'])get(name).addEventListener('change',filter);get('search').addEventListener('input',filter);
  get('form').addEventListener('input',invalidate);get('form').addEventListener('change',invalidate);
  get('function-schema').addEventListener('input',()=>{++functionVersion;get('function').replaceChildren();});get('functions').addEventListener('click',()=>functions());
  get('form').addEventListener('submit',event=>{event.preventDefault();preview(selected?'REPLACE':'ADD');});get('delete').addEventListener('click',()=>preview('DELETE'));get('toggle').addEventListener('click',()=>preview(selected.definition.enabled?'DISABLE':'ENABLE'));
  for(const name of ['confirm-target','consent','gap-consent'])get(name).addEventListener('input',approvalControls);
  get('run').addEventListener('click',execute);get('close').addEventListener('click',()=>get('dialog').close());get('dialog').addEventListener('cancel',event=>{if(busy)event.preventDefault();});get('dialog').addEventListener('close',invalidate);
  loadObjects();
}
if(typeof document!=='undefined')document.querySelectorAll('[data-vpd]').forEach(mountVpd);
