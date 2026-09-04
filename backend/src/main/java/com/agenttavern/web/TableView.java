package com.agenttavern.web;

import java.time.Instant;
import java.util.List;

/** Public, redacted projection safe to send to one browser session. */
public record TableView(
        String tableId,
        long version,
        String mode,
        String status,
        int handNumber,
        String street,
        long pot,
        int buttonSeat,
        Integer actorSeat,
        int selfSeat,
        BlindView blinds,
        List<SeatView> seats,
        List<CardView> board,
        List<CardView> holeCards,
        LegalActionView legalActions,
        List<ActionLogView> actionLog,
        List<ChatView> chat) {

    public record BlindView(long small, long big) {}

    public record SeatView(
            int seat,
            String name,
            String persona,
            int sprite,
            long stack,
            long streetCommitted,
            long handCommitted,
            String status,
            boolean self) {}

    public record CardView(String rank, String suit) {}

    public record LegalActionView(
            List<String> types,
            long callAmount,
            Long minRaiseTo,
            long maxRaiseTo) {
        static LegalActionView none() { return new LegalActionView(List.of(), 0, null, 0); }
    }

    public record ActionLogView(long sequence, int seat, String name, String action, String summary) {}

    public record ChatView(int seat, String name, String text, Instant occurredAt) {}
}
