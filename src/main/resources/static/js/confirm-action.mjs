import {t} from './i18n.mjs';
/** In-page confirmation remains keyboard accessible without blocking the browser process. */
export function confirmAction(message){
  return new Promise(resolve=>{
    const previous=document.activeElement,dialog=document.createElement('dialog'),title=document.createElement('h2'),body=document.createElement('p'),actions=document.createElement('div'),cancel=document.createElement('button'),accept=document.createElement('button');
    dialog.className='app-preview-dialog app-module-card';dialog.setAttribute('aria-label',t('confirmAction.title','Confirm action'));title.textContent=t('confirmAction.title','Confirm action');body.textContent=message;body.style.whiteSpace='pre-wrap';actions.className='app-editor-actions';
    cancel.type=accept.type='button';cancel.className='btn app-btn app-btn-quiet';accept.className='btn app-btn app-btn-primary';cancel.textContent=t('confirmAction.cancel','Cancel');accept.textContent=t('confirmAction.accept','Confirm');
    let accepted=false;accept.addEventListener('click',()=>{accepted=true;dialog.close();});cancel.addEventListener('click',()=>dialog.close());
    dialog.addEventListener('close',()=>{dialog.remove();previous?.focus();resolve(accepted);},{once:true});
    actions.append(cancel,accept);dialog.append(title,body,actions);document.body.append(dialog);dialog.showModal();cancel.focus();
  });
}
