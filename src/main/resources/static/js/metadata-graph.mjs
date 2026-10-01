import {t} from './i18n.mjs';
const label=(key,...args)=>t('ontology.mg.'+key,key,...args);
const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;};
export function metadataCounts(plan){return {nodes:plan.objects+plan.columns,edges:plan.columns+plan.relations+plan.mappings};}
export function metadataGraphPanel(host,{schema,post,lock,status,isBusy}){
  let preview=null;const help=el('button','SQL ?','btn app-btn app-btn-secondary');help.type='button';help.dataset.sqlHelpFor='metadata-graph';host.append(help,el('p',label('help'),'app-filter-message'));
  const field=el('label',undefined,'app-ontology-field'),name=el('input',undefined,'form-control');name.value='DBC_METADATA_GRAPH';name.maxLength=60;name.setAttribute('aria-label',label('name'));field.append(el('span',label('name')),name);
  const result=el('div'),button=(title,fn)=>{const e=el('button',title,'btn app-btn app-btn-secondary');e.type='button';e.addEventListener('click',fn);return e;};
  name.addEventListener('input',()=>{preview=null;result.replaceChildren();});
  const prepare=button(label('preview'),async()=>{
    if(isBusy())return;preview=null;result.replaceChildren();lock(true);status(label('working'));
    try{
      preview=await post('/metadata-graph/preview',{schema,name:name.value});const p=preview.plan,a=preview.access,n=metadataCounts(p);
      result.append(el('h3',label('summary',n.nodes,n.edges)),el('p',label('counts',p.objects,p.columns,p.relations,p.mappings,p.excluded.length)),el('p',label('snapshot'),'app-filter-message'));
      if(p.relations===0)result.append(el('p',label('noRelations'),'app-filter-message'));
      const states=[['supported',a.supported],['owner',a.owner],['graphPrivilege',a.graphPrivilege],['viewPrivilege',a.viewPrivilege],['namesFree',!a.collisions.length]];
      result.append(el('p',states.map(([key,ok])=>label(key)+': '+label(ok?'yes':'no')).join(' · ')));
      if(a.collisions.length)result.append(el('p',a.collisions.join(' · '),'app-alert is-error'));
      if(!a.graphPrivilege||!a.viewPrivilege)result.append(el('p',label('permissionHelp'),'app-filter-message'));
      if(p.excluded.length){const details=el('details');details.append(el('summary',label('excluded',p.excluded.length)));const list=el('ul',undefined,'app-pipeline-items');for(const item of p.excluded)list.append(el('li',item.name+' · '+t('ontology.pg.reason.'+item.reason,item.reason)));details.append(list);result.append(details);}
      const code=el('details');code.append(el('summary',label('sql')),el('pre',p.ddl.map(s=>s+';').join('\n\n'),'app-pipeline-code'));result.append(code);
      const consent=el('label',undefined,'app-pipeline-consent'),input=el('input');input.type='checkbox';consent.append(input,el('span',label('consent')));
      const create=button(label('create'),async()=>{
        if(isBusy()||!preview?.canCreate||!input.checked)return;const token=preview.token;preview=null;lock(true);status(label('creating'));
        try{const value=await post('/metadata-graph/create',{schema,token,confirmed:true});status(label('created',value.name));result.append(el('p',value.objects.join(' · ')),el('pre',value.query,'app-pipeline-code'));}
        catch(ex){status(ex.message,true);}finally{input.dataset.boundDisabled='true';create.dataset.boundDisabled='true';lock(false);}
      });
      create.dataset.boundDisabled='true';input.dataset.boundDisabled=String(!preview.canCreate);
      input.addEventListener('change',()=>{create.dataset.boundDisabled=String(!preview?.canCreate||!input.checked);create.disabled=create.dataset.boundDisabled==='true';});
      result.append(consent,create);status(label('verified'));
    }catch(ex){preview=null;status(ex.message,true);}finally{lock(false);}
  });host.append(field,prepare,result);
}
