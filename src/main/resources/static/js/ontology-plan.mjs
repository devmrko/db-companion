import {t} from './i18n.mjs';
const label=key=>t('ontology.query.plan.'+key,key);
const node=(tag,text)=>{const e=document.createElement(tag);if(text!=null)e.textContent=text;return e;};
export function blockedSources(plan,sources){return plan.mode==='INDEPENDENT'?plan.tables.filter(name=>!sources.some(s=>s.name===name&&s.state==='APPROVED')):[];}
export function renderPlan(host,plan,search,candidates,sources,apply,edited=()=>{}){
  host.replaceChildren();host.hidden=false;
  host.append(node('h3',label('title')),node('p',plan.reason),node('p',label('note')));
  for(const question of plan.questions)host.append(node('p',question));
  const mode=node('select');mode.className='form-select';mode.setAttribute('aria-label',label('mode'));
  for(const value of ['REVIEW','PATH','INDEPENDENT']){const option=node('option',label(value));option.value=value;mode.append(option);}mode.value=plan.mode;host.append(mode);
  const routes=node('select');routes.className='form-select';routes.setAttribute('aria-label',label('PATH'));
  const empty=node('option',label('choose'));empty.value='';routes.append(empty);
  for(const route of search.routes){const option=node('option',route.tables.join(' → '));option.value=route.id;routes.append(option);}routes.value=plan.routeId;host.append(routes);
  const sourceBox=node('fieldset');sourceBox.append(node('legend',label('sources')));const sourceChecks=[];
  for(const source of sources){const row=node('label'),check=node('input');check.type='checkbox';check.value=source.name;check.checked=plan.tables.includes(source.name);row.append(check,node('span',`${source.name} · ${source.state}`));sourceBox.append(row,node('br'));sourceChecks.push(check);}host.append(sourceBox);
  const candidateBox=node('details');candidateBox.open=true;candidateBox.append(node('summary',label('candidates')));const checks=[];
  for(const candidate of candidates){const suggestion=plan.candidates.find(c=>c.id===candidate.id);const row=node('div'),check=node('input');check.type='checkbox';check.value=candidate.id;check.checked=suggestion?.selected===true;
    const item=node('label');item.append(check,node('span',`${candidate.source} → ${candidate.target} · ${candidate.status} · ${candidate.label}`));row.append(item,node('p',suggestion?`${label(suggestion.use)} · ${suggestion.reason}`:label('notRecommended')));candidateBox.append(row);checks.push(check);}
  host.append(candidateBox);
  const submit=node('button',label('apply'));submit.type='button';submit.className='btn app-btn app-btn-primary';
  const notice=node('p');host.append(notice);
  const update=()=>{routes.hidden=mode.value!=='PATH';sourceBox.hidden=mode.value!=='INDEPENDENT';
    const selected=sourceChecks.filter(c=>c.checked).map(c=>c.value),blocked=blockedSources({mode:mode.value,tables:selected},sources);
    notice.textContent=blocked.length?`${label('blocked')} ${blocked.join(', ')}`:'';
    submit.disabled=mode.value==='REVIEW'||blocked.length>0||(mode.value==='INDEPENDENT'&&!selected.length)||(mode.value==='PATH'&&!routes.value);
    if(submit.disabled)submit.dataset.localDisabled='';else delete submit.dataset.localDisabled;};
  mode.addEventListener('change',update);update();
  submit.addEventListener('click',()=>{if(submit.disabled)return;apply({mode:mode.value,route:routes.value,tables:sourceChecks.filter(c=>c.checked).map(c=>c.value),candidates:checks.filter(c=>c.checked).map(c=>c.value)});});host.append(submit);
  for(const input of [mode,routes,...sourceChecks,...checks])input.addEventListener('change',()=>{update();edited();});
}
