package com.agenttavern.agents;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.Street;
import com.agenttavern.game.card.Card;
import java.util.List;

/** The complete and only information an agent provider may receive for one decision. */
public record AgentObservation(
        PlayerId self,
        AgentPersona persona,
        List<Card> holeCards,
        List<Card> board,
        List<ObservedSeat> seats,
        Street street,
        long pot,
        LegalActions legalActions,
        List<TableMessage> recentMessages) {

    public AgentObservation {
        requireNonNull(self, "self");
        requireNonNull(persona, "persona");
        requireNonNull(street, "street");
        requireNonNull(legalActions, "legalActions");
        holeCards = List.copyOf(holeCards);
        board = List.copyOf(board);
        seats = List.copyOf(seats);
        recentMessages = List.copyOf(recentMessages);
        if (holeCards.size() != 2) throw new IllegalArgumentException("agent must observe exactly two hole cards");
        if (pot < 0) throw new IllegalArgumentException("pot must be non-negative");
    }
}
