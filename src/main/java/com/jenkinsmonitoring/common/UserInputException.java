package com.jenkinsmonitoring.common;

/**
 * An error the user can act on (bad input, unknown rule). Its message is shown to the caller as a tool
 * error; any other exception is reported as an internal error.
 */
public class UserInputException extends RuntimeException {

    public UserInputException(String message) {
        super(message);
    }
}
