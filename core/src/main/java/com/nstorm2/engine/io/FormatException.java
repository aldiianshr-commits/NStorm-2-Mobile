package com.nstorm2.engine.io;

/** Thrown when game data does not match the expected binary layout. */
public class FormatException extends RuntimeException {
    public FormatException(String message) {
        super(message);
    }

    public FormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
