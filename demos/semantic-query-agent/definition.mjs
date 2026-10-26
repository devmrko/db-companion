// Generic definitions. Names/profiles are explicit deployment inputs, not model inputs.
export const names = Object.freeze({
  function: 'DBC_SEMANTIC_QUERY', tool: 'DBC_SEMANTIC_QUERY_TOOL',
  agent: 'DBC_SEMANTIC_QUERY_AGENT', task: 'DBC_SEMANTIC_QUERY_TASK',
  team: 'DBC_SEMANTIC_QUERY_TEAM',
});
export function definitions({owner, agentProfile}) {
  for (const value of [owner, agentProfile]) {
    if (!/^[A-Z][A-Z0-9_]{0,127}$/.test(value)) throw Error('Invalid deployment identifier');
  }
  const items = [
    {kind:'TOOL', name:names.tool, attributes:{
      function:`${owner}.${names.function}`,
      instruction:'Query actual database data from the original natural-language question. The function ALWAYS searches the business glossary, optionally reads selected approved ontology, generates SQL once and executes that same SQL. Default ontology OFF (0) and tables []. Do not send SQL. Return the actual JSON including status, evidence versions, SQL, columns, rows and more. Errors are not empty/zero results. Never retry a failed or uncertain call.',
      tool_inputs:[
        {name:'p_question',description:'Complete original user data question in its original language. Preserve all dates, metrics, numbers and filters. Not SQL.'},
        {name:'p_use_ontology',description:'0 by default; 1 only when user explicitly requests ontology. Never silently switch 1 to 0 on an error.'},
        {name:'p_tables_json',description:'A JSON array encoded as a string: [] when ontology is OFF; otherwise 1-10 exact user-selected approved table/view names within the configured profile. Do not guess names.'},
      ],
    }},
    {kind:'AGENT', name:names.agent, attributes:{
      profile_name:agentProfile,
      role:'You retrieve business data ONLY through DBC_SEMANTIC_QUERY_TOOL and explain its observed result. Never invent business numbers, claim unused evidence, or execute SQL through another path. Use the Oracle agent framework action format.',
      enable_human_tool:false, short_term_memory_length:30,
    }},
    {kind:'TASK', name:names.task, attributes:{
      instruction:'Handle this user request: {query}. For a data question call DBC_SEMANTIC_QUERY_TOOL exactly once with the complete original data question. Default p_use_ontology=0 and p_tables_json="[]". If ontology is explicitly requested, use p_use_ontology=1 and only explicitly selected table names; if missing, ask the user for them in the final answer without calling a tool. Do not disable ontology to bypass an error. Use Action and Action Input to invoke the tool; never write a fabricated Observation. Never answer data questions from memory. After the actual tool Observation, stop tool use and produce Final Answer in the user language. For status SUCCESS, present returned rows without recalculating/changing numbers, mention the actual evidence terms and ontology use, and show SQL when requested. If result.more=true, clearly state the 1000-row limit and do not claim a full aggregate/chart. Never interpret decimal strings as categorical values solely due to JSON encoding, and never convert null to zero. For status ERROR or framework tool error, explain the observed error and stop; no retries, alternate SQL, or invented results. Do not create charts or claim a chart exists merely by describing one. This task retrieves grounded data; the client handles visualization. Finish using Thought: I now have all the information needed to answer the question. then Final Answer: followed by the observed answer.',
      tools:[names.tool], enable_human_tool:false,
    }},
    {kind:'TEAM', name:names.team, attributes:{
      agents:[{name:names.agent,task:names.task}], process:'sequential',
    }},
  ];
  items[2].attributes.instruction = items[2].attributes.instruction
    .replace('present returned rows without recalculating/changing numbers, mention the actual evidence terms and ontology use, and show SQL when requested.',
      'give a short answer of at most 5 rows without recalculating/changing numbers. The result is a bounded preview, not all matching data. Mention actual evidence terms and ontology use only if supplied. Full fetched rows and SQL are in the client saved-result panel only when recovery=SAVED; otherwise explicitly say recovery is unavailable. Do not reproduce a long table or invent SQL. Never infer totals from preview rows.')
    .replace('clearly state the 1000-row limit', 'clearly state that the saved data is also limited to 1000 rows');
  return items;
}
