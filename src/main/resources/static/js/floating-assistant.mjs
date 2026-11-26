import {t} from './i18n.mjs';
const label=(key,fallback)=>t('floatingAssistant.'+key,fallback);
export function pageProfile(location,doc){
  return doc.querySelector('[data-profile-history][data-profile]')?.dataset.profile||new URL(location.href).searchParams.get('profile')||'';
}
/** One modeless, lazy-loaded panel per authenticated document. No transcript persistence. */
export function mountFloatingAssistant(doc=document,win=window){
  if(!doc.querySelector('.app-topbar[data-admin-account]')?.dataset.adminAccount||doc.querySelector('[data-floating-assistant]'))return;
  const el=(tag,text,css)=>{const node=doc.createElement(tag);if(text!==undefined)node.textContent=text;if(css)node.className=css;return node;};
  const host=el('div',undefined,'app-floating-assistant');host.dataset.floatingAssistant='';
  const launcher=el('button',label('open','설정 도우미'),'app-assistant-launcher');launcher.type='button';launcher.setAttribute('aria-controls','global-setup-assistant');launcher.setAttribute('aria-expanded','false');
  const panel=el('section',undefined,'app-assistant-window');panel.id='global-setup-assistant';panel.hidden=true;panel.setAttribute('role','dialog');panel.setAttribute('aria-modal','false');panel.setAttribute('aria-labelledby','global-assistant-title');
  const header=el('div',undefined,'app-assistant-window-header'),title=el('h2',label('title','설정 도우미'));title.id='global-assistant-title';
  const task=el('button',label('task','현재 작업'),'app-btn app-btn-quiet');task.type='button';task.setAttribute('aria-pressed','false');
  const close=el('button','×','app-btn app-btn-quiet');close.type='button';close.setAttribute('aria-label',label('close','도우미 닫기'));
  header.append(title,task,close);const content=el('div',undefined,'app-assistant-window-content');panel.append(header,content);host.append(launcher,panel);doc.body.append(host);
  let loading=false,loaded=false,lastFocus=null;
  function expanded(value){host.classList.toggle('is-task-open',value);task.setAttribute('aria-pressed',String(value));task.textContent=value?label('conversation','대화 보기'):label('task','현재 작업');}
  function focusInput(){if(!panel.hidden)content.querySelector('[data-wb-input]')?.focus({preventScroll:true});}
  async function load(){
    if(loaded||loading)return;loading=true;panel.setAttribute('aria-busy','true');content.replaceChildren(el('p',t('ui.8bf609c884ca','불러오는 중…'),'app-assistant-loading'));
    try{
      const url='/ai-setup/assistant?'+new URLSearchParams({panel:'1',profile:pageProfile(win.location,doc)});
      const response=await win.fetch(url,{cache:'no-store',credentials:'same-origin',headers:{Accept:'text/html'}});
      if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1','로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'));
      if(!response.ok)throw new Error(label('loadFailed','도우미를 불러오지 못했습니다.'));
      const parsed=new win.DOMParser().parseFromString(await response.text(),'text/html');
      const source=parsed.querySelector('main[data-setup-workbench]');if(!source)throw new Error(label('loadFailed','도우미를 불러오지 못했습니다.'));
      // Use only the known server-rendered component, never document scripts or another app shell.
      const root=el('div',undefined,'setup-workbench setup-workbench-floating');
      for(const name of ['schema','allowed','initialProfile','model'])root.dataset[name]=source.dataset[name]||'';
      for(const node of Array.from(source.childNodes))root.append(doc.importNode(node,true));
      root.querySelectorAll('script').forEach(node=>node.remove());
      const {mountWorkbench}=await import('./setup-workbench.mjs');
      content.replaceChildren(root);
      root.addEventListener('setup-workspace-changed',event=>expanded(win.matchMedia('(max-width: 680px)').matches?['select','review','result'].includes(event.detail.step):event.detail.step!=='waiting'));
      mountWorkbench(root,{doc});loaded=true;focusInput();
    }catch(error){const message=el('p',error.message),retry=el('button',label('retry','다시 불러오기'),'app-btn');retry.type='button';retry.addEventListener('click',load);content.replaceChildren(message,retry);}
    finally{loading=false;panel.removeAttribute('aria-busy');}
  }
  function hide(){panel.hidden=true;launcher.setAttribute('aria-expanded','false');if(lastFocus?.isConnected)lastFocus.focus();else launcher.focus();}
  function show(){lastFocus=doc.activeElement;panel.hidden=false;launcher.setAttribute('aria-expanded','true');if(loaded)focusInput();else load();}
  launcher.addEventListener('click',()=>panel.hidden?show():hide());close.addEventListener('click',hide);
  task.addEventListener('click',()=>expanded(!host.classList.contains('is-task-open')));
  panel.addEventListener('keydown',event=>{if(event.key==='Escape'&&!panel.querySelector('dialog[open]')){event.preventDefault();hide();}});
  doc.addEventListener('click',event=>{const a=event.target.closest?.('a[href="/ai-setup/assistant"]');if(a&&!event.defaultPrevented&&event.button===0&&!event.metaKey&&!event.ctrlKey&&!event.shiftKey&&!event.altKey){event.preventDefault();show();}});
  if(new URL(win.location.href).searchParams.get('assistant')==='open')show();
  return {show,hide};
}
if(typeof document!=='undefined')mountFloatingAssistant();
