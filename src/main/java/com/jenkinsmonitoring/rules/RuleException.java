package com.jenkinsmonitoring.rules;

import com.jenkinsmonitoring.common.UserInputException;

/** A rule request that cannot be accepted; the message explains why and, where possible, what to do. */
public class RuleException extends UserInputException {

    public RuleException(String message) {
        super(message);
    }
}
