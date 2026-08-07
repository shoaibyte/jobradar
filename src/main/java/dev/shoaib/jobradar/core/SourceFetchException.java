package dev.shoaib.jobradar.core;

public class SourceFetchException extends Exception {

    public SourceFetchException(String message) {
        super(message);
    }

    public SourceFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
