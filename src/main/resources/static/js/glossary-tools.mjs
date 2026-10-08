export function mountGlossaryTools(root){
  const tabs=[...root.querySelectorAll('[data-glossary-tool-tab]')];
  const panels=[...root.querySelectorAll('[data-glossary-tool-panel]')];
  if(!tabs.length)return;
  const select=tab=>{for(const item of tabs){const active=item===tab;item.setAttribute('aria-selected',String(active));item.tabIndex=active?0:-1;}for(const panel of panels)panel.hidden=panel.id!==tab.getAttribute('aria-controls');};
  for(const tab of tabs){
    tab.addEventListener('click',()=>{if(!tab.disabled)select(tab);});
    tab.addEventListener('keydown',event=>{
      if(tab.disabled||!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;
      event.preventDefault();const index=event.key==='Home'?0:event.key==='End'?tabs.length-1:(tabs.indexOf(tab)+(event.key==='ArrowRight'?1:-1)+tabs.length)%tabs.length;
      if(!tabs[index].disabled){select(tabs[index]);tabs[index].focus();}
    });
  }
  select(tabs[0]);
}
