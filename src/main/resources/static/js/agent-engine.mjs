import {flowGraph,storedGraph,traceGraph,renderFlow} from './engine-flow.mjs';
export function parseFlow(text) {
  if(text.length>500000)throw new Error('AgentSpec은 500,000자까지 지원합니다.');
  const spec=JSON.parse(text);
  if(spec.component_type!=='Flow'||!Array.isArray(spec.inputs)||!spec.agentspec_version)throw new Error('Flow 형식의 AgentSpec이 필요합니다.');
  const names=new Set();
  for(const field of spec.inputs){
    if(!field.title||names.has(field.title)||!['string','number','integer','boolean','object','array'].includes(field.type))throw new Error('입력 이름·타입을 확인하세요. 중복 이름은 허용하지 않습니다.');
    names.add(field.title);
  }
  return spec;
}
export function inputValue(field, value) {
  if(field.type==='string')return value;
  const parsed=JSON.parse(value);
  const valid=field.type==='number'?typeof parsed==='number':field.type==='integer'?Number.isInteger(parsed):field.type==='boolean'?typeof parsed==='boolean':field.type==='array'?Array.isArray(parsed):parsed!==null&&typeof parsed==='object'&&!Array.isArray(parsed);
  if(!valid)throw new Error(`${field.title}: ${field.type} 값을 입력하세요.`);
  return parsed;
}
export function components(spec) {
  const items=[];
  function visit(value, depth=0) {
    if(depth>80||!value||typeof value!=='object')return;
    if(value.component_type)items.push(value);
    for(const item of Object.values(value))visit(item,depth+1);
  }
  visit(spec);return items;
}
export function mountEngine(root) {
  const get=n=>root.querySelector(`[data-engine-${n}]`);
  const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;};
  let spec=null, loaded='', supported=false, busy=false, stored=null, catalogSupported=false, storedSupported=false;
  let editorSpec=null;
  function editorSource(value){editorSpec=value;get('editor').dispatchEvent(new CustomEvent('engine-source',{detail:value}));}
  const message=text=>{get('message').textContent=text;};
  const controls=()=>{get('run').disabled=busy||!(stored?storedSupported:supported)||!spec||(!stored&&loaded!==get('spec').value);get('file').disabled=busy;get('load').disabled=busy;get('spec').disabled=busy;get('agents').disabled=busy||!catalogSupported;get('refresh').disabled=busy||!catalogSupported;};
  async function api(path,options={}) {
    const response=await fetch('/ai-agent-engine'+path,{credentials:'same-origin',cache:'no-store',...options});
    if(response.redirected||response.status===401)throw new Error('로그인 세션이 만료되었습니다.');
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error('서버 응답을 확인하지 못했습니다.');
    const data=await response.json();if(!response.ok)throw new Error(data.error||'처리 실패');return data;
  }
import('../vendor/ontology-flow/engine-workbench.js').then(({mountWorkbench})=>mountWorkbench(get('editor'),{read:()=>editorSpec,subscribe:handler=>{const listener=e=>handler(e.detail);get('editor').addEventListener('engine-source',listener);return ()=>get('editor').removeEventListener('engine-source',listener);},apply:value=>{get('spec').value=JSON.stringify(value,null,2);load();},busy:()=>busy,tools:()=>api('/tools?'+new URLSearchParams({schema:root.dataset.schema}))})).catch(e=>{get('editor').textContent='편집기를 불러오지 못했습니다: '+e.message;});
  function load() {
    spec=null;stored=null;get('agents').value='';get('detail-panel').hidden=true;get('confirm').checked=false;get('results').hidden=true;
    get('inputs').replaceChildren();get('preview').replaceChildren();
    try {
      spec=parseFlow(get('spec').value);loaded=get('spec').value;
      renderFlow(get('diagram'),flowGraph(spec));
      get('preview').append(el('h3',spec.name||spec.id||'Flow'));
      const all=components(spec);
      const steps=el('div',undefined,'app-engine-steps');
      const resolve=value=>value?.$component_ref?spec.$referenced_components?.[value.$component_ref]||value:value;
      for(const value of spec.nodes||[]) {
        const node=resolve(value);const step=el('div',undefined,'app-engine-step');
        step.append(el('span',node.component_type||'Node','app-muted'),el('strong',node.name||node.id||value.$component_ref));steps.append(step);
      }
      get('preview').append(steps);
      const list=el('ul',undefined,'app-config-list');
      for(const item of all.filter(item=>item.component_type.endsWith('Tool'))) {
        const config=item.configuration||{};
        const target=[config.schema_name,config.package_name,config.procedure_name].filter(Boolean).join('.');
        list.append(el('li',[item.component_type,item.name||item.id,target].filter(Boolean).join(' · ')));
      }
      get('preview').append(el('h3','호출 도구'),list);
      const connections=el('details',undefined,'app-config-disclosure');connections.append(el('summary','분기·연결 보기'));
      const edges=el('ul',undefined,'app-config-list');
      for(const edge of spec.control_flow_connections||[])edges.append(el('li',`${edge.from_node?.$component_ref||edge.from_node?.name||'?'} → ${edge.to_node?.$component_ref||edge.to_node?.name||'?'} · ${edge.from_branch||''}`));
      connections.append(edges);get('preview').append(connections);
      renderInputs(spec.inputs);
      renderOutputs(spec.outputs||[]);
      editorSource(spec);
      message('정의를 읽었습니다. 입력값과 호출 도구를 확인하세요.');
    }catch(error){spec=null;get('diagram').replaceChildren();message(error.message);}controls();
  }
  function renderInputs(fields) {
      get('inputs').replaceChildren();
      for(const field of fields) {
        const label=el('label',`${field.title} · ${field.type}`,'app-config-field');
        const control=el(field.type==='boolean'?'select':'input');control.className='form-control';control.dataset.inputName=field.title;
        if(field.type==='boolean')for(const value of ['true','false']){const option=el('option',value);option.value=value;control.append(option);}
        else {control.type='text';control.required=true;}
        if(field.default!==undefined)control.value=typeof field.default==='string'?field.default:JSON.stringify(field.default);
        label.append(control);get('inputs').append(label);
      }
  }
  function renderOutputs(fields) {
    const list=el('ul',undefined,'app-config-list');
    for(const field of fields)list.append(el('li',`${field.title} · ${field.type||'JSON'}`));
    if(!fields.length)list.append(el('li','선언된 출력이 없습니다.'));
    get('preview').append(el('h3','출력 규격'),list);
  }
  function clearSelection() {
    editorSource(null);
    spec=null;stored=null;loaded='';get('spec').value='';get('confirm').checked=false;
    get('results').hidden=true;get('detail-panel').hidden=true;get('inputs').replaceChildren();get('preview').replaceChildren();get('diagram').replaceChildren();
  }
  async function refreshAgents() {
    if(busy||!catalogSupported)return;
    busy=true;clearSelection();controls();get('agents').replaceChildren();
    try {
      const rows=await api('/agents?'+new URLSearchParams({schema:root.dataset.schema}));
      const first=el('option',rows.length?'등록된 Agent를 선택하세요':'등록된 Agent가 없습니다.');first.value='';get('agents').append(first);
      for(const row of rows){const option=el('option',row.name);option.value=row.name;get('agents').append(option);}
      get('catalog-status').textContent=`등록 ${rows.length}개 · 목록 조회는 실행하지 않습니다.`;
    }catch(error){get('catalog-status').textContent=error.message;}finally{busy=false;controls();}
  }
  get('refresh').addEventListener('click',refreshAgents);
  get('agents').addEventListener('change',async()=>{
    const name=get('agents').value;if(busy)return;clearSelection();if(!name){controls();return;}
    busy=true;controls();message('등록된 Agent 상세를 조회 중입니다.');
    try {
      const detail=await api('/agent?'+new URLSearchParams({schema:root.dataset.schema,name}));
      renderFlow(get('diagram'),storedGraph(detail));
      get('detail').textContent=JSON.stringify(detail,null,2);get('detail-panel').hidden=false;
      get('preview').append(el('h3',detail.name),el('p',`생성일: ${detail.create_time||'미제공'}`,'app-muted'),el('p',detail.description||'등록된 설명이 없습니다.'));
      renderOutputs(detail.outputs||[]);
      // Validate supported input types without presenting a fabricated stored Flow definition.
      parseFlow(JSON.stringify({component_type:'Flow',agentspec_version:'input-contract',inputs:detail.inputs}));
      stored=detail;spec={inputs:detail.inputs};renderInputs(detail.inputs);
      message(`${detail.name} 조회 완료 · DB에 등록된 이름으로 실행합니다.`);
      try {
        const original=await api('/definition?'+new URLSearchParams({schema:root.dataset.schema,name}));
        editorSource(parseFlow(JSON.stringify(original.spec)));
        renderFlow(get('diagram'),flowGraph(original.spec));
        message(`${detail.name} · 보관된 등록 원본을 캔버스에 로드했습니다. DB 등록 정보 일치 확인. 편집은 실행에 적용하기 전까지 초안입니다.`);
      }catch(error){message(`${detail.name} · ${error.message}`);}
    }catch(error){stored=null;spec=null;message(error.message);}finally{busy=false;controls();}
  });
  get('load').addEventListener('click',load);
  get('original').addEventListener('change',async event=>{
    const file=event.target.files?.[0],name=get('agents').value;
    if(!file)return;
    try{
      if(!name||busy)throw Error('먼저 등록된 Agent를 선택하세요.');
      if(file.size>2000000)throw Error('원본 파일은 2MB 이하만 지원합니다.');
      const text=await file.text();parseFlow(text);
      busy=true;controls();
      await api('/definition',{method:'POST',headers:{'Content-Type':'application/json',[get('csrf').dataset.header]:get('csrf').value},body:JSON.stringify({schema:root.dataset.schema,name,spec:text,confirmed:true})});
      const original=await api('/definition?'+new URLSearchParams({schema:root.dataset.schema,name}));
      editorSource(original.spec);renderFlow(get('diagram'),flowGraph(original.spec));
      message(`${name} · 등록 원본을 보관하고 캔버스에 로드했습니다. DB 정의는 변경하지 않았습니다.`);
    }catch(error){message(error.message);}finally{busy=false;controls();event.target.value='';}
  });
  get('spec').addEventListener('input',()=>{get('diagram').replaceChildren();if(stored){stored=null;spec=null;get('agents').value='';get('inputs').replaceChildren();get('preview').replaceChildren();get('detail-panel').hidden=true;}get('confirm').checked=false;controls();});
  get('file').addEventListener('change',async()=>{try{const file=get('file').files[0];if(!file)return;if(file.size>2000000)throw new Error('파일 크기를 확인하세요.');get('spec').value=await file.text();load();}catch(error){message(error.message);}});
  get('inputs').addEventListener('input',()=>{get('confirm').checked=false;});
  function traceTree(nodes,depth=0) {
    const list=el('ul',undefined,'app-engine-trace');
    if(depth>40)return list;
    for(const node of (Array.isArray(nodes)?nodes:[])) {
      const li=el('li');const details=el('details',undefined,'app-config-disclosure');details.open=depth<2;
      details.append(el('summary',`${node.component_type||'Node'} · ${node.node_name||node.node_id||''}`));
      details.append(el('pre',JSON.stringify(node.events||[],null,2),'app-config-output'));
      if(node.children?.length)details.append(traceTree(node.children,depth+1));
      li.append(details);list.append(li);
    }
    return list;
  }
  get('form').addEventListener('submit',async event=>{
    event.preventDefault();if(busy||!spec||(!stored&&loaded!==get('spec').value)||!(stored?storedSupported:supported)||!get('confirm').checked)return;
    try {
      const inputs=spec.inputs.map(field=>({name:field.title,value:inputValue(field,[...get('inputs').querySelectorAll('[data-input-name]')].find(e=>e.dataset.inputName===field.title).value)}));
      busy=true;controls();get('results').hidden=true;message('DB에서 실행 중입니다. 자동 재시도하지 않습니다.');
      const csrf=get('csrf');
      const payload=stored?{schema:root.dataset.schema,name:stored.name,contract:JSON.stringify(stored),inputs:JSON.stringify(inputs),confirmed:true}:{schema:root.dataset.schema,spec:loaded,inputs:JSON.stringify(inputs),confirmed:true};
      const result=await api(stored?'/run-stored':'/run',{method:'POST',headers:{'Content-Type':'application/json',[csrf.dataset.header]:csrf.value},body:JSON.stringify(payload)});
      const pretty=value=>{try{return JSON.stringify(JSON.parse(value),null,2);}catch{return value;}};
      get('output').textContent=pretty(result.output);get('raw').textContent=pretty(result.trace);get('trace').replaceChildren();
      try{const parsed=JSON.parse(result.trace);renderFlow(get('trace-diagram'),traceGraph(parsed));get('trace').append(traceTree(parsed.roots));}catch{get('trace-diagram').replaceChildren();get('trace').append(el('p','Trace 원문을 확인하세요.'));}
      get('results').hidden=false;get('results').scrollIntoView({behavior:'smooth',block:'start'});message('DB 실행 응답을 받았습니다. 결과와 실행 경로를 확인하세요.');
    }catch(error){message(error.message);}finally{busy=false;get('confirm').checked=false;controls();}
  });
  api('/capability?'+new URLSearchParams({schema:root.dataset.schema})).then(data=>{supported=data.supported;catalogSupported=data.catalog;storedSupported=data.stored;get('status').textContent=`파일 실행: ${supported?'지원 확인':'미확인'} · 등록 목록 조회: ${catalogSupported?'지원 확인':'미확인'} · 이름으로 실행: ${storedSupported?'지원 확인':'미확인'}`;controls();if(catalogSupported)refreshAgents();else get('catalog-status').textContent='이 DB에서 목록·상세 조회 API를 확인하지 못했습니다.';}).catch(error=>{get('status').textContent=error.message;});
}
if(typeof document!=='undefined')document.querySelectorAll('[data-engine]').forEach(mountEngine);
