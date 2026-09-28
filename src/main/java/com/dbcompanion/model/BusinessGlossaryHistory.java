package com.dbcompanion.model;

import java.util.List;
import java.util.Objects;
import com.dbcompanion.model.BusinessGlossary.Term;

/** App-authored snapshots, not an audit of direct SQL changes or a reconstructed past. */
public final class BusinessGlossaryHistory {
    private BusinessGlossaryHistory() {}
    public static final String TYPE="BUSINESS_TERM_HISTORY";
    public record Change(int format,String termId,String operation,Term before,Term after){
        public Change {
            BusinessGlossary.id(termId);Objects.requireNonNull(after);
            if(format!=1||!termId.equals(after.id())||after.revision()<1)throw BusinessGlossary.invalid();
            after.draft();
            if(before==null){if(!"CREATE".equals(operation)||after.revision()!=1)throw BusinessGlossary.invalid();}
            else{
                before.draft();
                if(!"UPDATE".equals(operation)||!termId.equals(before.id())||after.revision()!=before.revision()+1)throw BusinessGlossary.invalid();
            }
        }
    }
    public record Entry(String seq,String id,String actor,String recordedAt,Change change) {}
    public record Page(List<Entry> entries,String next){public Page{entries=List.copyOf(entries);}}
}
