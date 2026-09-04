package com.agenttavern.web;

import com.agenttavern.agents.AgentDecision;
import com.agenttavern.agents.AgentDecisionPolicy;
import com.agenttavern.agents.AgentDecisionProvider;
import com.agenttavern.agents.AgentObservation;
import com.agenttavern.agents.AgentPersona;
import com.agenttavern.agents.AgentRoster;
import com.agenttavern.agents.ObservedSeat;
import com.agenttavern.agents.TableMessage;
import com.agenttavern.game.betting.ActionType;
import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Rank;
import com.agenttavern.game.card.Suit;
import com.agenttavern.game.hand.Hand;
import com.agenttavern.game.hand.HandCheckpoint;
import com.agenttavern.game.hand.HandEvent;
import com.agenttavern.game.hand.HandId;
import com.agenttavern.tablechat.TableChatMessage;
import com.agenttavern.tablechat.TableChatPolicy;
import com.agenttavern.tablechat.TableChatService;
import com.agenttavern.tournament.Tournament;
import com.agenttavern.tournament.TournamentEntrant;
import com.agenttavern.tournament.TournamentId;
import com.agenttavern.tournament.TournamentEvent;
import com.agenttavern.tournament.TournamentMode;
import com.agenttavern.tournament.TournamentStatus;
import com.agenttavern.tournament.application.ActInTournamentCommand;
import com.agenttavern.tournament.application.CreateTournamentCommand;
import com.agenttavern.tournament.application.StartNextHandCommand;
import com.agenttavern.tournament.application.TournamentCommandService;
import com.agenttavern.tournament.application.TournamentExecution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class GameTableService {
    private static final int HUMAN_SEAT = 5;
    private static final int MAX_AUTOMATIC_ACTIONS = 100;

    private final TournamentCommandService commands;
    private final AgentDecisionProvider decisions;
    private final TableChatService chatService;
    private final ConcurrentMap<TournamentId, RuntimeTable> tables = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TableSession> sessions = new ConcurrentHashMap<>();

    public GameTableService(
            TournamentCommandService commands,
            AgentDecisionProvider decisions,
            TableChatService chatService) {
        this.commands = commands;
        this.decisions = decisions;
        this.chatService = chatService;
    }

    CreatedTable createTable(String requestedName, String requestedMode) {
        String displayName = normalizeDisplayName(requestedName);
        TournamentMode mode = "SPECTATOR".equalsIgnoreCase(requestedMode)
                ? TournamentMode.SPECTATOR : TournamentMode.PLAYER;
        TournamentId tournamentId = TournamentId.random();
        List<PlayerId> players = new ArrayList<>(6);
        List<TournamentEntrant> entrants = new ArrayList<>(6);
        for (int seat = 0; seat < 6; seat++) {
            PlayerId id = PlayerId.random();
            players.add(id);
            entrants.add(new TournamentEntrant(id, seat));
        }
        TournamentExecution execution = commands.create(new CreateTournamentCommand(
                UUID.randomUUID(), tournamentId, mode,
                entrants, 0, HandId.random()));
        PlayerId humanId = mode == TournamentMode.PLAYER ? players.get(HUMAN_SEAT) : null;
        RuntimeTable table = new RuntimeTable(tournamentId, humanId, displayName, players, execution);
        tables.put(tournamentId, table);
        String token = UUID.randomUUID().toString();
        sessions.put(token, new TableSession(tournamentId));
        synchronized (table) {
            if (mode == TournamentMode.PLAYER) advanceAgents(table);
            return new CreatedTable(token, project(table));
        }
    }

    TableView current(String token) {
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            return project(table);
        }
    }

    TableView act(String token, PlayerActionRequest request) {
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            requireHumanTurn(table);
            Hand hand = currentHand(table.execution);
            PlayerAction action = parseAction(request, hand.legalActions());
            table.execution = commands.act(new ActInTournamentCommand(
                    UUID.randomUUID(), table.id, table.execution.version(), table.humanId, action));
            table.addAction(HUMAN_SEAT, table.displayName, describe(action), "玩家行动");
            recordSettlement(table, hand);
            advanceAgents(table);
            return project(table);
        }
    }

    TableView talk(String token, String text) {
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            if (table.humanId == null) throw new TableSessionException(409, "观战模式不能代替 Agent 发言");
            TableChatMessage message = chatService.submit(table.id, table.humanId, text);
            table.chat.add(message);
            table.trimHistory();
            return project(table);
        }
    }

    TableView nextHand(String token) {
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            if (table.execution.checkpoint().status() != TournamentStatus.BETWEEN_HANDS) {
                throw new TableSessionException(409, "当前还不能开始下一手牌");
            }
            table.execution = commands.startNextHand(new StartNextHandCommand(
                    UUID.randomUUID(), table.id, table.execution.version(), HandId.random()));
            table.lastBoard = List.of();
            table.lastHumanHoleCards = List.of();
            table.addAction(-1, "茶馆荷官", "开始第 "
                    + (table.execution.checkpoint().completedHands() + 1) + " 手牌", "盲注已下，卡牌已发出");
            if (table.humanId != null) advanceAgents(table);
            return project(table);
        }
    }

    TableView advanceSpectator(String token) {
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            if (table.humanId != null) throw new TableSessionException(409, "玩家模式不能手动推进 Agent");
            if (table.execution.checkpoint().status() != TournamentStatus.IN_HAND) {
                throw new TableSessionException(409, "当前手牌已经结束");
            }
            advanceOneAgent(table);
            return project(table);
        }
    }

    private void advanceAgents(RuntimeTable table) {
        for (int count = 0; count < MAX_AUTOMATIC_ACTIONS; count++) {
            if (table.execution.checkpoint().status() != TournamentStatus.IN_HAND) return;
            Hand hand = currentHand(table.execution);
            PlayerId actor = hand.actor().playerId();
            if (actor.equals(table.humanId)) return;
            advanceOneAgent(table);
        }
        throw new IllegalStateException("automatic action safety limit exceeded");
    }

    private void advanceOneAgent(RuntimeTable table) {
        Hand hand = currentHand(table.execution);
        PlayerId actor = hand.actor().playerId();
        int seat = hand.actor().seatIndex();
        AgentObservation observation = observation(table, hand, actor, seat);
        AgentDecision decision;
        try {
            decision = AgentDecisionPolicy.validateOrFallback(observation, decisions.decide(observation));
        } catch (RuntimeException exception) {
            decision = AgentDecisionPolicy.fallback(hand.legalActions(), "决策服务暂不可用，已执行安全动作。");
        }
        table.execution = commands.act(new ActInTournamentCommand(
                UUID.randomUUID(), table.id, table.execution.version(), actor, decision.action()));
        AgentPersona persona = personaForSeat(seat);
        table.addAction(seat, persona.name(), describe(decision.action()), decision.publicSummary());
        recordSettlement(table, hand);
        if (!decision.tableTalk().isBlank()) {
            try {
                String safeTalk = TableChatPolicy.normalize(decision.tableTalk());
                table.chat.add(new TableChatMessage(
                        UUID.randomUUID(), table.id, actor, safeTalk, java.time.Instant.now()));
            } catch (RuntimeException ignored) {
                // A malformed model utterance never blocks the poker action.
            }
        }
        table.trimHistory();
    }

    private static void recordSettlement(RuntimeTable table, Hand completedHand) {
        table.execution.events().stream()
                .map(event -> event.payload())
                .filter(TournamentEvent.HandEventRecorded.class::isInstance)
                .map(TournamentEvent.HandEventRecorded.class::cast)
                .map(TournamentEvent.HandEventRecorded::handEvent)
                .filter(HandEvent.PotsAwarded.class::isInstance)
                .map(HandEvent.PotsAwarded.class::cast)
                .findFirst()
                .ifPresent(awarded -> {
                    table.lastBoard = List.copyOf(completedHand.board());
                    table.lastHumanHoleCards = table.humanId == null
                            ? List.of() : List.copyOf(completedHand.holeCards(table.humanId));
                    List<String> winners = awarded.payouts().entrySet().stream()
                            .map(entry -> winnerName(table, entry.getKey()) + "收下 " + entry.getValue() + " 筹码")
                            .toList();
                    int winnerSeat = awarded.payouts().size() == 1
                            ? table.players.indexOf(awarded.payouts().keySet().iterator().next()) : -1;
                    table.addAction(winnerSeat, "茶馆荷官", "本手结算", String.join("，", winners) + "。");
                });
    }

    private static String winnerName(RuntimeTable table, PlayerId playerId) {
        int seat = table.players.indexOf(playerId);
        if (seat < 0) return "未知牌手";
        if (table.humanId != null && playerId.equals(table.humanId)) return table.displayName;
        return personaForSeat(seat).name();
    }

    private AgentObservation observation(RuntimeTable table, Hand hand, PlayerId actor, int seat) {
        List<ObservedSeat> seats = hand.seats().stream()
                .map(value -> new ObservedSeat(value.playerId(), value.seatIndex(), value.stack(),
                        value.handCommitted(), value.status()))
                .toList();
        List<TableMessage> messages = table.chat.stream()
                .skip(Math.max(0, table.chat.size() - 12L))
                .map(value -> new TableMessage(value.speakerId(), value.text()))
                .toList();
        long pot = hand.seats().stream().mapToLong(SeatState::handCommitted).sum();
        return new AgentObservation(actor, personaForSeat(seat), hand.holeCards(actor), hand.board(),
                seats, hand.street(), pot, hand.legalActions(), messages);
    }

    private TableView project(RuntimeTable table) {
        var checkpoint = table.execution.checkpoint();
        Hand hand = checkpoint.status() == TournamentStatus.IN_HAND ? Tournament.restore(checkpoint).currentHand() : null;
        Map<PlayerId, SeatState> handSeats = new LinkedHashMap<>();
        if (hand != null) hand.seats().forEach(seat -> handSeats.put(seat.playerId(), seat));
        List<TableView.SeatView> seats = checkpoint.seats().stream()
                .sorted(Comparator.comparingInt(value -> value.seatIndex()))
                .map(seat -> {
                    SeatState handSeat = handSeats.get(seat.playerId());
                    boolean self = table.humanId != null && seat.playerId().equals(table.humanId);
                    int index = seat.seatIndex();
                    return new TableView.SeatView(index,
                            self ? table.displayName : personaForSeat(index).name(),
                            self ? "human" : personaForSeat(index).key(),
                            self ? 7 : index,
                            handSeat == null ? seat.stack() : handSeat.stack(),
                            handSeat == null ? 0 : handSeat.streetCommitted(),
                            handSeat == null ? 0 : handSeat.handCommitted(),
                            handSeat == null ? seat.status().name() : handSeat.status().name(), self);
                }).toList();

        Integer actorSeat = hand == null ? null : hand.actor().seatIndex();
        List<TableView.CardView> board = (hand == null ? table.lastBoard : hand.board()).stream()
                .map(GameTableService::card).toList();
        List<TableView.CardView> hole = table.humanId == null ? List.of()
                : (hand == null ? table.lastHumanHoleCards : hand.holeCards(table.humanId)).stream()
                        .map(GameTableService::card).toList();
        TableView.LegalActionView legal = hand != null && table.humanId != null && hand.actor().playerId().equals(table.humanId)
                ? legal(hand.legalActions()) : TableView.LegalActionView.none();
        long pot = hand == null ? 0 : hand.seats().stream().mapToLong(SeatState::handCommitted).sum();
        List<TableView.ChatView> chat = table.chat.stream().map(message -> {
            int seat = table.players.indexOf(message.speakerId());
            String name = table.humanId != null && seat == HUMAN_SEAT ? table.displayName : personaForSeat(seat).name();
            return new TableView.ChatView(seat, name, message.text(), message.occurredAt());
        }).toList();

        return new TableView(checkpoint.id().value().toString(), table.execution.version(), checkpoint.mode().name(),
                checkpoint.status().name(), hand == null
                        ? Math.max(1, checkpoint.completedHands()) : checkpoint.completedHands() + 1,
                hand == null ? checkpoint.status().name() : hand.street().name(), pot,
                checkpoint.buttonSeat(), actorSeat, table.humanId == null ? -1 : HUMAN_SEAT,
                new TableView.BlindView(checkpoint.currentHand().map(value -> value.blinds().smallBlind())
                        .orElse(checkpoint.seats().isEmpty() ? 0L : Tournament.restore(checkpoint).currentBlinds().smallBlind()),
                        checkpoint.currentHand().map(value -> value.blinds().bigBlind())
                                .orElse(Tournament.restore(checkpoint).currentBlinds().bigBlind())),
                seats, board, hole, legal, List.copyOf(table.actions), chat);
    }

    private RuntimeTable requireTable(String token) {
        if (token == null || token.isBlank()) throw new TableSessionException(401, "缺少牌桌会话");
        TableSession session = sessions.get(token);
        if (session == null) throw new TableSessionException(401, "牌桌会话已失效");
        RuntimeTable table = tables.get(session.tableId());
        if (table == null) {
            throw new TableSessionException(401, "牌桌会话已失效");
        }
        return table;
    }

    private static void requireHumanTurn(RuntimeTable table) {
        if (table.humanId == null
                || table.execution.checkpoint().status() != TournamentStatus.IN_HAND
                || !currentHand(table.execution).actor().playerId().equals(table.humanId)) {
            throw new TableSessionException(409, "当前不是你的行动回合");
        }
    }

    private static Hand currentHand(TournamentExecution execution) {
        return Tournament.restore(execution.checkpoint()).currentHand();
    }

    private static PlayerAction parseAction(PlayerActionRequest request, LegalActions legal) {
        if (request == null || request.type() == null) throw new TableSessionException(400, "缺少行动类型");
        ActionType type;
        try {
            type = ActionType.valueOf(request.type().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new TableSessionException(400, "未知行动类型");
        }
        PlayerAction action = switch (type) {
            case FOLD -> PlayerAction.fold();
            case CHECK -> PlayerAction.check();
            case CALL -> PlayerAction.call();
            case RAISE -> PlayerAction.raiseTo(requiredAmount(request));
            case ALL_IN -> PlayerAction.allIn(request.amount() == null ? legal.maxRaiseTo() : request.amount());
        };
        if (!AgentDecisionPolicy.isLegal(legal, action)) throw new TableSessionException(409, "该行动当前不合法");
        return action;
    }

    private static long requiredAmount(PlayerActionRequest request) {
        if (request.amount() == null) throw new TableSessionException(400, "加注需要目标金额");
        return request.amount();
    }

    private static TableView.LegalActionView legal(LegalActions value) {
        return new TableView.LegalActionView(value.types().stream().map(Enum::name).sorted().toList(),
                value.callAmount(), value.minRaiseTo().isPresent() ? value.minRaiseTo().getAsLong() : null,
                value.maxRaiseTo());
    }

    private static TableView.CardView card(Card value) {
        return new TableView.CardView(rank(value.rank()), suit(value.suit()));
    }

    private static String rank(Rank rank) {
        return switch (rank) {
            case TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE -> Integer.toString(rank.strength());
            case TEN -> "10";
            case JACK -> "J";
            case QUEEN -> "Q";
            case KING -> "K";
            case ACE -> "A";
        };
    }

    private static String suit(Suit suit) {
        return switch (suit) {
            case CLUBS -> "♣";
            case DIAMONDS -> "♦";
            case HEARTS -> "♥";
            case SPADES -> "♠";
        };
    }

    private static AgentPersona personaForSeat(int seat) {
        if (seat < 0 || seat >= AgentRoster.all().size()) throw new IllegalArgumentException("seat has no agent persona: " + seat);
        return AgentRoster.all().get(seat);
    }

    private static String describe(PlayerAction action) {
        return switch (action.type()) {
            case FOLD -> "弃牌";
            case CHECK -> "过牌";
            case CALL -> "跟注";
            case RAISE -> "加注至 " + action.amount();
            case ALL_IN -> "全下至 " + action.amount();
        };
    }

    private static String normalizeDisplayName(String value) {
        String name = value == null ? "旅人" : value.strip();
        if (name.isBlank()) name = "旅人";
        if (name.codePointCount(0, name.length()) > 20) throw new TableSessionException(400, "昵称最多 20 个字符");
        return name;
    }

    record CreatedTable(String sessionToken, TableView view) {}
    private record TableSession(TournamentId tableId) {}

    private static final class RuntimeTable {
        private final TournamentId id;
        private final PlayerId humanId;
        private final String displayName;
        private final List<PlayerId> players;
        private final List<TableView.ActionLogView> actions = new ArrayList<>();
        private final List<TableChatMessage> chat = new ArrayList<>();
        private List<Card> lastBoard = List.of();
        private List<Card> lastHumanHoleCards = List.of();
        private TournamentExecution execution;
        private long actionSequence;

        private RuntimeTable(TournamentId id, PlayerId humanId, String displayName,
                List<PlayerId> players, TournamentExecution execution) {
            this.id = id;
            this.humanId = humanId;
            this.displayName = displayName;
            this.players = List.copyOf(players);
            this.execution = execution;
        }

        private void addAction(int seat, String name, String action, String summary) {
            actions.add(new TableView.ActionLogView(++actionSequence, seat, name, action, summary));
            trimHistory();
        }

        private void trimHistory() {
            if (actions.size() > 40) actions.subList(0, actions.size() - 40).clear();
            if (chat.size() > 30) chat.subList(0, chat.size() - 30).clear();
        }
    }
}
