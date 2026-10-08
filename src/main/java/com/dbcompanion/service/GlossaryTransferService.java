package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.BusinessGlossary;
import com.dbcompanion.model.GlossaryTransfer;
import com.dbcompanion.repository.BusinessGlossaryHistoryRepository;
import com.dbcompanion.repository.BusinessGlossaryRepository;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GlossaryTransferService {
    private final SessionDataSource source;
    private final BusinessGlossaryRepository repository;
    private final BusinessGlossaryHistoryRepository history;
    private final JsonMapper json;
    private final TransactionTemplate read, write;
    public GlossaryTransferService(SessionDataSource source, BusinessGlossaryRepository repository, BusinessGlossaryHistoryRepository history, JsonMapper json) {
        this.source=source;this.repository=repository;this.history=history;this.json=json;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(30);
        write=new TransactionTemplate(manager);write.setTimeout(60);
    }
    private <T> T query(PoolSession session, boolean writing, Supplier<T> work) {
        source.bind(session.pool(),session.metadata().info().username());
        try { return (writing?write:read).execute(status->work.get()); } finally { source.clear(); }
    }
    public GlossaryTransfer.Document export(PoolSession session) {
        return query(session,false,()->{
            String owner=session.metadata().info().username();repository.requireTable(owner);
            var terms=repository.transferTerms(owner);
            if(terms.size()>500)throw BusinessGlossary.failure(413,"내보내기 한도는 500개입니다. 일부만 내보내지 않습니다.");
            var document=new GlossaryTransfer.Document(GlossaryTransfer.FORMAT,1,terms.stream().map(BusinessGlossary.Term::draft).toList());
            if(json.writeValueAsBytes(document).length>GlossaryTransfer.MAX_BYTES)throw BusinessGlossary.failure(413,"내보낼 파일이 4 MB를 초과합니다. 일부만 내보내지 않습니다.");
            return document;
        });
    }
    public GlossaryTransfer.Preview preview(PoolSession session, String content) {
        if(content==null || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>GlossaryTransfer.MAX_BYTES)throw BusinessGlossary.failure(413,"파일 한도는 4 MB입니다.");
        final GlossaryTransfer.Document document;
        try { document=json.readValue(content,GlossaryTransfer.Document.class); }
        catch(RuntimeException ex) { throw BusinessGlossary.failure(400,"업무 용어 JSON 형식·버전·필수 필드와 중복 용어를 확인해 주세요."); }
        if(document==null)throw BusinessGlossary.invalid();
        return query(session,false,()->{
            String owner=session.metadata().info().username();repository.requireTable(owner);
            return GlossaryTransfer.preview(owner,document,repository.transferTerms(owner));
        });
    }
    public int apply(PoolSession session, GlossaryTransfer.Preview preview, GlossaryTransfer.Apply request) {
        String owner=session.metadata().info().username();
        var entries=GlossaryTransfer.selected(preview,request,owner);
        int count=query(session,true,()->{
            repository.requireTable(owner);history.require(owner);
            repository.lockTransfer(owner);
            List<BusinessGlossary.Term> current=repository.transferTerms(owner);
            for(var entry:entries) {
                var matches=current.stream().filter(t->BusinessGlossary.normalize(t.term()).equals(BusinessGlossary.normalize(entry.value().term()))).toList();
                if(entry.previous()==null ? !matches.isEmpty() : matches.size()!=1 || !matches.getFirst().equals(entry.previous()))throw BusinessGlossary.stale();
            }
            for(var entry:entries) {
                var before=entry.previous();
                var saved=repository.save(owner,before==null?null:before.id(),before==null?0:before.revision(),entry.value());
                history.append(owner,before,saved);
            }
            return entries.size();
        });
        session.metadata().businessGlossary().clear();return count;
    }
}
