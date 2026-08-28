package com.agenttavern.game;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenttavern.game.betting.ActionType;
import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.hand.BlindLevel;
import com.agenttavern.game.hand.HandEvent;
import com.agenttavern.game.hand.HandId;
import com.agenttavern.game.hand.HandTransition;
import com.agenttavern.game.hand.PlayerStack;
import com.agenttavern.game.handvalue.HandCategory;
import com.agenttavern.game.handvalue.HandValue;
import com.agenttavern.game.showdown.Pot;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameEngineDependencyTest {

    private static final String GAME_PACKAGE = "com.agenttavern.game";

    @Test
    void gameEngineDoesNotDependOnInfrastructureFrameworks() {
        noClasses()
                .that().resideInAPackage(GAME_PACKAGE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.data..",
                        "org.springframework.ai..",
                        "org.springframework.jdbc..",
                        "jakarta.persistence..",
                        "javax.persistence..",
                        "org.hibernate..",
                        "org.flywaydb..",
                        "liquibase..",
                        "com.zaxxer.hikari..",
                        "io.r2dbc..",
                        "java.sql..",
                        "javax.sql..",
                        "com.fasterxml.jackson..",
                        "com.google.gson..",
                        "org.json..",
                        "org.vaadin..",
                        "javafx..",
                        "org.thymeleaf..")
                .check(gameClasses());
    }

    @Test
    void gameEngineContainsNoInfrastructureStyleClassNames() {
        JavaClasses classes = gameClasses();
        for (String forbiddenSuffix : List.of("Controller", "RepositoryImpl", "Entity")) {
            noClasses()
                    .that().resideInAPackage(GAME_PACKAGE + "..")
                    .should().haveSimpleNameEndingWith(forbiddenSuffix)
                    .check(classes);
        }
    }

    @Test
    void publicDomainRecordsExposeDefensivelyImmutableCollections() {
        assertThat(publicRecordsWithCollectionComponents())
                .containsExactlyInAnyOrder(
                        LegalActions.class,
                        HandTransition.class,
                        HandEvent.HandStarted.class,
                        HandEvent.HoleCardsDealt.class,
                        HandEvent.CommunityCardsDealt.class,
                        HandEvent.PotsAwarded.class,
                        HandEvent.HandCompleted.class,
                        Pot.class,
                        HandValue.class);

        assertUnmodifiableCollection(mutableLegalActions().types());
        assertUnmodifiableCollection(mutableHandValue().tieBreakers());

        HandTransition transition = completedAllInHand();
        assertUnmodifiableCollection(transition.events());
        HandEvent.HandStarted started = event(transition, HandEvent.HandStarted.class);
        HandEvent.HoleCardsDealt holeCardsDealt = event(transition, HandEvent.HoleCardsDealt.class);
        HandEvent.CommunityCardsDealt communityCardsDealt =
                event(transition, HandEvent.CommunityCardsDealt.class);
        HandEvent.PotsAwarded potsAwarded = event(transition, HandEvent.PotsAwarded.class);
        HandEvent.HandCompleted completed = event(transition, HandEvent.HandCompleted.class);

        assertUnmodifiableCollection(started.players());
        assertUnmodifiableMap(holeCardsDealt.holeCards());
        holeCardsDealt.holeCards().values().forEach(
                GameEngineDependencyTest::assertUnmodifiableCollection);
        assertUnmodifiableCollection(communityCardsDealt.cards());
        assertUnmodifiableCollection(potsAwarded.pots());
        assertUnmodifiableMap(potsAwarded.payouts());
        potsAwarded.pots().forEach(pot -> assertUnmodifiableCollection(pot.eligiblePlayers()));
        assertUnmodifiableCollection(completed.seats());
    }

    private static JavaClasses gameClasses() {
        return new ClassFileImporter().importPackages(GAME_PACKAGE);
    }

    private static Set<Class<?>> publicRecordsWithCollectionComponents() {
        return gameClasses().stream()
                .map(JavaClass::reflect)
                .filter(Class::isRecord)
                .filter(type -> Modifier.isPublic(type.getModifiers()))
                .filter(GameEngineDependencyTest::hasCollectionComponent)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean hasCollectionComponent(Class<?> type) {
        return java.util.Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getType)
                .anyMatch(componentType -> Collection.class.isAssignableFrom(componentType)
                        || Map.class.isAssignableFrom(componentType));
    }

    private static LegalActions mutableLegalActions() {
        Set<ActionType> types = EnumSet.of(ActionType.CHECK, ActionType.ALL_IN);
        LegalActions legalActions = new LegalActions(types, 0, OptionalLong.empty(), 100);
        types.clear();
        assertThat(legalActions.types()).containsExactlyInAnyOrder(ActionType.CHECK, ActionType.ALL_IN);
        return legalActions;
    }

    private static HandValue mutableHandValue() {
        List<Integer> tieBreakers = new ArrayList<>(List.of(14, 13, 12, 11));
        HandValue handValue = new HandValue(HandCategory.ONE_PAIR, tieBreakers);
        tieBreakers.clear();
        assertThat(handValue.tieBreakers()).containsExactly(14, 13, 12, 11);
        return handValue;
    }

    private static HandTransition completedAllInHand() {
        PlayerId button = new PlayerId(new UUID(0, 1));
        PlayerId bigBlind = new PlayerId(new UUID(0, 2));
        return com.agenttavern.game.hand.Hand.start(
                new HandId(new UUID(1, 1)),
                List.of(
                        new PlayerStack(button, 0, 50),
                        new PlayerStack(bigBlind, 1, 100)),
                0,
                new BlindLevel(50, 100),
                Deck.standard());
    }

    private static <T extends HandEvent> T event(HandTransition transition, Class<T> eventType) {
        return transition.events().stream()
                .filter(eventType::isInstance)
                .map(eventType::cast)
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertUnmodifiableCollection(Collection<?> collection) {
        Collection rawCollection = collection;
        assertThatThrownBy(() -> rawCollection.add(new Object()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertUnmodifiableMap(Map<?, ?> map) {
        Map rawMap = map;
        assertThatThrownBy(() -> rawMap.put(new Object(), new Object()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
