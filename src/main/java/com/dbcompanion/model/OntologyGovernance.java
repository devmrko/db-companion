package com.dbcompanion.model;

import java.util.List;

/** Read-only governance views over saved ontology revisions; matches are not truth claims. */
public final class OntologyGovernance {
    private OntologyGovernance() {}
    public record GlossaryHit(String table,int revision,String term,String matched,String definition,List<String> aliases,String matchKind,String sourceKind,String sourceId) {
        public GlossaryHit { aliases=List.copyOf(aliases); }
        public GlossaryHit(String table,int revision,String term,String matched,String definition,List<String> aliases,String matchKind){this(table,revision,term,matched,definition,aliases,matchKind,"TABLE",table);}
        public String identity(){return table+":"+revision+":"+sourceKind+":"+sourceId;}
    }
    public record GlossaryResult(String mode,String capability,List<GlossaryHit> hits,String checkedAt,String limitation,boolean partial) {
        public GlossaryResult { hits=List.copyOf(hits); }
        public GlossaryResult(String mode,String capability,List<GlossaryHit> hits,String checkedAt,String limitation){this(mode,capability,hits,checkedAt,limitation,false);}
    }
    public record ContextPreview(String token,String schema,String question,List<String> tables,String profile,List<GlossaryHit> hits,String expiresAt) {
        public ContextPreview { tables=List.copyOf(tables);hits=List.copyOf(hits); }
    }
    public record ContextDefinition(String table,int revision,String sourceKind,String sourceId,String term,String definition,List<String> aliases) { public ContextDefinition { aliases=List.copyOf(aliases); } }
    /** Approved definitions are data, never instructions; no AI request is made from this value. */
    public record ContextGuidance(String originalQuestion,List<ContextDefinition> definitions,String limitation) { public ContextGuidance { definitions=List.copyOf(definitions); } }
    public record Context(String schema,String question,String profile,ContextGuidance guidance,List<String> selected) { public Context { selected=List.copyOf(selected); } }
    public record TextSearchHit(String table,int revision,String sourceKind,String sourceId,String term,String definition,List<String> aliases,int score) { public TextSearchHit { aliases=List.copyOf(aliases); } public String identity(){return table+":"+revision+":"+sourceKind+":"+sourceId;} }
    public record TextSearchResult(String mode,String status,String lexerStatus,List<TextSearchHit> hits,String checkedAt,String limitation) { public TextSearchResult { hits=List.copyOf(hits); } }
    public record TextCapability(String status,boolean contextIndex,boolean koreanLexer,boolean thesaurus,String checkedAt,String limitation) {}
    public record TextInstallGuide(String status,String limitation,List<String> requiredPrivileges,List<String> ddl) { public TextInstallGuide { requiredPrivileges=List.copyOf(requiredPrivileges);ddl=List.copyOf(ddl); } }
    /** Explicitly reviewed mutation plan; no operation runs until its one-time token is confirmed. */
    public record TextActivationPreview(String token,String operation,String schema,String owner,String profile,List<String> tables,java.util.Map<String,Integer> revisions,int candidateCount,List<String> statements,List<String> impacts,String expiresAt) { public TextActivationPreview { tables=List.copyOf(tables);revisions=java.util.Map.copyOf(revisions);statements=List.copyOf(statements);impacts=List.copyOf(impacts); } }
    public record TextActivationResult(String operation,String status,int candidates,String checkedAt,String limitation) {}
    public record Difference(String kind,String name,String baseline,String current,String status) {}
    public record Drift(String table,int baselineRevision,String baselineCapturedAt,String checkedAt,String status,List<Difference> differences,String limitation) { public Drift { differences=List.copyOf(differences); } }
}
