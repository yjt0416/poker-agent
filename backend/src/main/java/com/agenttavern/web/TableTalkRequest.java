package com.agenttavern.web;

public record TableTalkRequest(String text, java.util.UUID commandId, String tableId, Long expectedVersion) {
    TableCommand command() { return new TableCommand(commandId, tableId, expectedVersion); }
}
