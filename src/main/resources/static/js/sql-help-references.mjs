// Explicit operation references; custom app storage is not an Oracle dictionary API.
const ref=name=>`https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/${name}.html`;
const sql=name=>`https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/${name}.html`;
const adb=name=>`https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/${name}.html`;
const app=anchor=>({doc:`/help/sql-reference.html#${anchor}`,docKind:'project'});
const agentViews=adb('dbms-cloud-ai-agent-views');
const agentApi=adb('dbms-cloud-ai-agent-package');
const references={
  tables:{list:ref('ALL_TAB_COLUMNS'),comment:sql('COMMENT'),'column-comment':sql('COMMENT'),
    annotation:sql('ALTER-TABLE'),'column-annotation':sql('ALTER-TABLE'),'annotation-replace':sql('ALTER-TABLE'),
    'column-annotation-add':sql('ALTER-TABLE'),history:app('metadata-history'),'history-delegated':app('metadata-history')},
  credentials:{list:ref('USER_CREDENTIALS'),references:adb('dbms-cloud-ai-views')},
  feedback:{list:adb('select-ai-feedback')},
  agents:{list:agentViews,'task-definition':agentViews,'create-team':agentApi,'create-agent':agentApi,'create-task':agentApi,'create-tool':agentApi,edit:agentApi},
  executions:{'saved-tests':app('app-records'),cache:ref('V-SQL'),awr:ref('DBA_HIST_SQLTEXT'),audit:ref('UNIFIED_AUDIT_TRAIL'),'agent-runs':adb('dbms-cloud-ai-agent-views-history')},
  scheduler:{jobs:ref('ALL_SCHEDULER_JOBS'),runs:ref('ALL_SCHEDULER_JOB_RUN_DETAILS')},
  external:{tables:ref('ALL_EXTERNAL_TABLES'),links:ref('ALL_DB_LINKS'),acl:ref('USER_HOST_ACES')},
  security:{roles:ref('DBA_DATA_ROLES'),assignments:ref('DBA_DATA_ROLE_GRANTS'),grants:ref('DBA_DATA_GRANTS'),applications:ref('DBA_APPLICATION_IDENTITIES')},
  functions:{list:ref('ALL_PROCEDURES'),arguments:ref('ALL_ARGUMENTS'),source:ref('ALL_SOURCE')},
  vectors:{columns:ref('ALL_TAB_COLUMNS'),embedding:sql('vector_embedding'),search:sql('vector_distance')},
  ontology:{current:app('ontology'),history:app('ontology'),append:app('ontology'),metadata:ref('ALL_TAB_COMMENTS'),'discovery-history':app('discovery')},
  'ontology-terms':{approved:app('ontology'),text:app('ontology'),lexer:'https://docs.oracle.com/en/database/oracle/oracle-database/26/ccref/ctx_ddl-package.html'},
  'ontology-drift':{compare:app('ontology'),append:app('ontology')},
  'ontology-query':{'join-rows':app('ontology'),archive:app('app-records')},
  assistant:{profiles:adb('dbms-cloud-ai-views')},
  'problem-questions':{list:app('app-records'),attempts:app('app-records'),save:app('app-records')},
  'ai-batch':{attempts:app('app-records')},
  'ai-test':{'result-review':app('result-review')},
  ords:{publish:'https://docs.oracle.com/en/database/oracle/oracle-rest-data-services/25.3/orddg/ORDS-reference.html',
    'module-path':'https://docs.oracle.com/en/database/oracle/oracle-rest-data-services/25.3/orddg/ORDS-reference.html'}
};
export function referenceFor(feature,operation){
  const entry=references[feature]?.[operation]||(['profiles','feedback','agents'].includes(feature)&&operation==='history'?app('ai-history'):null);
  if(!entry)throw new Error(`Missing SQL help reference: ${feature}/${operation}`);
  return typeof entry==='string'?{doc:entry,docKind:'oracle'}:entry;
}
