package com.agenttavern.web;

import com.agenttavern.agents.AgentDecision;
import com.agenttavern.agents.AgentMemory;
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
import com.agenttavern.tournament.TournamentSeatStatus;
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
import com.agenttavern.tournament.port.TournamentStore;
import com.agenttavern.web.port.TableSessionStore;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PreDestroy;

@Service
public class GameTableService {
    private static final int HUMAN_SEAT = 5;
    private static final int MAX_AUTOMATIC_ACTIONS = 100;

    private final TournamentCommandService commands;
    private final AgentDecisionProvider decisions;
    private final TournamentStore store;
    private final TableSessionStore sessions;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final TableStreamHub streams;
    private final java.util.random.RandomGenerator random;
    private final ConcurrentMap<String, RuntimeTable> tables = new ConcurrentHashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon().name("table-agent-", 0).factory());
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("table-cleanup").factory());

    public GameTableService(
            TournamentCommandService commands,
            AgentDecisionProvider decisions,
            TournamentStore store, TableSessionStore sessions, JsonMapper json, Clock clock,
            ObjectProvider<PlatformTransactionManager> transactionManager, TableStreamHub streams,
            java.util.random.RandomGenerator random) {
        this.commands = commands;
        this.decisions = decisions;
        this.store = store;
        this.sessions = sessions;
        this.json = json;
        this.clock = clock;
        this.streams = streams;
        this.random = random;
        var manager = transactionManager.getIfAvailable();
        this.transactions = manager == null ? TransactionOperations.withoutTransaction() : new TransactionTemplate(manager);
        cleanup.scheduleWithFixedDelay(() -> {
            try { cleanupExpired(clock.instant()); }
            catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(GameTableService.class).warn(
                        "Expired table cleanup failed ({})", error.getClass().getSimpleName());
            }
        }, 1, 15, TimeUnit.MINUTES);
    }

    int cleanupExpired(Instant now) {
        var evicted = new java.util.concurrent.atomic.AtomicInteger();
        tables.forEach((hash, table) -> {
            if (!now.isBefore(table.expiresAt) && tables.remove(hash, table)) {
                streams.disconnect(hash);
                evicted.incrementAndGet();
            }
        });
        // The extra day preserves a short backup/recovery window after browser access expires.
        var purged = sessions.purgeExpired(now.minus(Duration.ofHours(24)));
        if (!purged.isEmpty() && store instanceof RuntimeTournamentStore memory) {
            memory.removeAll(purged.stream().map(TournamentId::new).toList());
        }
        return evicted.get() + purged.size();
    }

    CreatedTable createTable(String requestedName, String requestedMode, List<String> requestedPersonas) {
        String displayName = normalizeDisplayName(requestedName);
        TournamentMode mode = "SPECTATOR".equalsIgnoreCase(requestedMode)
                ? TournamentMode.SPECTATOR : TournamentMode.PLAYER;
        if (requestedMode != null && !List.of("PLAYER", "SPECTATOR").contains(requestedMode)) {
            throw new TableSessionException(400, "未知游戏模式");
        }
        int count = mode == TournamentMode.PLAYER ? 5 : 6;
        List<String> personas = requestedPersonas == null ? AgentRoster.all().stream().limit(count).map(AgentPersona::key).toList()
                : List.copyOf(requestedPersonas);
        if (personas.size() != count || personas.stream().distinct().count() != count) {
            throw new TableSessionException(400, "请选择 " + count + " 名不同的角色");
        }
        personas.forEach(AgentRoster::require);
        TournamentId tournamentId = TournamentId.random();
        List<PlayerId> players = new ArrayList<>(6);
        List<TournamentEntrant> entrants = new ArrayList<>(6);
        for (int seat = 0; seat < 6; seat++) {
            PlayerId id = PlayerId.random();
            players.add(id);
            entrants.add(new TournamentEntrant(id, seat));
        }
        PlayerId humanId = mode == TournamentMode.PLAYER ? players.get(HUMAN_SEAT) : null;
        String token = UUID.randomUUID().toString();
        RuntimeTable table = new RuntimeTable(tournamentId, humanId, displayName, players, null,
                hash(token), personas, clock.instant().plus(Duration.ofHours(8)));
        synchronized (table) {
            TableView view = commit(table, () -> table.execution = commands.create(new CreateTournamentCommand(
                    UUID.randomUUID(), tournamentId, mode, entrants,
                    random.nextInt(6), HandId.random())));
            tables.put(table.tokenHash, table);
            schedule(table, false);
            return new CreatedTable(token, view);
        }
    }

    TableView current(String token) {
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            schedule(table, table.stepRequested);
            return project(table);
        }
    }

    TableView act(String token, PlayerActionRequest request) {
        if (request == null || request.type() == null) throw new TableSessionException(400, "缺少行动类型");
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            if (duplicate(table, request.command(), "action:" + request.type() + ":" + request.amount())) return project(table);
            requireHumanTurn(table);
            Hand hand = currentHand(table.execution);
            PlayerAction action = parseAction(request, hand.legalActions());
            TableView view = commit(table, () -> {
                table.execution = commands.act(new ActInTournamentCommand(
                        request.commandId(), table.id, request.expectedVersion(), table.humanId, action));
                table.addAction(HUMAN_SEAT, table.displayName, describe(action), "玩家行动");
                rememberAction(table, HUMAN_SEAT, action.type());
                recordSettlement(table, hand);
                table.receipts.put(request.commandId(), "action:" + request.type() + ":" + request.amount());
            });
            schedule(table, false);
            return view;
        }
    }

    TableView talk(String token, TableTalkRequest request) {
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            if (request == null || request.text() == null) throw new TableSessionException(400, "缺少发言内容");
            if (duplicate(table, request.command(), "chat:" + request.text())) return project(table);
            if (table.humanId == null) throw new TableSessionException(409, "观战模式不能代替 Agent 发言");
            if (table.execution.checkpoint().status() == TournamentStatus.COMPLETE) {
                throw new TableSessionException(409, "锦标赛已结束，不能继续发言");
            }
            if (!humanIsFunded(table)) throw new TableSessionException(409, "已淘汰玩家只能观战，不能继续影响牌桌");
            String text = TableChatPolicy.normalize(request.text());
            if (table.lastHumanChat != null && clock.instant().isBefore(table.lastHumanChat.plusSeconds(5))) {
                throw new TableSessionException(429, "请等待 5 秒再发言");
            }
            return commit(table, () -> {
                table.lastHumanChat = clock.instant();
                table.chat.add(new TableChatMessage(request.commandId(), table.id, table.humanId, text, clock.instant()));
                table.memories.replaceAll((seat, memory) -> memory.hear(HUMAN_SEAT));
                table.addAction(HUMAN_SEAT, table.displayName, "牌桌发言", text);
                table.receipts.put(request.commandId(), "chat:" + request.text());
                table.trimHistory();
            });
        }
    }

    TableView nextHand(String token, TableCommand command) {
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            if (duplicate(table, command, "next")) return project(table);
            if (table.execution.checkpoint().status() != TournamentStatus.BETWEEN_HANDS) {
                throw new TableSessionException(409, "当前还不能开始下一手牌");
            }
            int handNumber = Math.addExact(table.execution.checkpoint().completedHands(), 1);
            TableView view = commit(table, () -> {
                table.execution = commands.startNextHand(new StartNextHandCommand(
                        command.commandId(), table.id, command.expectedVersion(), HandId.random()));
                table.lastBoard = List.of();
                table.lastHumanHoleCards = List.of();
                table.memories.replaceAll((seat, memory) -> memory.nextHand());
                table.addAction(-1, "茶馆荷官", "开始第 " + handNumber + " 手牌", "盲注已下，卡牌已发出");
                recordSettlement(table, null);
                table.receipts.put(command.commandId(), "next");
            });
            schedule(table, false);
            return view;
        }
    }

    TableView advanceSpectator(String token, TableCommand command) {
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            if (duplicate(table, command, "advance")) return project(table);
            if (!canAdvance(table)) throw new TableSessionException(409, "玩家模式不能手动推进 Agent");
            if (table.execution.checkpoint().status() != TournamentStatus.IN_HAND) {
                throw new TableSessionException(409, "当前手牌已经结束");
            }
            if (table.scheduled || table.stepRequested) throw new TableSessionException(409, "Agent 正在思考，请稍候");
            TableView view = commit(table, () -> {
                table.receipts.put(command.commandId(), "advance");
                table.stepRequested = true;
            });
            schedule(table, true);
            return view;
        }
    }

    private void advanceOneAgent(RuntimeTable table) {
        Hand hand;
        long version;
        AgentObservation observation;
        synchronized (table) {
            if (table.execution.checkpoint().status() != TournamentStatus.IN_HAND) return;
            hand = currentHand(table.execution);
            if (hand.actor().playerId().equals(table.humanId)) return;
            version = table.execution.version();
            observation = observation(table, hand, hand.actor().playerId(), hand.actor().seatIndex());
        }
        PlayerId actor = hand.actor().playerId();
        int seat = hand.actor().seatIndex();
        AgentDecision decision;
        try {
            decision = AgentDecisionPolicy.validateOrFallback(observation, decisions.decide(observation));
        } catch (RuntimeException exception) {
            decision = AgentDecisionPolicy.fallback(hand.legalActions(), "决策服务暂不可用，已执行安全动作。");
        }
        AgentDecision accepted = decision;
        synchronized (table) {
          if (table.execution.version() != version) return;
          commit(table, () -> {
            table.stepRequested = false;
            table.execution = commands.act(new ActInTournamentCommand(
                UUID.randomUUID(), table.id, version, actor, accepted.action()));
            AgentPersona persona = personaForSeat(table, seat);
            rememberAction(table, seat, accepted.action().type());
            String utterance = "";
            try {
                if (!accepted.tableTalk().isBlank()) utterance = TableChatPolicy.normalize(accepted.tableTalk());
            } catch (RuntimeException ignored) { /* Invalid speech never blocks an action. */ }
            AgentMemory memory = table.memories.getOrDefault(seat, AgentMemory.empty());
            if (memory.recentUtterances().contains(utterance)) utterance = "";
            table.memories.put(seat, memory.remember(accepted, utterance));
            table.addAction(seat, persona.name(), describe(accepted.action()), accepted.publicSummary());
            recordSettlement(table, hand);
            if (!utterance.isBlank()) {
                table.chat.add(new TableChatMessage(
                        UUID.randomUUID(), table.id, actor, utterance, clock.instant()));
                table.memories.replaceAll((observer, state) -> observer == seat ? state : state.hear(seat));
            }
        table.trimHistory();
          });
        }
    }

    private static void recordSettlement(RuntimeTable table, Hand completedHand) {
        List<HandEvent> events = table.execution.events().stream()
                .map(event -> event.payload())
                .filter(TournamentEvent.HandEventRecorded.class::isInstance)
                .map(TournamentEvent.HandEventRecorded.class::cast)
                .map(TournamentEvent.HandEventRecorded::handEvent)
                .toList();
        events.stream()
                .filter(HandEvent.PotsAwarded.class::isInstance)
                .map(HandEvent.PotsAwarded.class::cast)
                .findFirst()
                .ifPresent(awarded -> {
                    Map<Integer, Long> startingStacks = new LinkedHashMap<>();
                    List<Card> finalBoard = new ArrayList<>();
                    List<Card> humanHoleCards;
                    if (completedHand != null) {
                        completedHand.seats().forEach(previousSeat -> startingStacks.put(
                                previousSeat.seatIndex(),
                                Math.addExact(previousSeat.stack(), previousSeat.handCommitted())));
                        finalBoard.addAll(completedHand.board());
                        humanHoleCards = humanCards(table, completedHand);
                    } else {
                        HandEvent.HandStarted started = events.stream()
                                .filter(HandEvent.HandStarted.class::isInstance)
                                .map(HandEvent.HandStarted.class::cast)
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("settled opening hand has no start event"));
                        started.players().forEach(player -> startingStacks.put(player.seatIndex(), player.chips()));
                        humanHoleCards = table.humanId == null ? List.of() : events.stream()
                                .filter(HandEvent.HoleCardsDealt.class::isInstance)
                                .map(HandEvent.HoleCardsDealt.class::cast)
                                .findFirst()
                                .map(dealt -> dealt.holeCards().getOrDefault(table.humanId, List.of()))
                                .orElse(List.of());
                    }
                    for (var startingStack : startingStacks.entrySet()) {
                        int seat = startingStack.getKey();
                        if (!table.memories.containsKey(seat)) continue;
                        long finalStack = table.execution.checkpoint().seats().stream()
                                .filter(s -> s.seatIndex() == seat).findFirst().orElseThrow().stack();
                        table.memories.put(seat, table.memories.get(seat).outcome(
                                finalStack - startingStack.getValue()));
                    }
                    events.stream()
                            .filter(HandEvent.CommunityCardsDealt.class::isInstance)
                            .map(HandEvent.CommunityCardsDealt.class::cast)
                            .forEach(event -> finalBoard.addAll(event.cards()));
                    table.lastBoard = List.copyOf(finalBoard);
                    table.lastHumanHoleCards = List.copyOf(humanHoleCards);
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
        return personaForSeat(table, seat).name();
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
        return new AgentObservation(actor, personaForSeat(table, seat), hand.holeCards(actor), hand.board(),
                seats, hand.street(), pot, hand.legalActions(), messages,
                table.memories.getOrDefault(seat, AgentMemory.empty()));
    }

    private static void rememberAction(RuntimeTable table, int actorSeat, ActionType action) {
        table.memories.replaceAll((seat, memory) -> seat == actorSeat ? memory : memory.observe(actorSeat, action));
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
                            self ? table.displayName : personaForSeat(table, index).name(),
                            self ? "human" : personaForSeat(table, index).key(),
                            self ? 7 : AgentRoster.all().indexOf(personaForSeat(table, index)),
                            handSeat == null ? seat.stack() : handSeat.stack(),
                            handSeat == null ? 0 : handSeat.streetCommitted(),
                            handSeat == null ? 0 : handSeat.handCommitted(),
                            handSeat == null ? seat.status().name() : handSeat.status().name(), self,
                            self ? null : table.memories.getOrDefault(index, AgentMemory.empty()).emotion().name());
                }).toList();

        Integer actorSeat = hand == null ? null : hand.actor().seatIndex();
        List<TableView.CardView> board = (hand == null ? table.lastBoard : hand.board()).stream()
                .map(GameTableService::card).toList();
        List<TableView.CardView> hole = !humanIsFunded(table) ? List.of()
                : (hand == null ? table.lastHumanHoleCards : humanCards(table, hand)).stream()
                        .map(GameTableService::card).toList();
        TableView.LegalActionView legal = hand != null && table.humanId != null && hand.actor().playerId().equals(table.humanId)
                ? legal(hand.legalActions()) : TableView.LegalActionView.none();
        long pot = hand == null ? 0 : hand.seats().stream().mapToLong(SeatState::handCommitted).sum();
        List<TableView.ChatView> chat = table.chat.stream().map(message -> {
            int seat = table.players.indexOf(message.speakerId());
            String name = table.humanId != null && seat == HUMAN_SEAT ? table.displayName : personaForSeat(table, seat).name();
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
                seats, board, hole, legal, List.copyOf(table.actions), chat, table.sequence, canAdvance(table),
                checkpoint.seats().stream().sorted(Comparator.comparingInt(s -> s.finishPosition() == null ? 0 : s.finishPosition()))
                        .map(s -> new TableView.RankingView(s.seatIndex(), winnerName(table, s.playerId()),
                                table.humanId != null && s.playerId().equals(table.humanId) ? 7
                                        : AgentRoster.all().indexOf(personaForSeat(table, s.seatIndex())),
                                s.stack(), s.finishPosition())).toList());
    }

    private RuntimeTable requireTable(String token) {
        if (token == null || token.isBlank()) throw new TableSessionException(401, "缺少牌桌会话");
        String hash = hash(token);
        RuntimeTable table = tables.computeIfAbsent(hash, key -> {
            var saved = sessions.find(key).orElseThrow(() -> new TableSessionException(401, "牌桌会话已失效"));
            if (!clock.instant().isBefore(saved.expiresAt())) throw new TableSessionException(401, "牌桌会话已过期");
            var state = json.readValue(saved.metadata(), SavedTable.class);
            var stored = store.load(new TournamentId(saved.tournamentId())).orElseThrow(() -> new TableSessionException(404, "牌桌不存在"));
            RuntimeTable restored = new RuntimeTable(state.id(), state.humanId(), state.displayName(), state.players(),
                    new TournamentExecution(stored.checkpoint(), stored.version(), stored.lastSequence(), List.of()),
                    key, state.personas(), saved.expiresAt());
            restored.restore(state);
            return restored;
        });
        if (!clock.instant().isBefore(table.expiresAt)) {
            tables.remove(hash, table);
            throw new TableSessionException(401, "牌桌会话已过期");
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

    private static AgentPersona personaForSeat(RuntimeTable table, int seat) {
        return AgentRoster.require(table.personas.get(seat));
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

    private static List<Card> humanCards(RuntimeTable table, Hand hand) {
        return table.humanId != null && hand.seats().stream().anyMatch(s -> s.playerId().equals(table.humanId))
                ? hand.holeCards(table.humanId) : List.of();
    }

    private static boolean canAdvance(RuntimeTable table) {
        return table.humanId == null || table.execution.checkpoint().seats().stream()
                .anyMatch(s -> s.playerId().equals(table.humanId) && s.finishPosition() != null && s.finishPosition() > 1);
    }

    private static boolean humanIsFunded(RuntimeTable table) {
        return table.humanId != null && table.execution.checkpoint().seats().stream()
                .anyMatch(s -> s.playerId().equals(table.humanId) && s.status() == TournamentSeatStatus.FUNDED);
    }

    /** Duplicate receipts are checked before version validation, including after process recovery. */
    private boolean duplicate(RuntimeTable table, TableCommand command, String fingerprint) {
        if (command == null || command.commandId() == null || command.expectedVersion() == null
                || !table.id.value().toString().equals(command.tableId())) {
            throw new TableSessionException(400, "命令需要正确的牌桌 ID、命令 ID 和期望版本");
        }
        String previous = table.receipts.get(command.commandId());
        if (previous != null) {
            if (!previous.equals(fingerprint)) throw new TableSessionException(409, "命令 ID 已用于其他操作");
            schedule(table, table.stepRequested);
            return true;
        }
        if (command.expectedVersion() != table.execution.version()) throw new TableSessionException(409, "牌局已更新，请同步后重试");
        return false;
    }

    /** PostgreSQL checkpoint, session metadata and public frame share the same outer transaction. */
    private TableView commit(RuntimeTable table, Runnable action) {
        SavedTable before = table.snapshot();
        TournamentExecution previous = table.execution;
        TableView view;
        try {
            java.util.function.Supplier<TableView> persist = () -> transactions.execute(status -> {
                action.run();
                table.sequence++;
                TableView projection = project(table);
                sessions.save(new TableSessionStore.Session(table.tokenHash, table.id.value(), table.sequence,
                                json.writeValueAsString(table.snapshot()), table.expiresAt), before.sequence(),
                        new TableSessionStore.Frame(table.sequence, json.writeValueAsString(projection)));
                return projection;
            });
            view = store instanceof RuntimeTournamentStore memory ? memory.transaction(persist) : persist.get();
        } catch (RuntimeException error) {
            table.restore(before);
            table.execution = previous;
            throw error;
        }
        try {
            streams.publish(table.tokenHash, view);
        } catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(GameTableService.class).warn(
                    "Committed table update could not be pushed ({})", error.getClass().getSimpleName());
        }
        return view;
    }

    private void schedule(RuntimeTable table, boolean oneStep) {
        if (!clock.instant().isBefore(table.expiresAt)) return;
        if (table.scheduled || table.execution.checkpoint().status() != TournamentStatus.IN_HAND) return;
        if (!oneStep && canAdvance(table)) return;
        if (currentHand(table.execution).actor().playerId().equals(table.humanId)) return;
        table.scheduled = true;
        try {
            workers.execute(() -> {
                boolean success = false;
                try {
                    for (int i = 0; i < MAX_AUTOMATIC_ACTIONS; i++) {
                        advanceOneAgent(table);
                        synchronized (table) {
                            if (oneStep || canAdvance(table) || table.execution.checkpoint().status() != TournamentStatus.IN_HAND
                                    || currentHand(table.execution).actor().playerId().equals(table.humanId)) break;
                        }
                    }
                    success = true;
                } catch (RuntimeException error) {
                    org.slf4j.LoggerFactory.getLogger(GameTableService.class).warn(
                            "Agent task stopped for table {} ({}: {}; at {})",
                            table.id.value(), error.getClass().getSimpleName(), error.getMessage(),
                            error.getStackTrace().length == 0 ? "unknown" : error.getStackTrace()[0]);
                } finally {
                    synchronized (table) {
                        table.scheduled = false;
                        // A human can act between the final agent commit and this task completing.
                        if (success && !oneStep) schedule(table, false);
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            table.scheduled = false;
        }
    }

    SseEmitter subscribe(String token, String tableId, long after) {
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            if (!table.id.value().toString().equals(tableId)) throw new TableSessionException(409, "当前牌桌已切换");
            List<TableView> missed = replayFrames(table, after, 201);
            SseEmitter emitter = streams.subscribe(table.tokenHash);
            if (after < 0 || after > table.sequence || missed.size() > 200) streams.send(table.tokenHash, emitter, "reset", project(table));
            else if (missed.isEmpty()) streams.send(table.tokenHash, emitter, "table", project(table));
            else missed.forEach(view -> streams.send(table.tokenHash, emitter, "table", view));
            schedule(table, table.stepRequested);
            return emitter;
        }
    }

    ReplayPage replay(String token, String tableId, long after) {
        RuntimeTable table = requireTable(token);
        synchronized (table) {
            if (!table.id.value().toString().equals(tableId)) throw new TableSessionException(403, "无权读取其他牌桌回放");
            if (after < 0) throw new TableSessionException(400, "回放序号不能为负数");
            List<TableView> frames = replayFrames(table, after, 101);
            boolean more = frames.size() > 100;
            List<TableView> page = frames.stream().limit(100).toList();
            return new ReplayPage(page, page.isEmpty() ? after : page.getLast().sequence(), more);
        }
    }

    private List<TableView> replayFrames(RuntimeTable table, long after, int limit) {
        return sessions.frames(table.tokenHash, after, limit).stream().map(frame -> json.readValue(frame.json(), TableView.class)).toList();
    }

    record ReplayPage(List<TableView> frames, long nextSequence, boolean hasMore) {}

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @PreDestroy void close() { cleanup.shutdownNow(); workers.shutdownNow(); }

    record SavedTable(TournamentId id, PlayerId humanId, String displayName, List<PlayerId> players,
                      List<String> personas, List<TableView.ActionLogView> actions, List<TableChatMessage> chat,
                      List<Card> lastBoard, List<Card> lastHumanHoleCards, long sequence, long actionSequence,
                      Map<UUID, String> receipts, Instant lastHumanChat, boolean stepRequested,
                      Map<Integer, AgentMemory> memories) {}

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
        private long sequence;
        private final String tokenHash;
        private final List<String> personas;
        private final Instant expiresAt;
        private final Map<UUID, String> receipts = new LinkedHashMap<>();
        private Instant lastHumanChat;
        private boolean scheduled;
        private boolean stepRequested;
        private final Map<Integer, AgentMemory> memories = new LinkedHashMap<>();

        private RuntimeTable(TournamentId id, PlayerId humanId, String displayName,
                List<PlayerId> players, TournamentExecution execution, String tokenHash, List<String> personas, Instant expiresAt) {
            this.id = id;
            this.humanId = humanId;
            this.displayName = displayName;
            this.players = List.copyOf(players);
            this.execution = execution;
            this.tokenHash = tokenHash;
            this.personas = List.copyOf(personas);
            this.expiresAt = expiresAt;
            for (int seat = 0; seat < personas.size(); seat++) memories.put(seat, AgentMemory.empty());
        }

        private SavedTable snapshot() {
            return new SavedTable(id, humanId, displayName, players, personas, List.copyOf(actions), List.copyOf(chat),
                    lastBoard, lastHumanHoleCards, sequence, actionSequence, Map.copyOf(receipts), lastHumanChat, stepRequested,
                    Map.copyOf(memories));
        }

        private void restore(SavedTable saved) {
            actions.clear(); actions.addAll(saved.actions());
            chat.clear(); chat.addAll(saved.chat());
            lastBoard = saved.lastBoard(); lastHumanHoleCards = saved.lastHumanHoleCards();
            sequence = saved.sequence(); actionSequence = saved.actionSequence();
            receipts.clear(); receipts.putAll(saved.receipts()); lastHumanChat = saved.lastHumanChat();
            stepRequested = saved.stepRequested();
            memories.clear();
            for (int seat = 0; seat < personas.size(); seat++) memories.put(seat, AgentMemory.empty());
            if (saved.memories() != null) memories.putAll(saved.memories());
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
