import {t} from './i18n.mjs';
import {profileField,distinctObjectChoices} from './profile-fields.mjs';
import {helpFor} from './sql-help.mjs';
const tr=(key,fallback,...args)=>t('creationChat.'+key,fallback,...args);
let sequence=0;

export function creationCommand(question){
  if(/(?:하지\s*마|말고|설명만|don't|do not)/i.test(question))return null;
  if(/(?:테이블|객체|table|object)/i.test(question))return {kind:'tables',remove:/(?:빼|제외|삭제|remove)/i.test(question)};
  if(/모델|model/i.test(question)&&/뭐|무엇|어떤|목록|what|which|list|\?/i.test(question))return {kind:'models'};
  const keys=[['name',/(?:이름|name)/i],['provider',/(?:제공자|provider)/i],['model',/(?:모델|model)/i],['credential_name',/(?:인증|credential)/i],['region',/(?:리전|region)/i],['oci_compartment_id',/(?:컴파트먼트|compartment)/i]];
  for(const [key,pattern]of keys)if(pattern.test(question)){
    const raw=question.replace(pattern,'').replace(/^(?:은|는|을|를|[:=])\s*/,'').trim();
    const value=raw.replace(/(?:으로|로)?\s*(?:바꿔(?:줘)?|변경(?:해줘)?|설정(?:해줘)?|쓰자|사용(?:해줘)?)\s*[.!]?$/,'').trim();
    if(value&&!/^(?:뭐|무엇|어떤|what|which)/i.test(value))return {kind:'field',key,value};
  }
  if(/^(?:이대로\s*)?(?:만들어(?:줘)?|생성(?:해줘)?|확인|완료|다음|create|review|next)[.!]?$/i.test(question))return {kind:'review'};
  return null;
}
export function selectedObjects(question,rows,owner){
  const requested=new Set();
  const ordinals=[['첫',1],['두',2],['세',3],['네',4],['다섯',5]];
  for(const [word,index]of ordinals)if(new RegExp(word+'\\s*번째').test(question))requested.add(index);
  for(const match of question.matchAll(/(?:^|[^\w])([0-9]+)\s*(?:번(?:째)?|(?=[,\s]|$))/g))requested.add(Number(match[1]));
  const values=[];
  for(const index of requested){if(index<1||index>rows.length)throw new Error(tr('pick','표시된 목록의 번호나 정확한 객체 이름을 선택하세요.'));values.push({owner,name:rows[index-1].name});}
  for(const row of rows)if(question.split(/[\s,]+/).some(word=>word===row.name||word===owner+'.'+row.name))values.push({owner,name:row.name});
  for(const token of question.split(/[\s,]+/).filter(v=>/^[A-Z][A-Z0-9_$#]*(?:\.[A-Z][A-Z0-9_$#]*)?$/.test(v)))
    if(!rows.some(v=>token===v.name||token===owner+'.'+v.name))throw new Error(tr('pick','표시된 목록의 번호나 정확한 객체 이름을 선택하세요.'));
  const distinct=[...new Map(values.map(v=>[v.owner+'.'+v.name,v])).values()];
  if(!distinct.length)throw new Error(tr('pick','표시된 목록의 번호나 정확한 객체 이름을 선택하세요.'));
  return distinct;
}
export function mergeObjects(before,selected,remove=false){
  const keys=new Set(selected.map(v=>v.owner+'.'+v.name));
  return remove?before.filter(v=>!keys.has(v.owner+'.'+v.name)):[...new Map([...before,...selected].map(v=>[v.owner+'.'+v.name,v])).values()];
}

/** A bounded conversation shares the existing create service; no implicit model or database writes. */
export function creationConversation(ui){
  const {schema,api,post,workspace,field,button,ask,say,detail,link,review,el}=ui;
  let draft=null,form=null,options=null,rows=[],owner=schema,filter='',stage='name';
  const url=()=>'/ai-create/profile-options?'+new URLSearchParams({schema,owner,filter,provider:draft.attributes.provider});
  const summary=body=>detail(body,tr('draft','현재 생성 초안'),draft);
  const set=(key,value)=>{if(key==='name'){value=value.toUpperCase();if(!/^[A-Z][A-Z0-9_$#]{0,124}$/.test(value))throw new Error(tr('nameHelp','이름은 영문자로 시작하는 영문 대문자·숫자·_·$·# 125자 이내로 입력하세요.'));draft.name=value;}
    else {if(key==='provider'&&!profileField('provider').choices.includes(value))throw new Error(tr('providerHelp','목록의 제공자를 선택하세요.'));draft.attributes[key]=value;}};
  async function tables(){
    stage='tables';options=await api(url());rows=distinctObjectChoices(options.objects.objects);
    const body=workspace(tr('tables','조회할 테이블 선택'),'select');summary(body);
    const scope=field(body,tr('schema','검색할 스키마'),owner),search=field(body,tr('filter','객체 이름 검색'),filter);
    const selection=el('p',draft.attributes.object_list.map(v=>v.owner+'.'+v.name).join(', '));body.append(selection);
    body.append(button(tr('search','검색'),async()=>{owner=scope.value.trim();filter=search.value.trim();await tables();}));
    for(const [i,row]of rows.entries()){
      const label=el('label',undefined,'setup-workspace-consent'),check=el('input');check.type='checkbox';check.checked=draft.attributes.object_list.some(v=>v.owner===owner&&v.name===row.name);
      check.addEventListener('change',()=>{draft.attributes.object_list=mergeObjects(draft.attributes.object_list,[{owner,name:row.name}],!check.checked);selection.textContent=draft.attributes.object_list.map(v=>v.owner+'.'+v.name).join(', ');});
      label.append(check,el('span',`${i+1}. ${owner}.${row.name} · ${row.type}`));body.append(label);
    }
    if(options.objects.more)body.append(el('p',tr('more','일부 결과입니다. 검색어를 좁혀 다른 객체를 찾으세요.')));
    body.append(button(tr('model','모델·인증 선택'),models));
    ask(tr('tableQuestion','번호나 정확한 테이블 이름을 입력하거나 목록에서 선택하세요. 선택 후 “다음”을 입력하세요.'),handle);
  }
  async function models(){
    stage='model';options=await api(url());const body=workspace(tr('model','모델·인증 선택'),'edit');summary(body);
    body.append(el('p',tr('candidateNote','모델·리전 후보는 같은 제공자를 사용하는 기존 프로필값입니다. 전체 지원 목록이나 호출 성공을 의미하지 않습니다. 도우미 실행 모델은 변경하지 않습니다.')));
    for(const key of ['provider','model','credential_name','region','oci_compartment_id']){
      const input=field(body,key,draft.attributes[key]||'');input.setAttribute('autocomplete','off');
      input.addEventListener('input',()=>{draft.attributes[key]=input.value.trim();});
      const choices=key==='provider'?profileField(key).choices:key==='model'?options.models:key==='region'?options.regions:key==='credential_name'?form.credentials:[];
      if(choices.length){const list=el('datalist');list.id=`creation-choice-${++sequence}`;input.setAttribute('list',list.id);for(const value of choices){const option=el('option',value);option.value=value;list.append(option);}body.append(list);}
    }
    body.append(el('p',tr('noSecret','인증 정보는 Credential 이름만 사용합니다. 비밀번호·API 키를 입력하지 마세요. 빈 인증값의 사용 가능 여부는 제공자의 인증 방식에 따라 확인해야 합니다.')));
    body.append(button(tr('review','생성안 확인'),prepare),button(tr('tables','조회할 테이블 선택'),tables));
    ask(tr('modelQuestion','값을 입력하거나 “모델은 모델명 쓰자”처럼 수정하세요. “이대로 만들어”를 입력하면 생성안을 표시합니다.'),handle);
  }
  async function prepare(){
    set('name',draft.name);set('provider',draft.attributes.provider);
    if(!draft.attributes.model||!draft.attributes.object_list.length)throw new Error(tr('required','이름·모델·테이블을 선택하세요.'));
    const attributes=Object.fromEntries(Object.entries(draft.attributes).filter(([,v])=>typeof v!=='string'||v.trim()));
    const preview=await post('/ai-create/preview',{schema,kind:'PROFILE',source:'',sourceVersion:form.sourceVersion,name:draft.name,description:draft.description,status:'ENABLED',attributes:JSON.stringify(attributes),copyFeedback:false});
    stage='review';
    const visible={schema,name:preview.input.name,status:preview.input.status,...JSON.parse(preview.input.attributes)};
    review(draft.name,tr('new','신규 프로파일'),visible,async()=>{
      const saved=await post('/ai-create/create',{token:preview.token,confirmed:true,consent:true});
      const body=workspace(tr('result','프로파일 생성 결과'),'result');detail(body,tr('result','프로파일 생성 결과'),saved);
      if(saved.verified&&saved.done){draft=null;ui.created(saved.name);say('assistant',tr('created','프로파일 생성값을 재조회해 일치를 확인했습니다. 실제 질문 실행 성공은 별도 확인이 필요합니다.'));
        link(body,tr('open','프로파일 상세'),'/ai-profiles/detail?'+new URLSearchParams({profile:saved.name}));
        body.append(button(tr('test','질문으로 확인'),()=>ui.verify(saved.name)));
        try{detail(body,tr('history','변경 이력'),await api('/ai-profiles/history?'+new URLSearchParams({schema,profile:saved.name,page:'1',scope:'profile'})));}catch(error){body.append(el('p',error.message));}
      }else{draft=null;say('assistant',tr('uncertain','생성 결과를 확정하지 못했습니다. 상세와 이력을 조회하세요. 자동 재시도하지 않습니다.'));link(body,tr('open','프로파일 상세'),'/ai-profiles/detail?'+new URLSearchParams({profile:saved.name}));}
    },tr('consent','적용 시 표시된 메타데이터가 AI 제공자에게 전송되거나 비용이 발생할 수 있습니다. 내용을 검토하고 동의할 때만 적용하세요.'));
    const help=helpFor('profiles',ui.lang||'ko','create');
    if(help)detail(ui.body(),tr('sqlExample','SQL 예제 · 현재 초안의 실제 값은 위 생성안 참조'),help.sql);
    ask(tr('reviewHelp','생성안을 확인한 뒤 체크하고 적용하세요. 수정 요청은 확인을 무효화합니다.'),handle);
  }
  async function handle(question){
    const command=creationCommand(question);
    if(command?.kind==='models'){await models();return;}
    if(command?.kind==='field'){set(command.key,command.value);if(!draft.name)return;await(command.key==='name'&&stage==='name'?tables():models());return;}
    if(stage==='name'){set('name',question);await tables();return;}
    if(command?.kind==='tables'){
      if(/(?:뭐|목록|조회|보여|what|list)/i.test(question)){await tables();return;}
      draft.attributes.object_list=mergeObjects(draft.attributes.object_list,selectedObjects(question,rows,owner),command.remove);await tables();return;
    }
    if(command?.kind==='review'){await(stage==='tables'?models():prepare());return;}
    if(stage==='tables'){draft.attributes.object_list=mergeObjects(draft.attributes.object_list,selectedObjects(question,rows,owner),/(?:빼|제외|remove)/i.test(question));await tables();return;}
    say('assistant',tr('clarify','수정할 항목을 지정하세요. 예: 이름은 AU_PROFILE, 모델은 모델명 쓰자, 테이블 목록, 이대로 만들어.'));ask(tr('modelQuestion','값을 입력하거나 “모델은 모델명 쓰자”처럼 수정하세요. “이대로 만들어”를 입력하면 생성안을 표시합니다.'),handle);
  }
  return {
    async start(){form=await api('/ai-create/form?'+new URLSearchParams({schema,kind:'PROFILE',source:''}));
      if(!form.historyReady){const body=workspace(tr('prepare','프로파일 이력 준비'),'result');say('assistant',tr('historyRequired','생성 전 프로파일 이력 저장소가 필요합니다. 준비 화면에서 설치 내용을 확인하세요.'));link(body,tr('prepare','프로파일 이력 준비'),'/ai-profiles');return;}
      owner=schema;filter='';rows=[];stage='name';draft={name:'',description:'',attributes:{provider:'oci',model:'',credential_name:'',region:'',oci_compartment_id:'',comments:true,conversation:false,object_list:[]}};
      summary(workspace(tr('new','신규 프로파일'),'edit'));ask(tr('nameQuestion','새 프로파일 이름을 입력하세요.'),handle);
    },
    clear(){draft=null;form=null;rows=[];},
    active:()=>draft!==null,
    async resume(){if(stage==='name'){summary(workspace(tr('new','신규 프로파일'),'edit'));ask(tr('nameQuestion','새 프로파일 이름을 입력하세요.'),handle);}else await(stage==='tables'?tables():models());},
    handle,
  };
}
