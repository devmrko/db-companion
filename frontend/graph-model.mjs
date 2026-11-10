export const nodeId = (schema, name) => JSON.stringify([schema, name]);
export const confirmed = r => r.status === 'APPROVED' ||
  r.status === 'FK' && r.key?.status === 'ENABLED' && r.key?.validated === 'VALIDATED';

export function graphModel(data, {candidates = false, reveal = null} = {}) {
  const relations = data.relations.filter(r => r.status !== 'REJECTED' && r.target && r.targetSchema &&
    (confirmed(r) || candidates || r.id === reveal));
  const nodes = new Map(data.tables.map(table => [nodeId(data.schema, table.name), {
    id: nodeId(data.schema, table.name), type: 'table', position: {x: 0, y: 0},
    data: {title: table.concept || table.name, name: table.name, columns: [], boundary: false}
  }]));
  const edges = [];
  for (const r of relations) {
    const source = nodeId(data.schema, r.source), target = nodeId(r.targetSchema, r.target);
    if (!nodes.has(source)) continue;
    if (!nodes.has(target)) nodes.set(target, {id: target, type: 'table', position: {x: 0, y: 0},
      data: {title: r.target, name: r.targetSchema + '.' + r.target, columns: [], boundary: true}});
    for (const [id, columns] of [[source, r.sourceColumns], [target, r.targetColumns]])
      nodes.get(id).data.columns = [...new Set([...nodes.get(id).data.columns, ...(columns || [])])];
    edges.push({id: r.id, source, target, type: 'smoothstep', label: r.label || r.key?.name || r.status,
      data: {status: r.status, confirmed: confirmed(r)},
      style: {stroke: confirmed(r) ? '#738a86' : '#b68748', strokeWidth: 1.5,
        strokeDasharray: confirmed(r) ? undefined : '5 4'},
      markerEnd: {type: 'arrowclosed', color: confirmed(r) ? '#738a86' : '#b68748'}});
  }
  for (const node of nodes.values()) {
    node.width = 238;
    node.height = 74 + Math.min(node.data.columns.length, 3) * 22 + (node.data.columns.length > 3 ? 22 : 0);
  }
  return {nodes: [...nodes.values()], edges};
}

export async function layoutGraph(model, elk) {
  if (!model.nodes.length) return model;
  const result = await elk.layout({id: 'root', layoutOptions: {
    'elk.algorithm': 'layered', 'elk.direction': 'RIGHT',
    'elk.spacing.nodeNode': '45', 'elk.layered.spacing.nodeNodeBetweenLayers': '110',
    'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
    'elk.separateConnectedComponents': 'true'
  }, children: model.nodes.map(n => ({id: n.id, width: n.width, height: n.height})),
  edges: model.edges.map(e => ({id: e.id, sources: [e.source], targets: [e.target]}))});
  const positions = new Map(result.children.map(n => [n.id, {x: n.x, y: n.y}]));
  return {...model, nodes: model.nodes.map(n => ({...n, position: positions.get(n.id)}))};
}

export function highlight(model, selected) {
  if (!selected) return model;
  const adjacent = new Set([selected]);
  for (const e of model.edges) if (e.id === selected || e.source === selected || e.target === selected) {
    adjacent.add(e.source); adjacent.add(e.target); adjacent.add(e.id);
  }
  return {nodes: model.nodes.map(n => ({...n, selected: n.id === selected,
    style: {...n.style, opacity: adjacent.has(n.id) ? 1 : .3}})),
  edges: model.edges.map(e => ({...e, selected: e.id === selected,
    style: {...e.style, opacity: adjacent.has(e.id) ? 1 : .15, strokeWidth: adjacent.has(e.id) ? 2 : 1}}))};
}
