package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.common.db.QuestionAnalysisSql;
import com.dbcompanion.model.QuestionLanguage;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.BusinessGlossary.Analysis;
import com.dbcompanion.model.SelectAiTest;
import com.dbcompanion.repository.QuestionAnalysisRepository;
import java.util.List;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuestionAnalysisService {
    public record Configuration(String owner,String language,String state,String policy,String lexer,String setupSql){}
    private final SessionDataSource source;
    private final QuestionAnalysisRepository repository;
    private final TransactionTemplate read;
    public QuestionAnalysisService(SessionDataSource source,QuestionAnalysisRepository repository){
        this.source=source;this.repository=repository;read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setTimeout(30);
    }
    public Analysis analyze(PoolSession session,String question){
        return analyze(session,question,QuestionLanguage.KO);
    }
    private String policy(String owner,QuestionLanguage language){return language==QuestionLanguage.KO?repository.policy(owner):repository.policy(owner,language);}
    public Configuration configuration(PoolSession session,QuestionLanguage language){
        String owner=session.metadata().info().username();source.bind(session.pool(),owner);
        try{return read.execute(status->{
            String name;
            try{name=policy(owner,language);}catch(AiAssistant.Failure ex){
                if(ex.status()!=409)throw ex;
                return new Configuration(owner,language.code(),"CONFLICT",language.policy(),language.lexer(),"");
            }
            String state=!name.isEmpty()?"READY":repository.hasPreference(language)?"PARTIAL":"MISSING";
            return new Configuration(owner,language.code(),state,name.isEmpty()?language.policy():name,language.lexer(),state.equals("MISSING")?QuestionAnalysisSql.script(language):"");
        });}finally{source.clear();}
    }
    public Analysis analyze(PoolSession session,String question,QuestionLanguage language){
        SelectAiTest.question(question);String owner=session.metadata().info().username();source.bind(session.pool(),owner);
        try{return read.execute(status->{
            String policy=policy(owner,language);
            return new Analysis(policy.isEmpty()?"ORACLE_TEXT_UNCONFIGURED":"ORACLE_TEXT",policy.isEmpty()?List.of():repository.tokens(policy,question),language.code(),policy,language.lexer());
        });}finally{source.clear();}
    }
}
