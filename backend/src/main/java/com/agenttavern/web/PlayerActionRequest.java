package com.agenttavern.web;

public record PlayerActionRequest(String type, Long amount, java.util.UUID commandId, String tableId, Long expectedVersion) {
    TableCommand command() { return new TableCommand(commandId, tableId, expectedVersion); }
}
