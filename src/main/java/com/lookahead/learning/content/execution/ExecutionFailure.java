package com.lookahead.learning.content.execution;

final class ExecutionFailure extends RuntimeException {
    final int status;
    final String code;
    ExecutionFailure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
}
