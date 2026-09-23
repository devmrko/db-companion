package com.dbcompanion.model;

import java.util.List;

public final class FunctionCatalog {
    private FunctionCatalog() {}
    public record Entry(String object, String member, String name, String reference) {}
    public record ObjectState(String type, String status, String modified) {}
    public record Argument(int subprogramId, String overload, int position, String name,
                           String type, String direction, boolean defaulted) {}
    public record Detail(RoutineSource.Definition definition, List<ObjectState> objects, List<Argument> arguments) {
        public Detail { objects=List.copyOf(objects);arguments=List.copyOf(arguments); }
    }
    public static String reference(String owner,String object,String member) {
        return quote(owner)+"."+quote(object)+(member==null?"":"."+quote(member));
    }
    private static String quote(String value) {
        if(value==null||value.isEmpty()||value.indexOf(0)>=0)throw new IllegalArgumentException("Invalid object identifier");
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status,String message){super(message);this.status=status;}
        public int status(){return status;}
    }
}
