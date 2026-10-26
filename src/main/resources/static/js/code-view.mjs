import {mountSourceViewer} from './source-viewer.mjs';

// Keep the original PRE as the application's data source. Updating its text
// updates the viewer too; copying uses the unmodified source, not line numbers.
export function isSql(text){
  return /^\s*(?:--[^\n]*\n\s*|\/\*[\s\S]*?\*\/\s*)*(?:SELECT|WITH|CREATE|ALTER|DROP|GRANT|REVOKE|BEGIN|DECLARE|INSERT|UPDATE|DELETE|MERGE|FUNCTION|PROCEDURE|PACKAGE|EXPLAIN|CALL|SET|VARIABLE)\b/i.test(text);
}
export function mountCodeViews(root){
  const views=new WeakMap();
  const update=pre=>{
    if(pre.closest('.app-code-view'))return;
    const sql=pre.dataset.codeLanguage==='sql'||isSql(pre.textContent);
    let view=views.get(pre);
    if(!sql){if(view)view.hidden=true;pre.classList.remove('app-code-source');return;}
    if(!view){
      const host=pre.ownerDocument.createElement('div');host.className='app-code-view';
      pre.after(host);view=host;views.set(pre,view);
    }
    if(view.source!==pre.textContent){
      view.source=pre.textContent;view.replaceChildren();
      mountSourceViewer(view,[{type:'SQL / PL/SQL',text:view.source}],null);
    }
    pre.classList.add('app-code-source');view.hidden=pre.hidden;
  };
  const scan=node=>{
    if(node.nodeType===1&&node.matches('pre'))update(node);
    node.querySelectorAll?.('pre').forEach(update);
  };
  scan(root);
  const observer=new MutationObserver(records=>{
    for(const record of records){
      const pre=record.target.nodeType===1?record.target.closest('pre'):record.target.parentElement?.closest('pre');
      if(pre)update(pre);
      for(const node of record.addedNodes||[])scan(node);
    }
  });
  observer.observe(root,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['hidden']});
  return ()=>observer.disconnect();
}
