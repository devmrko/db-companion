package com.dbcompanion.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static com.dbcompanion.model.DdsManagement.*;

/** Local principals and explicit role assignments; never carries a password in a preview. */
public final class DdsIdentity {
    private DdsIdentity() {}
    public record State(List<Map<String,String>> users,List<Map<String,String>> roles,
                        List<Map<String,String>> assignments,Set<String> privileges) {}
    public record Draft(String action,String username,String role,boolean passwordAuthentication) {}
    public record Request(Draft draft,String revision) {}
    public record Preview(String token,String confirmation,String sql,String revision,Draft draft) {}
    public record Apply(String token,String confirmation,boolean consent,String password) {
        @Override public String toString(){return "DdsIdentity.Apply[redacted]";}
    }
    public static boolean assigned(State state,Draft d) {
        return state.assignments().stream().anyMatch(r->d.username().equals(r.get("GRANTEE"))
                && d.role().equals(r.get("DATA_ROLE")) && "END USER".equals(r.get("GRANTEE_TYPE")));
    }
    public static String sql(Draft d,State state) {
        require(d!=null&&Set.of("CREATE","GRANT","REVOKE").contains(Objects.toString(d.action(),"")),"invalid");
        String user=id(d.username());
        boolean exists=state.users().stream().anyMatch(r->d.username().equals(r.get("USERNAME")));
        if(d.action().equals("CREATE")) {
            require(state.privileges().contains("CREATE END USER"),"endUserPrivilege");
            require(!exists,"exists");
            return "CREATE END USER "+user+(d.passwordAuthentication()?" IDENTIFIED BY <password supplied at apply>":"");
        }
        require(!d.passwordAuthentication(),"invalid");
        require(state.privileges().contains("GRANT ANY DATA ROLE"),"assignmentPrivilege");
        require(exists,"missing");id(d.role());
        require(state.roles().stream().anyMatch(r->d.role().equals(r.get("DATA_ROLE"))
                && Objects.toString(r.get("MAPPED_TO"),"").isBlank()),"localAssignment");
        boolean grant=d.action().equals("GRANT");
        require(grant?!assigned(state,d):assigned(state,d),grant?"exists":"missing");
        return (grant?"GRANT":"REVOKE")+" DATA ROLE "+id(d.role())+(grant?" TO ":" FROM ")+user;
    }
    public static String executable(Draft d,State state,String password) {
        String sql=sql(d,state);
        if(d.action().equals("CREATE")&&d.passwordAuthentication()) {
            require(password!=null&&password.length()>=12&&password.length()<=128
                    && password.chars().allMatch(c->c>=33&&c<=126&&c!='"'),"passwordInvalid");
            return "CREATE END USER "+id(d.username())+" IDENTIFIED BY \""+password+"\"";
        }
        require(password==null||password.isEmpty(),"invalid");return sql;
    }
    public static void verify(Draft d,State state) {
        if(d.action().equals("CREATE")) {
            var users=state.users().stream().filter(r->d.username().equals(r.get("USERNAME"))).toList();
            require(users.size()==1&&"OPEN".equals(users.getFirst().get("ACCOUNT_STATUS"))
                    && (d.passwordAuthentication()?"PASSWORD":"NONE").equals(users.getFirst().get("AUTHENTICATION_TYPE")),"verifyFailed");
        } else require(assigned(state,d)==d.action().equals("GRANT"),"verifyFailed");
    }
}
