package com.dbcompanion.common.exception;

public final class MetadataEditException extends RuntimeException {
    private final int status;
    private final String userMessage;
    public MetadataEditException(int status, String diagnostic, String userMessage) {
        super(diagnostic); this.status = status; this.userMessage = userMessage;
    }
    public int status() { return status; }
    public String userMessage() { return userMessage; }
}
