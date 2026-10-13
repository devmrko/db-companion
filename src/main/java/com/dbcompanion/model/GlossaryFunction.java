package com.dbcompanion.model;

import java.time.Instant;
import java.util.List;

public final class GlossaryFunction {
    private GlossaryFunction() {}
    public record Status(String owner,String dictionary,String text,String function,boolean canCreate,
                         List<String> errors,String sql,String example) {
        public boolean installable(){return canCreate&&dictionary.equals("READY")&&text.equals("READY")&&function.equals("MISSING");}
    }
    public record Preview(String token,String owner,String sql,Instant expires) {}
    public record Apply(String token,boolean consent) {}
    public record Question(String question) {
        public Question {
            if(question==null||question.isBlank()||question.length()>8000)throw new IllegalArgumentException("Enter a question of 1 to 8000 characters");
            question=question.strip();
        }
    }
}
