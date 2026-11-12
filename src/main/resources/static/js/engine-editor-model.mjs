const clone=value=>structuredClone(value);
export const reference=id=>({$component_ref:id});
export const nodeTypes=['StartNode','ToolNode','BranchingNode','ParallelFlowNode','EndNode'];
export function resolve(flow,value){return value?.$component_ref?flow.$referenced_components?.[value.$component_ref]:value;}
export function nodes(flow){return (flow.nodes||[]).map(n=>resolve(flow,n));}
export function editableFlow(value){
  const flow=clone(value);flow.$referenced_components??={};const aliases=new Map();
  for(const entry of flow.nodes||[]){const node=resolve(flow,entry);if(!node)throw Error('노드 참조를 찾을 수 없습니다.');
    const key=entry.$component_ref||node.id;if(!entry.$component_ref&&Object.hasOwn(flow.$referenced_components,key))throw Error('인라인 노드와 참조 ID가 충돌합니다.');
    aliases.set(node.id,key);flow.$referenced_components[key]=node;
    if(node.component_type==='ParallelFlowNode')node.subflows=(node.subflows||[]).map(sub=>editableFlow(resolve(flow,sub)||sub));
  }
  const ref=value=>{const id=resolve(flow,value)?.id;return aliases.has(id)?reference(aliases.get(id)):value;};
  flow.nodes=flow.nodes.map(ref);flow.start_node=ref(flow.start_node);
  for(const edge of flow.control_flow_connections||[]){edge.from_node=ref(edge.from_node);edge.to_node=ref(edge.to_node);}
  for(const edge of flow.data_flow_connections||[]){edge.source_node=ref(edge.source_node);edge.destination_node=ref(edge.destination_node);}
  return flow;
}
export function newFlow(id='flow'){
  const field={title:'message',type:'string'};
  const start={component_type:'StartNode',id:id+'-start',name:'입력',inputs:[field],outputs:[field],branches:['next']};
  const end={component_type:'EndNode',id:id+'-end',name:'출력',inputs:[field],outputs:[field],branches:[],branch_name:'next'};
  const flow={component_type:'Flow',id,name:id,agentspec_version:'25.4.2',inputs:[field],outputs:[field],nodes:[start,end],start_node:reference(start.id),control_flow_connections:[],data_flow_connections:[],$referenced_components:{}};
  flow.$referenced_components[start.id]=start;flow.$referenced_components[end.id]=end;flow.nodes=[reference(start.id),reference(end.id)];
  connect(flow,'control',start.id,end.id,'next');connect(flow,'data',start.id,end.id,'message','message');return flow;
}
export function updateNode(flow,node){
  if(!node.id||!nodeTypes.includes(node.component_type))throw Error('노드 ID와 지원 유형을 확인하세요.');
  const existing=nodes(flow).find(n=>n?.id===node.id);
  flow.$referenced_components??={};
  const entry=(flow.nodes||[]).find(n=>resolve(flow,n)?.id===node.id);
  if(entry?.$component_ref)flow.$referenced_components[entry.$component_ref]=clone(node);
  else if(existing)flow.nodes[flow.nodes.indexOf(entry)]=clone(node);
  else {if(Object.hasOwn(flow.$referenced_components,node.id))throw Error('이미 사용 중인 컴포넌트 ID입니다.');flow.$referenced_components[node.id]=clone(node);flow.nodes.push(reference(node.id));}
  if(node.component_type==='StartNode'){flow.start_node=entry||reference(node.id);flow.inputs=clone(node.inputs);}
  if(node.component_type==='EndNode'&&existing)flow.outputs=clone(node.outputs);
}
export function removeNode(flow,id){
  const original=flow;flow=clone(flow);
  const is=value=>resolve(flow,value)?.id===id||value?.$component_ref===id;
  const removed=flow.nodes.find(is),key=removed?.$component_ref;
  flow.control_flow_connections=(flow.control_flow_connections||[]).filter(e=>!is(e.from_node)&&!is(e.to_node));
  flow.data_flow_connections=(flow.data_flow_connections||[]).filter(e=>!is(e.source_node)&&!is(e.destination_node));
  if(is(flow.start_node))flow.start_node=null;
  flow.nodes=flow.nodes.filter(n=>!is(n));
  if(key){
    const remaining=clone(flow);delete remaining.$referenced_components[key];
    const referenced=value=>value&&typeof value==='object'&&(value.$component_ref===key||Object.values(value).some(referenced));
    if(referenced(remaining))throw Error('다른 하위 흐름이 이 노드를 참조하고 있습니다. 먼저 참조를 해제하세요.');
    delete flow.$referenced_components[key];
  }
  Object.assign(original,flow);
}
function refFor(flow,id){const entry=flow.nodes.find(n=>resolve(flow,n)?.id===id);if(!entry)throw Error('노드를 선택하세요.');return entry.$component_ref?clone(entry):clone(entry);}
export function connect(flow,kind,from,to,port,target){
  const a=nodes(flow).find(n=>n?.id===from),b=nodes(flow).find(n=>n?.id===to);
  if(!a||!b)throw Error('출발·도착 노드를 선택하세요.');
  const id=crypto.randomUUID();
  if(kind==='control'){
    if(!(a.branches||[]).includes(port))throw Error('출발 분기를 선택하세요.');
    if((flow.control_flow_connections||[]).some(e=>resolve(flow,e.from_node)?.id===from&&e.from_branch===port))throw Error('이 분기는 이미 연결되어 있습니다. 기존 연결을 삭제한 뒤 다시 연결하세요.');
    flow.control_flow_connections??=[];flow.control_flow_connections.push({component_type:'ControlFlowEdge',id,name:id,from_node:refFor(flow,from),from_branch:port,to_node:refFor(flow,to)});
  }else{
    const output=a.outputs?.find(f=>f.title===port),input=b.inputs?.find(f=>f.title===target);
    if(!output||!input||!(output.type===input.type||output.type==='integer'&&input.type==='number'))throw Error('출력·입력 이름과 타입이 일치해야 합니다.');
    flow.data_flow_connections??=[];flow.data_flow_connections.push({component_type:'DataFlowEdge',id,name:id,source_node:refFor(flow,from),source_output:port,destination_node:refFor(flow,to),destination_input:target});
  }
}
export function validateFlow(flow,depth=0){
  if(depth>12)return ['하위 흐름 깊이 제한(12)을 초과했습니다.'];
  const errors=[],list=nodes(flow),ids=list.map(n=>n?.id),edges=flow.control_flow_connections||[];
  if(list.some(n=>!n)||new Set(ids).size!==ids.length)errors.push('노드 참조 또는 중복 ID를 확인하세요.');
  if(list.filter(n=>n?.component_type==='StartNode').length!==1||!resolve(flow,flow.start_node))errors.push('시작 노드는 하나여야 합니다.');
  if(!list.some(n=>n?.component_type==='EndNode'))errors.push('출력 노드가 필요합니다.');
  for(const node of list.filter(Boolean)){
    for(const fields of [node.inputs||[],node.outputs||[]])if(fields.some(f=>!f.title||!['string','number','integer','boolean','object','array'].includes(f.type))||new Set(fields.map(f=>f.title)).size!==fields.length)errors.push(`${node.name}: 입출력 이름·타입을 확인하세요.`);
    for(const branch of node.branches||[])if(edges.filter(e=>resolve(flow,e.from_node)?.id===node.id&&e.from_branch===branch).length!==1)errors.push(`${node.name}: ${branch} 분기는 하나의 경로에 연결해야 합니다.`);
    if(node.component_type==='ToolNode'){
      const tool=resolve(flow,node.tool),c=tool?.configuration;
      if(tool?.tool_type==='plsql_exec'&&(!c?.schema_name||!c?.procedure_name))errors.push(`${node.name}: PL/SQL 소유자·프로시저가 필요합니다.`);
      if(!tool)errors.push(`${node.name}: 도구가 필요합니다.`);
    }
    if(node.component_type==='BranchingNode'&&Object.values(node.mapping||{}).some(b=>!node.branches?.includes(b)))errors.push(`${node.name}: 조건에 지정한 분기를 확인하세요.`);
    if(node.component_type==='ParallelFlowNode'){
      if(!node.subflows?.length)errors.push(`${node.name}: 병렬 하위 흐름이 필요합니다.`);
      for(const sub of node.subflows||[])errors.push(...validateFlow(resolve(flow,sub)||{},depth+1).map(e=>`${node.name}: ${e}`));
    }
  }
  for(const edge of edges)if(!ids.includes(resolve(flow,edge.from_node)?.id)||!ids.includes(resolve(flow,edge.to_node)?.id))errors.push('존재하지 않는 노드의 연결입니다.');
  for(const edge of flow.data_flow_connections||[]){const a=resolve(flow,edge.source_node),b=resolve(flow,edge.destination_node),output=a?.outputs?.find(f=>f.title===edge.source_output),input=b?.inputs?.find(f=>f.title===edge.destination_input);if(!ids.includes(a?.id)||!ids.includes(b?.id)||!output||!input)errors.push('입출력 매핑의 노드·필드가 없습니다.');else if(!(output.type===input.type||output.type==='integer'&&input.type==='number'))errors.push('입출력 매핑의 타입이 일치하지 않습니다.');}
  const reachable=new Set(),start=resolve(flow,flow.start_node)?.id;if(start)reachable.add(start);
  for(let i=0;i<list.length;i++)for(const edge of edges)if(reachable.has(resolve(flow,edge.from_node)?.id))reachable.add(resolve(flow,edge.to_node)?.id);
  for(const node of list.filter(Boolean)){
    if(!reachable.has(node.id))errors.push(`${node.name}: 시작 노드에서 도달할 수 없습니다.`);
    if(node.component_type!=='StartNode'&&Array.isArray(flow.data_flow_connections))for(const field of node.inputs||[]){
      if(field.default===undefined&&!flow.data_flow_connections.some(e=>resolve(flow,e.destination_node)?.id===node.id&&e.destination_input===field.title))errors.push(`${node.name}.${field.title}: 입력 매핑이 필요합니다.`);
    }
  }
  return [...new Set(errors)];
}
