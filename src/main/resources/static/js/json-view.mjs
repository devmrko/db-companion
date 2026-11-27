import {t} from './i18n.mjs';

// Validate with JSON.parse, then keep number lexemes and duplicate object entries.
// The original source remains authoritative for copying and downloads.
export function jsonTree(source){
  if(typeof source!=='string'||source.length>250000||!/^\s*[\[{]/.test(source))return null;
  try{
    JSON.parse(source);
    const tokens=source.match(/"(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null|[{}\[\],:]/g);
    let index=0;
    function read(depth=0){
      if(depth>32)throw new Error('depth');
      const token=tokens[index++];
      if(token==='{'||token==='['){
        const object=token==='{',entries=[],end=object?'}':']';
        while(tokens[index]!==end){
          let key=entries.length;
          if(object){key=JSON.parse(tokens[index++]);index++;}
          entries.push([key,read(depth+1)]);
          if(tokens[index]!==',')break;
          index++;
        }
        index++;return {kind:object?'object':'array',entries};
      }
      return {kind:token[0]==='"'?'string':token==='null'?'null':/^(true|false)$/.test(token)?'boolean':'number',value:token[0]==='"'?JSON.parse(token):token};
    }
    return read();
  }catch{return null;}
}

export function mountJsonView(host,source){
  const tree=jsonTree(source);if(!tree)return false;
  const doc=host.ownerDocument;
  const el=(tag,value,css)=>{const node=doc.createElement(tag);if(value!==undefined)node.textContent=value;if(css)node.className=css;return node;};
  const toolbar=el('div',undefined,'app-json-toolbar'),status=el('span');status.setAttribute('role','status');
  const copy=el('button',t('jsonView.copy','JSON 복사'),'btn app-btn app-btn-secondary');copy.type='button';
  copy.addEventListener('click',async()=>{try{await doc.defaultView.navigator.clipboard.writeText(source);status.textContent=t('jsonView.copied','복사 완료');}catch{status.textContent=t('jsonView.copyFailed','원본 JSON을 선택해 복사하세요.');}});
  const download=el('button',t('jsonView.download','JSON 내려받기'),'btn app-btn app-btn-secondary');download.type='button';
  download.addEventListener('click',()=>{const url=URL.createObjectURL(new Blob([source],{type:'application/json;charset=utf-8'})),a=el('a');a.href=url;a.download='data.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
  toolbar.append(copy,download,status);host.append(toolbar);
  function render(node,parent){
    if(!node.entries){parent.append(el('span',node.kind==='string'?(node.value||'""'):node.value,'app-json-scalar app-json-'+node.kind));return;}
    if(!node.entries.length){parent.append(el('span',node.kind==='object'?'{}':'[]','app-json-empty'));return;}
    const list=el('dl',undefined,'app-json-fields');parent.append(list);let offset=0;
    const more=el('button',t('jsonView.more','더 보기'),'btn app-btn app-btn-secondary');more.type='button';
    function batch(){
      more.remove();const end=Math.min(offset+40,node.entries.length);
      for(;offset<end;offset++){
        const [key,value]=node.entries[offset],row=el('div',undefined,'app-json-field'),label=el('dt',String(key)),content=el('dd');row.append(label,content);list.append(row);
        if(value.entries){const details=el('details'),summary=el('summary',`${value.kind==='object'?'{}':'[]'} · ${value.entries.length}`);details.append(summary);let loaded=false;
          details.addEventListener('toggle',()=>{if(details.open&&!loaded){loaded=true;render(value,details);}});content.append(details);
        }else render(value,content);
      }
      if(offset<node.entries.length)parent.append(more);
    }
    more.addEventListener('click',batch);batch();
  }
  render(tree,host);
  const original=el('details',undefined,'app-json-original');original.append(el('summary',t('jsonView.original','원본 JSON')),el('pre',source));host.append(original);
  return true;
}
