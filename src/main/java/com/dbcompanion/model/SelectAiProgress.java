package com.dbcompanion.model;

import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;

/** Session-local timings only: no SQL, questions, credentials, database calls or execution control. */
public final class SelectAiProgress {
    private SelectAiProgress() {}
    public enum Stage { DEFINITIONS, CONNECTION, PROFILE, AI, RESPONSE, QUERY, FETCH, CLEANUP }
    public enum Status { RUNNING, COMPLETE, FAILED }
    public record Step(Stage stage,long elapsedMillis,Status status) {}
    public record Snapshot(String id,String operation,Instant startedAt,long elapsedMillis,Status status,List<Step> steps) {
        public Snapshot { steps=List.copyOf(steps); }
    }
    public static String requestId(String value) {
        if(value==null)return UUID.randomUUID().toString();
        if(!value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new IllegalArgumentException("Invalid progress identifier");
        return value;
    }
    public static final class State {
        private final Deque<Trace> recent=new ArrayDeque<>();
        private final LongSupplier clock;
        public State(){this(System::nanoTime);}
        State(LongSupplier clock){this.clock=clock;}
        public synchronized Trace begin(String id,String operation){
            var trace=new Trace(requestId(id),operation,clock);recent.addFirst(trace);
            while(recent.size()>8)recent.removeLast();
            return trace;
        }
        public synchronized List<Snapshot> snapshots(){return recent.stream().map(Trace::snapshot).toList();}
    }
    public static final class Trace {
        private final String id,operation;
        private final LongSupplier clock;
        private final Instant startedAt=Instant.now();
        private final long started;
        private final List<Step> completed=new ArrayList<>();
        private Stage active;
        private long stepStarted,finished;
        private Status status=Status.RUNNING;
        private Trace(String id,String operation,LongSupplier clock){this.id=id;this.operation=operation;this.clock=clock;this.started=clock.getAsLong();}
        public synchronized void step(Stage stage){
            if(status!=Status.RUNNING)return;
            long now=clock.getAsLong();
            if(active!=null)completed.add(new Step(active,millis(now-stepStarted),Status.COMPLETE));
            active=Objects.requireNonNull(stage);stepStarted=now;
        }
        public synchronized void finish(boolean success){
            if(status!=Status.RUNNING)return;
            finished=clock.getAsLong();status=success?Status.COMPLETE:Status.FAILED;
            if(active!=null)completed.add(new Step(active,millis(finished-stepStarted),status));active=null;
        }
        public synchronized Snapshot snapshot(){
            long now=status==Status.RUNNING?clock.getAsLong():finished;
            var steps=new ArrayList<>(completed);
            if(active!=null)steps.add(new Step(active,millis(now-stepStarted),Status.RUNNING));
            return new Snapshot(id,operation,startedAt,millis(now-started),status,steps);
        }
        private static long millis(long nanos){return Math.max(0,nanos/1_000_000);}
    }
}
