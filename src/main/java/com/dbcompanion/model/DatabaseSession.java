package com.dbcompanion.model;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.exception.AppException;
import java.util.List;
import java.util.Objects;
import static com.dbcompanion.common.exception.AppException.Code.SCHEMA_NOT_ACCESSIBLE;

/** Login-scoped public metadata. Authentication secrets and connections are not stored here. */
public final class DatabaseSession {
    private final DatabaseInfo info;
    private List<String> schemas;
    private String selectedSchema;
    private final AiAssistant.State assistant = new AiAssistant.State();
    private final AiCreation.State creation = new AiCreation.State();
    private final FeedbackEditing.State feedbackEditing = new FeedbackEditing.State();
    public FeedbackEditing.State feedbackEditing(){return feedbackEditing;}
    public AiCreation.State creation() { return creation; }
    public AiAssistant.State assistant() { return assistant; }
    private final SelectAiTest.State aiTest = new SelectAiTest.State();
    public SelectAiTest.State aiTest() { return aiTest; }
    private final ExternalSources.State externalSources = new ExternalSources.State();
    public ExternalSources.State externalSources() { return externalSources; }
    private final Scheduler.State scheduler = new Scheduler.State();
    public Scheduler.State scheduler() { return scheduler; }
    private final Ontology.State ontology = new Ontology.State();
    public Ontology.State ontology() { return ontology; }
    private final com.dbcompanion.service.OntologyInquiry.State inquiry = new com.dbcompanion.service.OntologyInquiry.State();
    public com.dbcompanion.service.OntologyInquiry.State inquiry() { return inquiry; }
    private final QueryArchive.State queryArchive=new QueryArchive.State();
    public QueryArchive.State queryArchive(){return queryArchive;}
    private final AiSqlHistory.State sqlHistory = new AiSqlHistory.State();
    public AiSqlHistory.State sqlHistory() { return sqlHistory; }
    private record HistoryKey(String schema,String table) {}
    private final java.util.Map<HistoryKey,MetadataHistory.State> historyStates = new java.util.HashMap<>();
    private final java.util.Map<HistoryKey,TableRowHistory.State> rowHistoryStates = new java.util.HashMap<>();
    private final java.util.Map<String,List<VectorSearch.Table>> vectorTables = new java.util.HashMap<>();
    private final java.util.Map<String,List<FunctionCatalog.Entry>> functions = new java.util.HashMap<>();
    private record FunctionKey(String schema,List<String> reference) {
        private FunctionKey { reference=List.copyOf(reference); }
    }
    private final java.util.Map<FunctionKey,List<FunctionCatalog.Detail>> functionDetails = new java.util.HashMap<>();
    private final java.util.Map<String,CredentialCatalog.Catalog> credentials = new java.util.HashMap<>();
    private record CredentialKey(String schema,String name) {}
    private final java.util.Map<CredentialKey,CredentialCatalog.Detail> credentialDetails = new java.util.HashMap<>();
    private record SecurityKey(DeepDataSecurity.Kind kind,String schema) {}
    private final java.util.Map<SecurityKey,DeepDataSecurity.Dataset> security = new java.util.HashMap<>();
    private VectorSearch.Options vectorOptions;
    private record OciModelKey(OciEmbeddingModels.Query query,String page) {}
    private final java.util.LinkedHashMap<OciModelKey,OciEmbeddingModels.Page> ociModels=new java.util.LinkedHashMap<>();

    public DatabaseSession(DatabaseInfo info, List<String> schemas) {
        this.info = Objects.requireNonNull(info);
        this.schemas = List.copyOf(schemas);
        selectSchema(this.schemas.contains(info.schema()) ? info.schema() : info.username());
    }

    public DatabaseInfo info() { return info; }
    public synchronized CredentialCatalog.Catalog credentials(String schema,boolean refresh,
            java.util.function.Supplier<CredentialCatalog.Catalog> loader) {
        if(refresh){credentials.remove(schema);credentialDetails.keySet().removeIf(key->key.schema().equals(schema));}
        if(credentials.containsKey(schema))return credentials.get(schema);
        var data=Objects.requireNonNull(loader.get());if(data.cacheable())credentials.put(schema,data);return data;
    }
    public synchronized CredentialCatalog.Detail credentialDetail(String schema,String name,
            java.util.function.Supplier<CredentialCatalog.Detail> loader) {
        var key=new CredentialKey(schema,name);if(credentialDetails.containsKey(key))return credentialDetails.get(key);
        var data=Objects.requireNonNull(loader.get());if(data.cacheable())credentialDetails.put(key,data);return data;
    }
    public synchronized DeepDataSecurity.Dataset security(DeepDataSecurity.Kind kind,String schema,boolean refresh,
            java.util.function.Supplier<DeepDataSecurity.Dataset> loader) {
        if(refresh)security.clear();
        var key=new SecurityKey(kind,kind==DeepDataSecurity.Kind.grants?schema:"");
        if(security.containsKey(key))return security.get(key);
        var data=Objects.requireNonNull(loader.get());
        if(data.cacheable())security.put(key,data);
        return data;
    }
    public synchronized List<FunctionCatalog.Entry> functions(String schema,boolean refresh,
            java.util.function.Supplier<List<FunctionCatalog.Entry>> loader) {
        if(refresh){
            functions.remove(schema);
            functionDetails.keySet().removeIf(key->key.schema().equals(schema));
        }
        if(!functions.containsKey(schema))functions.put(schema,List.copyOf(loader.get()));
        return functions.get(schema);
    }
    public synchronized List<FunctionCatalog.Detail> functionDetails(String schema,List<String> reference,
            java.util.function.Supplier<List<FunctionCatalog.Detail>> loader) {
        var key=new FunctionKey(schema,reference);
        if(!functionDetails.containsKey(key))functionDetails.put(key,List.copyOf(loader.get()));
        return functionDetails.get(key);
    }
    public synchronized TableRowHistory.State rowHistoryState(String schema,String table,boolean refresh,
            java.util.function.Supplier<TableRowHistory.State> loader) {
        var key=new HistoryKey(schema,table);
        if(refresh||!rowHistoryStates.containsKey(key)) {
            rowHistoryStates.remove(key);
            rowHistoryStates.put(key,Objects.requireNonNull(loader.get()));
        }
        return rowHistoryStates.get(key);
    }
    public synchronized void forgetRowHistory(String schema,String table){rowHistoryStates.remove(new HistoryKey(schema,table));}
    public synchronized void forgetRowHistorySchema(String schema){rowHistoryStates.keySet().removeIf(key->key.schema().equals(schema));}
    public synchronized List<String> schemas() { return schemas; }
    public synchronized String selectedSchema() { return selectedSchema; }

    public synchronized List<VectorSearch.Table> vectorTables(String schema,boolean refresh,
            java.util.function.Supplier<List<VectorSearch.Table>> loader) {
        if(refresh || !vectorTables.containsKey(schema)) vectorTables.put(schema,List.copyOf(loader.get()));
        return vectorTables.get(schema);
    }
    public synchronized VectorSearch.Options vectorOptions(boolean refresh,java.util.function.Supplier<VectorSearch.Options> loader) {
        if(refresh || vectorOptions==null) vectorOptions=Objects.requireNonNull(loader.get());
        return vectorOptions;
    }

    public synchronized OciEmbeddingModels.Result ociModels(OciEmbeddingModels.Request request,
            java.util.function.Supplier<OciEmbeddingModels.Page> loader) {
        if(request.refresh())ociModels.keySet().removeIf(k->k.query().equals(request.query()));
        var key=new OciModelKey(request.query(),request.page());
        if(ociModels.containsKey(key))return new OciEmbeddingModels.Result(ociModels.get(key),true);
        // A caller cannot introduce arbitrary continuation tokens into the SDK.
        if(!request.page().isEmpty()&&ociModels.entrySet().stream().noneMatch(e->e.getKey().query().equals(request.query())
                && e.getValue().nextPage().equals(request.page())))
            throw VectorSearch.invalid(UiMessages.text("vector.oci.pageInvalid","모델 목록 페이지를 다시 조회해 주세요."));
        var value=Objects.requireNonNull(loader.get());
        if(ociModels.size()>=32)ociModels.remove(ociModels.keySet().iterator().next());
        ociModels.put(key,value);return new OciEmbeddingModels.Result(value,false);
    }

    /** Display cache only. Mutations must recheck privileges and objects against the database. */
    public synchronized MetadataHistory.State historyState(String schema,String table,boolean refresh,
            java.util.function.Supplier<MetadataHistory.State> loader) {
        var key = new HistoryKey(schema,table);
        if (refresh || !historyStates.containsKey(key)) {
            try { historyStates.put(key,Objects.requireNonNull(loader.get())); }
            catch(RuntimeException ex) { uncertainHistoryState(schema,table);throw ex; }
        }
        return historyStates.get(key);
    }
    public synchronized MetadataHistory.State rememberHistoryState(String schema,String table,MetadataHistory.State state) {
        historyStates.put(new HistoryKey(schema,table),Objects.requireNonNull(state));return state;
    }
    public synchronized void uncertainHistoryState(String schema,String table) {
        var key=new HistoryKey(schema,table);var previous=historyStates.get(key);
        if(previous!=null)historyStates.put(key,new MetadataHistory.State(previous.installed(),previous.enabled(),false,
                com.dbcompanion.common.i18n.UiNotice.message("ui.14cade0c9fda", "작업 결과 확인 필요 · 상태를 갱신해 주세요."),previous.beforeTrigger(),previous.afterTrigger(),previous.triggerOwner(),false,""));
    }

    public synchronized void selectSchema(String schema) {
        if (schema == null || !schemas.contains(schema)) throw new AppException(SCHEMA_NOT_ACCESSIBLE);
        if(!Objects.equals(selectedSchema,schema)){inquiry.clear();queryArchive.clear();creation.clear();feedbackEditing.clear();}
        selectedSchema = schema;
    }

    public synchronized void refreshSchemas(List<String> schemas) {
        var updated = List.copyOf(schemas);
        var selected = updated.contains(selectedSchema) ? selectedSchema : info.username();
        if (!updated.contains(selected)) throw new AppException(SCHEMA_NOT_ACCESSIBLE);
        if(!Objects.equals(selectedSchema,selected)){inquiry.clear();queryArchive.clear();creation.clear();feedbackEditing.clear();}
        this.schemas = updated;
        this.selectedSchema = selected;
    }
}
