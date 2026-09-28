package com.dbcompanion.model;

import java.time.Instant;
import java.util.*;

/** Explicitly saved Select AI issue reports. These records never execute SQL or call AI. */
public final class ProblemQuestion {
    private ProblemQuestion() {}
    public static final String TYPE="SELECT_AI_PROBLEM", ATTEMPT_TYPE="SELECT_AI_ATTEMPT";
    public static final int MAX_TEXT=16_000, MAX_DESCRIPTION=32_000, MAX_PAYLOAD=300_000;
    public enum Status { RECEIVED, UNDER_REVIEW, REVIEWED, ON_HOLD }
    public record Create(String question,String description,String expected,String expectedSql,Status status) {}
    public record Parent(int version,String id,String question,String description,String expected,String expectedSql,Status status,boolean retained,Instant createdAt,Instant updatedAt) {}
    /** Value plus the fact of how and when it was obtained; blank never means available. */
    public record Snapshot(String value,String availability,Instant checkedAt) {}
    public record Attempt(int version,String id,String parentId,String input,String conditions,String response,String sql,String error,String profile,String model,String options,String prompt,String metadata,String feedback,String snapshotKind,String availability,Instant capturedAt,long elapsedMillis,Snapshot optionSnapshot,Snapshot promptSnapshot,Snapshot metadataSnapshot,Snapshot feedbackSnapshot) {}
    public record Update(Status status,boolean retained) {}
    public record Summary(String seq,String id,String question,Status status,boolean retained,String recordedAt,String updatedAt,int attempts) {}
    public record Page(List<Summary> rows,String next){public Page{rows=List.copyOf(rows);}}
    public record Detail(Parent parent,List<Attempt> attempts) {public Detail{attempts=List.copyOf(attempts);}}
    public record DeletePreview(List<String> ids,int records,int attempts,String fingerprint){public DeletePreview{ids=List.copyOf(ids);}}
    public static String text(String value,int maximum,String key){if(value==null||value.isBlank()||value.length()>maximum||value.indexOf('\0')>=0)throw new AiAssistant.Failure(400,key,"입력값을 확인해 주세요.");return mask(value);}
    public static String optional(String value,int maximum,String key){if(value==null)return "";if(value.length()>maximum||value.indexOf('\0')>=0)throw new AiAssistant.Failure(400,key,"입력값을 확인해 주세요.");return mask(value);}
    /**
     * Defensive, representation-independent redaction for every persisted diagnostic
     * field.  Browser redaction is only presentation protection; storage must not
     * rely on it.  Match whole quoted or line values so spaces and JSON escapes do
     * not preserve a suffix of a credential.
     */
    public static String mask(String value){
        if(value==null)return "";
        String key="(?:password|passwd|secret|token|credential|private[ _-]?key|wallet|authorization|cookie|api[ _-]?key|oracle[ _-]?(?:password|wallet(?:[ _-]?path)?))";
        return value
                .replaceAll("(?is)-----BEGIN [^-]*(?:PRIVATE KEY|CERTIFICATE)[^-]*-----.*?-----END [^-]*(?:PRIVATE KEY|CERTIFICATE)[^-]*-----","[REDACTED PEM]")
                .replaceAll("(?im)^(\\s*(?:authorization|cookie)\\s*[:=]\\s*).*$","$1[REDACTED]")
                .replaceAll("(?i)(\\\""+key+"\\\"\\s*:\\s*)\\\"(?:\\\\.|[^\\\"\\\\])*\\\"","$1\\\"[REDACTED]\\\"")
                .replaceAll("(?im)(^\\s*"+key+"\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\r\\n]*)","$1[REDACTED]");
    }
    public static Create create(Create value){if(value==null)throw new AiAssistant.Failure(400,"problemQuestion.invalid","문제 질문 정보를 확인해 주세요.");return new Create(text(value.question(),MAX_TEXT,"problemQuestion.question"),text(value.description(),MAX_DESCRIPTION,"problemQuestion.description"),text(value.expected(),MAX_DESCRIPTION,"problemQuestion.expected"),optional(value.expectedSql(),MAX_DESCRIPTION,"problemQuestion.sql"),value.status()==null?Status.RECEIVED:value.status());}
    public static String id(String value){try{return UUID.fromString(value).toString();}catch(RuntimeException ex){throw new AiAssistant.Failure(400,"problemQuestion.invalid","문제 질문 정보를 확인해 주세요.");}}
}
