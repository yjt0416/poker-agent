package com.agenttavern.web;

final class TableSessionException extends RuntimeException {
    private final int status;

    TableSessionException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() { return status; }
}
