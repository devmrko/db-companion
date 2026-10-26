package com.dbcompanion.common.exception;

import com.dbcompanion.common.db.OracleErrorDetails;
import com.dbcompanion.common.i18n.UiMessages;
import java.sql.SQLTimeoutException;
import java.util.Map;

/** Sanitized runtime boundary: never retain SQL, bind values, secrets or the raw cause. */
public final class ObjectCreationException extends RuntimeException {
    public enum Code { INVALID_INPUT, DUPLICATE, ACCESS_DENIED, REFERENCE_INVALID, TIMEOUT, DATABASE_ERROR }
    private final Code code;
    private final int status,oracleCode;
    private final String stage;
    private final boolean objectMayExist;
    private ObjectCreationException(Code code,int status,int oracleCode,String stage,boolean objectMayExist,String message){
        super(message);this.code=code;this.status=status;this.oracleCode=oracleCode;this.stage=stage;this.objectMayExist=objectMayExist;
    }
    public static ObjectCreationException from(Throwable ex,String stage,boolean submitted){
        if(ex instanceof ObjectCreationException value)return value;
        int oracle=OracleErrorDetails.firstCode(ex);
        Code code;int status;
        if(oracle==955||oracle==20022){code=Code.DUPLICATE;status=409;}
        else if(oracle==1031||oracle==1045){code=Code.ACCESS_DENIED;status=403;}
        else if(oracle==1013||ex instanceof SQLTimeoutException){code=Code.TIMEOUT;status=504;}
        else if(oracle==20050){code=Code.REFERENCE_INVALID;status=409;}
        else if(ex instanceof IllegalArgumentException){code=Code.INVALID_INPUT;status=400;}
        else{code=Code.DATABASE_ERROR;status=503;}
        String message=UiMessages.text("objectError."+code.name(),code.name());
        if(ex instanceof MetadataEditException meta){status=meta.status();message=meta.userMessage();code="exists".equals(meta.getMessage())?Code.DUPLICATE:status==403?Code.ACCESS_DENIED:status==409?Code.REFERENCE_INVALID:Code.INVALID_INPUT;}
        return new ObjectCreationException(code,status,oracle,stage,submitted,message);
    }
    public int status(){return status;}
    public Map<String,Object> response(){return Map.of("error",display(),"code",code.name(),"stage",stage,"oracleCode",oracleCode,"objectMayExist",objectMayExist,"automaticRetry",false);}
    public String display(){return "["+stage+"] "+getMessage()+" · "+UiMessages.text(objectMayExist?"objectError.inspect":"objectError.notSubmitted",objectMayExist?"Inspect objects before retrying":"Creation was not submitted")+(oracleCode==0?"":" (ORA-"+String.format(java.util.Locale.ROOT,"%05d",oracleCode)+")");}
}
