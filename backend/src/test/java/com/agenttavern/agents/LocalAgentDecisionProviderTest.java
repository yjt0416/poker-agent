package com.agenttavern.agents;

import com.agenttavern.game.betting.*;
import com.agenttavern.game.card.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LocalAgentDecisionProviderTest {
    private final LocalAgentDecisionProvider provider = new LocalAgentDecisionProvider();
    private static Card card(Suit suit, Rank rank) { return new Card(suit, rank); }
    private static final List<Card> WEAK = List.of(card(Suit.CLUBS,Rank.TWO),card(Suit.HEARTS,Rank.SEVEN));
    private static final List<Card> ACES = List.of(card(Suit.CLUBS,Rank.ACE),card(Suit.HEARTS,Rank.ACE));
    private AgentObservation observe(int seed, AgentPersona persona, List<Card> hole, List<Card> board,
            long stack, long committed, long streetCommitted, long call, long min, long pot) {
        var id = new PlayerId(new UUID(0,seed));
        var types = EnumSet.of(call == 0 ? ActionType.CHECK : ActionType.CALL,ActionType.ALL_IN);
        if (call > 0) types.add(ActionType.FOLD);
        if (min > 0) types.add(ActionType.RAISE);
        return new AgentObservation(id,persona,hole,board,
                List.of(new ObservedSeat(id,0,stack,committed,PlayerStatus.ACTIVE)),
                board.isEmpty()?Street.PREFLOP:board.size()==5?Street.RIVER:Street.FLOP,pot,
                new LegalActions(types,call,min>0?OptionalLong.of(min):OptionalLong.empty(),stack+streetCommitted),List.of());
    }
    @Test void weakHandsRefuseStackSizedPreflopCallsForEveryPersona() {
        for(var persona:AgentRoster.all()) for(int seed=0;seed<30;seed++) {
            var o=observe(seed,persona,WEAK,List.of(),9900,100,100,9900,0,20100);
            assertThat(provider.decide(o).action()).isEqualTo(PlayerAction.fold());
        }
    }
    @Test void strongStartingHandContinuesAtNormalPriceButCannotEscalateBeyondHandBudget() {
        for(int seed=0;seed<100;seed++) {
            var o=observe(seed,AgentRoster.require("hogarth"),ACES,List.of(),9900,100,100,100,300,500);
            var action=provider.decide(o).action();
            assertThat(action.type()).isIn(ActionType.CALL,ActionType.RAISE);
            assertThat(AgentDecisionPolicy.isLegal(o.legalActions(),action)).isTrue();
            var expensive=observe(seed,o.persona(),ACES,List.of(),4000,6000,2000,1000,4000,14000);
            assertThat(provider.decide(expensive).action()).isEqualTo(PlayerAction.fold());
        }
    }
    @Test void weakHandChecksWhenFreeEvenAfterSpendingItsBudget() {
        var o=observe(1,AgentRoster.require("hogarth"),WEAK,List.of(),9000,1000,0,0,100,3000);
        assertThat(provider.decide(o).action()).isEqualTo(PlayerAction.check());
    }
    @Test void sharedRoyalFlushIsNotMistakenForAnExclusiveMonster() {
        var board=List.of(card(Suit.SPADES,Rank.TEN),card(Suit.SPADES,Rank.JACK),card(Suit.SPADES,Rank.QUEEN),
                card(Suit.SPADES,Rank.KING),card(Suit.SPADES,Rank.ACE));
        var o=observe(1,AgentRoster.require("hogarth"),WEAK,board,9000,1000,0,9000,0,11000);
        assertThat(provider.decide(o).action()).isEqualTo(PlayerAction.fold());
    }
    @Test void privateFullHouseCanCallAllInSoRiskControlDoesNotBanStrongHands() {
        var board=List.of(card(Suit.SPADES,Rank.ACE),card(Suit.CLUBS,Rank.KING),card(Suit.HEARTS,Rank.KING),
                card(Suit.DIAMONDS,Rank.FOUR),card(Suit.SPADES,Rank.NINE));
        var o=observe(1,AgentRoster.require("bunji"),ACES,board,9000,1000,0,9000,0,11000);
        assertThat(provider.decide(o).action()).isEqualTo(PlayerAction.call());
    }
    @Test void raisesUseStreetContributionAndStayInsideWholeHandBudget() {
        int raises=0;
        for(int seed=0;seed<100;seed++) {
            var o=observe(seed,AgentRoster.require("hogarth"),ACES,List.of(),9000,1000,100,100,300,2000);
            var action=provider.decide(o).action();
            assertThat(AgentDecisionPolicy.isLegal(o.legalActions(),action)).isTrue();
            if(action.type()==ActionType.RAISE) {
                raises++;
                assertThat(action.amount()).isBetween(300L,5600L);
            }
        }
        assertThat(raises).isPositive();
    }
}
