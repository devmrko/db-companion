package com.dbcompanion.model;

import java.util.Arrays;

/** An explicit analysis language, independent of UI translation and business dictionaries. */
public enum QuestionLanguage {
    KO("ko","한국어","KOREAN_MORPH_LEXER","KO"),
    EN("en","English","BASIC_LEXER","EN"),
    JA("ja","日本語","JAPANESE_LEXER","JA"),
    ZH("zh-CN","简体中文","CHINESE_LEXER","ZH");
    private final String code,label,lexer,suffix;
    QuestionLanguage(String code,String label,String lexer,String suffix){this.code=code;this.label=label;this.lexer=lexer;this.suffix=suffix;}
    public String code(){return code;}
    public String label(){return label;}
    public String lexer(){return lexer;}
    public String preference(){return "DBC_QA_"+suffix+"_LEXER";}
    public String policy(){return "DBC_QA_"+suffix+"_POLICY";}
    public static QuestionLanguage of(String code){return Arrays.stream(values()).filter(v->v.code.equals(code)).findFirst().orElseThrow(BusinessGlossary::invalid);}
    public static QuestionLanguage forPolicy(String policy){
        if("DBC_BT_KO_POLICY".equals(policy))return KO;
        return Arrays.stream(values()).filter(v->v.policy().equals(policy)).findFirst().orElseThrow(BusinessGlossary::invalid);
    }
}
