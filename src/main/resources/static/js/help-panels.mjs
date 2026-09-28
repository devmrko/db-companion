/** Viewport-safe placement for icon help, including dynamically rendered tabs. */
export function helpPanelPosition(anchor, panel, viewport) {
  const gutter=12, gap=8;
  const maxLeft=Math.max(gutter,viewport.width-panel.width-gutter);
  const maxTop=Math.max(gutter,viewport.height-panel.height-gutter);
  const below=anchor.bottom+gap;
  const above=anchor.top-panel.height-gap;
  const preferred=below<=maxTop?below:above>=gutter?above:below;
  return {
    left:Math.max(gutter,Math.min(anchor.left,maxLeft)),
    top:Math.max(gutter,Math.min(preferred,maxTop))
  };
}

export function mountHelpPanels(doc, viewport) {
  let active=null;
  const clear=help=>{
    const body=help?.querySelector(':scope > div');
    if(body){delete body.dataset.helpPositioned;body.style.removeProperty('left');body.style.removeProperty('top');}
  };
  const close=()=>{if(active){const previous=active;active=null;previous.open=false;clear(previous);}};
  const toggle=event=>{
    const help=event.target;
    if(!help?.matches?.('details.app-dds-help'))return;
    if(!help.open){clear(help);if(active===help)active=null;return;}
    if(active!==help)close();
    const summary=help.querySelector(':scope > summary'),body=help.querySelector(':scope > div');
    if(!summary||!body)return;
    active=help;
    body.dataset.helpPositioned='true';
    const position=helpPanelPosition(summary.getBoundingClientRect(),body.getBoundingClientRect(),
      {width:viewport.innerWidth,height:viewport.innerHeight});
    body.style.left=position.left+'px';body.style.top=position.top+'px';
  };
  const outside=event=>{if(active&&!active.contains(event.target))close();};
  const escape=event=>{
    if(event.key!=='Escape'||!active)return;
    const summary=active.querySelector(':scope > summary');
    close();event.preventDefault();event.stopPropagation();summary?.focus();
  };
  const scroll=event=>{if(active&&!(event.target?.nodeType&&active.contains(event.target)))close();};
  const dialogClose=event=>{if(active&&event.target===active.closest('dialog'))close();};
  doc.addEventListener('toggle',toggle,true);
  doc.addEventListener('click',outside);
  doc.addEventListener('keydown',escape,true);
  doc.addEventListener('scroll',scroll,true);
  doc.addEventListener('close',dialogClose,true);
  viewport.addEventListener('resize',close);
  return ()=>{
    close();doc.removeEventListener('toggle',toggle,true);doc.removeEventListener('click',outside);
    doc.removeEventListener('keydown',escape,true);doc.removeEventListener('scroll',scroll,true);
    doc.removeEventListener('close',dialogClose,true);viewport.removeEventListener('resize',close);
  };
}
