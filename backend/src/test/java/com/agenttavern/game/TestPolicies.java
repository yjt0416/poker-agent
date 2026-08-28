package com.agenttavern.game;

import com.agenttavern.game.betting.ActionType;
import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerAction;
import java.util.Objects;

final class TestPolicies {

    private TestPolicies() {}

    static PlayerAction passiveLegalAction(LegalActions legalActions) {
        Objects.requireNonNull(legalActions, "legalActions");
        if (legalActions.types().contains(ActionType.CHECK)) {
            return PlayerAction.check();
        }
        if (legalActions.types().contains(ActionType.CALL)) {
            return PlayerAction.call();
        }
        if (legalActions.types().contains(ActionType.ALL_IN)) {
            return PlayerAction.allIn(legalActions.maxRaiseTo());
        }
        if (legalActions.types().contains(ActionType.FOLD)) {
            return PlayerAction.fold();
        }
        throw new AssertionError("active player must have a legal action");
    }

    static PlayerAction aggressiveLegalAction(LegalActions legalActions) {
        Objects.requireNonNull(legalActions, "legalActions");
        if (legalActions.types().contains(ActionType.RAISE)) {
            return PlayerAction.raiseTo(legalActions.minRaiseTo().orElseThrow());
        }
        if (legalActions.types().contains(ActionType.CALL)) {
            return PlayerAction.call();
        }
        if (legalActions.types().contains(ActionType.CHECK)) {
            return PlayerAction.check();
        }
        if (legalActions.types().contains(ActionType.ALL_IN)) {
            return PlayerAction.allIn(legalActions.maxRaiseTo());
        }
        if (legalActions.types().contains(ActionType.FOLD)) {
            return PlayerAction.fold();
        }
        throw new AssertionError("active player must have a legal action");
    }
}
