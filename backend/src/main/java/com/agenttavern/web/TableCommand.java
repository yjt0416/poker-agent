package com.agenttavern.web;

import java.util.UUID;

public record TableCommand(UUID commandId, String tableId, Long expectedVersion) {}
