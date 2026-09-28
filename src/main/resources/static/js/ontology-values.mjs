import {t} from './i18n.mjs';
const label=key=>t('ontology.values.'+key,key);
export const valueType=type=>/^(CHAR|NCHAR|VARCHAR2|NVARCHAR2)(\(|$)/i.test(type)?'TEXT':/^(NUMBER|FLOAT)(\(|$)/i.test(type)?'NUMBER':'';
export const aliases=value=>value.split('\n').map(v=>v.trim()).filter(Boolean);
export const eligibleColumns=(columns,meaning)=>columns.filter(c=>valueType(c.dataType)&&meaning.columns[c.name]?.sensitivity!=='SENSITIVE');
export const addColumnMapping=(meaning,column)=>addMapping(meaning,column.name,valueType(column.dataType));
export function addMapping(meaning,column,type,value='',name=''){
  const rows=meaning.valueMappings??=[];
  if(rows.length>=100)throw new Error(label('limit'));
  if(value&&rows.some(b=>b.column===column&&b.value===value))throw new Error(label('duplicate'));
  const binding={id:globalThis.crypto.randomUUID(),column,type,value,label:name,aliases:[],description:''};rows.push(binding);return binding;
}
const el=(tag,text,cls)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;};
export function valuesEditor(host,{entry,meaning,editable,lookup,run,changed}){
  const columns=eligibleColumns(entry.document.source.columns,meaning);
  const rows=meaning.valueMappings??=[];
  const help=el('details',undefined,'app-dds-help'),summary=el('summary','?'),helpBody=el('div');summary.setAttribute('aria-label',label('title'));helpBody.append(el('p',label('optionalHelp')),el('p',label('help')));help.append(summary,helpBody);host.append(help);
  function button(text,action){const b=el('button',text,'btn app-btn app-btn-quiet');b.type='button';b.disabled=!editable;b.dataset.boundDisabled=String(!editable);b.addEventListener('click',action);return b;}
  function field(text,input,parent){const f=el('label',undefined,'app-ontology-field');f.append(el('span',text),input);parent.append(f);return input;}
  function select(text,parent,current){const s=el('select',undefined,'form-select app-select');for(const c of columns)s.add(new Option(c.name,c.name));s.value=current??columns[0]?.name??'';s.disabled=!editable;s.dataset.boundDisabled=String(!editable);return field(text,s,parent);}
  function input(text,value,maximum,action,parent,multiline=false){const node=el(multiline?'textarea':'input',undefined,'form-control');node.value=value;node.maxLength=maximum;node.disabled=!editable;node.dataset.boundDisabled=String(!editable);if(multiline)node.rows=2;node.addEventListener('input',()=>{action(node.value);changed();});return field(text,node,parent);}
  const toolbar=el('div',undefined,'app-ontology-value-lookup'),code=select(label('codeColumn'),toolbar),name=select(label('labelColumn'),toolbar);
  const consentLabel=el('label',undefined,'app-ontology-consent'),consent=el('input');consent.type='checkbox';consent.disabled=!editable;consent.dataset.boundDisabled=String(!editable);consentLabel.append(consent,el('span',label('consent')));
  const candidates=el('div'),status=el('p',undefined,'app-filter-message');status.setAttribute('role','status');
  for(const control of [code,name])control.addEventListener('change',()=>{consent.checked=false;candidates.replaceChildren();status.textContent='';});
  toolbar.append(button(label('lookup'),()=>run(async()=>{
    if(!consent.checked)throw new Error(label('consent'));
    const result=await lookup({schema:entry.document.source.schema,table:entry.document.source.table,revision:entry.revision,codeColumn:code.value,labelColumn:name.value,confirmed:true});
    candidates.replaceChildren();status.textContent=t('ontology.values.preview','{0} rows; {1} excluded',result.scanned,result.skipped);
    const selectedColumn=code.value;
    for(const item of result.items){const line=el('div',undefined,'app-dds-toolbar');line.append(el('code',item.value),el('span',item.label),button(label('add'),()=>run(async()=>{addMapping(meaning,selectedColumn,result.type,item.value,item.label);changed();render();})));candidates.append(line);}
    if(!result.items.length)candidates.append(el('p',label('empty'),'app-empty'));
  })));
  if(editable)host.append(toolbar,consentLabel,status,candidates);
  const edits=el('div');host.append(edits);
  if(editable)host.append(button(label('manual'),()=>run(async()=>{if(!columns.length)throw new Error(label('invalid'));const c=columns.find(c=>c.name===code.value)??columns[0];addColumnMapping(meaning,c);changed();render();})));
  function render(){
    edits.replaceChildren();if(!rows.length){edits.append(el('p',label('empty'),'app-empty'));return;}
    for(const row of rows){
      const box=el('section',undefined,'app-ontology-value-row'),head=el('div',undefined,'app-dds-toolbar');head.append(el('strong',row.column+' · '+row.type));
      if(editable)head.append(button(label('remove'),()=>{rows.splice(rows.indexOf(row),1);changed();render();}));box.append(head);
      const fields=el('div',undefined,'app-ontology-value-fields');const column=select(label('codeColumn'),fields,row.column);
      // Historical/sensitive columns remain visible read-only, not silently replaced by another column.
      if(!columns.some(c=>c.name===row.column)){column.add(new Option(row.column,row.column));column.value=row.column;}
      column.addEventListener('change',()=>{row.column=column.value;row.type=valueType(columns.find(c=>c.name===column.value).dataType);changed();render();});
      input(label('code'),row.value,200,v=>row.value=v,fields);input(label('label'),row.label,200,v=>row.label=v,fields);
      input(label('aliases'),row.aliases.join('\n'),810,v=>row.aliases=aliases(v),fields,true);
      input(label('description'),row.description,500,v=>row.description=v,fields,true);box.append(fields);edits.append(box);
    }
  }
  render();
}
