package com.agenttavern.web;

import static org.assertj.core.api.Assertions.*;
import com.agenttavern.agents.*;
import com.agenttavern.game.betting.*;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.card.Rank;
import com.agenttavern.game.card.Suit;
import com.agenttavern.tournament.*;
import com.agenttavern.tournament.application.TournamentCommandService;
import com.agenttavern.tournament.port.TournamentCommit;
import com.agenttavern.web.port.TableSessionStore;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class GameTableServiceTest {
    final RuntimeTournamentStore store = new RuntimeTournamentStore();
    final MemoryTableSessionStore sessions = new MemoryTableSessionStore();
    final Clock clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"),ZoneOffset.UTC);
    final JsonMapper json = JsonMapper.builder().build();
    final List<GameTableService> services = new ArrayList<>();
    final List<TableStreamHub> hubs = new ArrayList<>();

    GameTableService service(AgentDecisionProvider provider, TableSessionStore archive) {
        return service(provider, archive, new TableStreamHub());
    }
    GameTableService service(AgentDecisionProvider provider, TableSessionStore archive, TableStreamHub hub) {
        return service(provider, archive, hub, 42);
    }
    GameTableService service(AgentDecisionProvider provider, TableSessionStore archive, int deckSeed) {
        return service(provider, archive, new TableStreamHub(), deckSeed);
    }
    GameTableService service(AgentDecisionProvider provider, TableSessionStore archive, TableStreamHub hub, int deckSeed) {
        return service(provider, archive, hub, () -> Deck.standard().shuffled(new Random(deckSeed)), 11);
    }
    GameTableService service(AgentDecisionProvider provider, TableSessionStore archive,
            Supplier<Deck> decks, int tableSeed) {
        return service(provider, archive, new TableStreamHub(), decks, tableSeed);
    }
    GameTableService service(AgentDecisionProvider provider, TableSessionStore archive, TableStreamHub hub,
            Supplier<Deck> decks, int tableSeed) {
        hubs.add(hub);
        var commands = new TournamentCommandService(store,clock,decks);
        var service = new GameTableService(commands,provider,store,archive,json,clock,
                new StaticListableBeanFactory().getBeanProvider(PlatformTransactionManager.class),hub,new Random(tableSeed));
        services.add(service); return service;
    }
    static AgentDecision call(AgentObservation o) {
        return new AgentDecision(o.legalActions().types().contains(ActionType.CHECK)?PlayerAction.check():PlayerAction.call(),
                "先看看。",AgentEmotion.CALM,"继续观察",List.of());
    }
    static AgentDecision allIn(AgentObservation o) {
        return new AgentDecision(PlayerAction.allIn(o.legalActions().maxRaiseTo()),"来吧。",AgentEmotion.CONFIDENT,"全下",List.of());
    }
    static TableCommand command(TableView v) {return new TableCommand(UUID.randomUUID(),v.tableId(),v.version());}
    static PlayerActionRequest action(TableView v,String type) {return new PlayerActionRequest(type,null,UUID.randomUUID(),v.tableId(),v.version());}
    static String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static Deck immediateSettlementDeck() {
        return Deck.ordered(List.of(
                new Card(Suit.CLUBS, Rank.TWO), new Card(Suit.SPADES, Rank.ACE),
                new Card(Suit.DIAMONDS, Rank.THREE), new Card(Suit.HEARTS, Rank.ACE),
                new Card(Suit.CLUBS, Rank.FOUR),
                new Card(Suit.CLUBS, Rank.FIVE), new Card(Suit.DIAMONDS, Rank.SEVEN),
                new Card(Suit.HEARTS, Rank.NINE),
                new Card(Suit.CLUBS, Rank.TEN), new Card(Suit.DIAMONDS, Rank.JACK),
                new Card(Suit.CLUBS, Rank.QUEEN), new Card(Suit.DIAMONDS, Rank.KING)));
    }
    static TableView await(GameTableService service,String token,Predicate<TableView> condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        do {TableView v=service.current(token);if(condition.test(v))return v;Thread.sleep(5);}while(System.nanoTime()<end);
        throw new AssertionError("Timed out waiting for table state");
    }
    static TableView advanceWhenIdle(GameTableService service,String token,TableCommand command)
            throws InterruptedException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(true) {
            try {
                return service.advanceSpectator(token,command);
            } catch(TableSessionException busy) {
                if(busy.status()!=409 || !"Agent 正在思考，请稍候".equals(busy.getMessage())) throw busy;
                // A committed version can be read just before the worker releases its busy flag.
                // Retry the same command so a previously accepted step remains idempotent.
                if(System.nanoTime()>=end) throw new AssertionError("Timed out waiting for idle Agent",busy);
                Thread.sleep(5);
            }
        }
    }
    @AfterEach void close() {services.forEach(GameTableService::close);hubs.forEach(TableStreamHub::close);}

    @Test void duplicateActionDoesNotActAgainAndStaleVersionIsRejected() throws Exception {
        var svc=service(GameTableServiceTest::call,sessions);var created=svc.createTable("旅人","PLAYER",null);
        var turn=await(svc,created.sessionToken(),v->Objects.equals(v.actorSeat(),5));
        var action=action(turn,"CALL");svc.act(created.sessionToken(),action);
        var next=await(svc,created.sessionToken(),v->v.version()>turn.version()&&Objects.equals(v.actorSeat(),5));
        var duplicate=svc.act(created.sessionToken(),action);
        assertThat(duplicate.version()).isEqualTo(next.version());assertThat(duplicate.sequence()).isEqualTo(next.sequence());
        assertThatThrownBy(()->svc.act(created.sessionToken(),action(turn,"CALL"))).isInstanceOf(TableSessionException.class).hasMessageContaining("已更新");
    }

    @Test void failedNotificationCannotUndoACommittedTable() {
        var brokenHub = new TableStreamHub() {
            @Override void publish(String hash, TableView view) {
                throw new IllegalStateException("disconnected stream");
            }
        };
        var svc = service(GameTableServiceTest::call, sessions, brokenHub);
        var created = svc.createTable("", "SPECTATOR", null);
        assertThat(svc.current(created.sessionToken())).isEqualTo(created.view());
        assertThat(svc.replay(created.sessionToken(), created.view().tableId(), 0).frames())
                .containsExactly(created.view());
    }

    @Test void slowAgentDoesNotBlockReadsOrChat() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        var svc=service(o->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return call(o);},sessions);
        var created=svc.createTable("旅人","PLAYER",null);
        try {
            assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            var view=svc.current(created.sessionToken());
            var chat=svc.talk(created.sessionToken(),new TableTalkRequest("慢慢想",UUID.randomUUID(),view.tableId(),view.version()));
            assertThat(chat.chat()).extracting(TableView.ChatView::text).contains("慢慢想");
            assertThat(chat.version()).isEqualTo(view.version());assertThat(chat.sequence()).isGreaterThan(view.sequence());
        } finally {release.countDown();}
    }

    @Test void restartRestoresMetadataChatReceiptsAndReplay() throws Exception {
        var svc=service(GameTableServiceTest::call,sessions);var created=svc.createTable("恢复旅人","PLAYER",null);
        var turn=await(svc,created.sessionToken(),v->Objects.equals(v.actorSeat(),5));
        var request=new TableTalkRequest("保存这句话",UUID.randomUUID(),turn.tableId(),turn.version());
        var saved=svc.talk(created.sessionToken(),request);svc.close();
        var restored=service(GameTableServiceTest::call,sessions);
        var current=restored.current(created.sessionToken());
        assertThat(current).isEqualTo(saved);assertThat(restored.talk(created.sessionToken(),request)).isEqualTo(saved);
        var replay=restored.replay(created.sessionToken(),current.tableId(),0);
        assertThat(replay.frames().getLast()).isEqualTo(saved);
        assertThat(json.writeValueAsString(replay)).doesNotContain("deck","burnedCards","playerId");
    }

    @Test void expiredTableAndArchiveAreReleasedAfterRetentionWindow() throws Exception {
        var hub = new TableStreamHub();
        var svc = service(GameTableServiceTest::call, sessions, hub);
        var created = svc.createTable("清理验收", "PLAYER", null);
        await(svc, created.sessionToken(), view -> Objects.equals(view.actorSeat(), 5));
        var id = new com.agenttavern.tournament.TournamentId(UUID.fromString(created.view().tableId()));
        assertThat(store.load(id)).isPresent();
        assertThat(svc.cleanupExpired(clock.instant().plus(Duration.ofHours(33)))).isEqualTo(2);
        assertThat(store.load(id)).isEmpty();
        assertThatThrownBy(() -> svc.current(created.sessionToken()))
                .isInstanceOf(TableSessionException.class).hasMessageContaining("失效");
    }

    @Test void failedArchiveCommitRollsBackDomainAndDoesNotCreateAFrame() throws Exception {
        var fail=new java.util.concurrent.atomic.AtomicBoolean();
        TableSessionStore archive=new TableSessionStore(){
            public Optional<Session> find(String hash){return sessions.find(hash);}
            public List<Frame> frames(String hash,long after,int limit){return sessions.frames(hash,after,limit);}
            public List<UUID> purgeExpired(Instant cutoff){return sessions.purgeExpired(cutoff);}
            public void save(Session s,long expected,Frame f){if(fail.get())throw new IllegalStateException("disk unavailable");sessions.save(s,expected,f);}
        };
        var svc=service(GameTableServiceTest::call,archive);var created=svc.createTable("旅人","PLAYER",null);
        var turn=await(svc,created.sessionToken(),v->Objects.equals(v.actorSeat(),5));fail.set(true);
        assertThatThrownBy(()->svc.act(created.sessionToken(),action(turn,"CALL"))).hasMessage("disk unavailable");
        assertThat(svc.current(created.sessionToken())).isEqualTo(turn);
        fail.set(false);assertThat(svc.act(created.sessionToken(),action(turn,"CALL")).version()).isGreaterThan(turn.version());
    }

    @Test void fullAllInTournamentKeepsBoardChipsRankingsAndWinnerChatBoundaryValid() throws Exception {
        var svc=service(GameTableServiceTest::allIn,sessions,45);var created=svc.createTable("旅人","PLAYER",null);String token=created.sessionToken();
        TableView view=created.view();TableTalkRequest acceptedChat=null;
        for(int i=0;i<100&&!view.status().equals("COMPLETE");i++) {
            view=await(svc,token,v->!v.status().equals("IN_HAND")||v.canAdvance()||Objects.equals(v.actorSeat(),5));
            if(view.status().equals("COMPLETE"))break;
            if(view.status().equals("BETWEEN_HANDS")) {
                assertThat(view.board()).hasSize(5);view=svc.nextHand(token,command(view));
            } else if(view.canAdvance()) {
                long version=view.version();advanceWhenIdle(svc,token,command(view));view=await(svc,token,v->v.version()>version);
            } else {
                if(acceptedChat==null) {
                    acceptedChat=new TableTalkRequest("胜负桌上见",UUID.randomUUID(),view.tableId(),view.version());
                    view=svc.talk(token,acceptedChat);
                }
                view=svc.act(token,action(view,"ALL_IN"));
            }
            assertThat(view.seats().stream().mapToLong(TableView.SeatView::stack).sum()+view.pot()).isEqualTo(60000);
        }
        assertThat(view.status()).isEqualTo("COMPLETE");assertThat(view.board()).hasSize(5);
        assertThat(view.holeCards()).isEmpty();
        assertThat(view.rankings()).extracting(TableView.RankingView::position).containsExactlyInAnyOrder(1,2,3,4,5,6);
        assertThat(view.seats().get(view.selfSeat()).status()).isEqualTo("WINNER");
        assertThat(acceptedChat).isNotNull();
        assertThat(svc.talk(token,acceptedChat)).isEqualTo(svc.current(token));
        var completed=view;
        assertThatThrownBy(()->svc.talk(token,new TableTalkRequest("冠军发言",UUID.randomUUID(),
                completed.tableId(),completed.version())))
                .isInstanceOf(TableSessionException.class)
                .hasMessage("锦标赛已结束，不能继续发言");
        assertThatThrownBy(()->svc.nextHand(token,command(svc.current(token)))).hasMessageContaining("不能开始");
    }

    @Test void nextHandThatSettlesFromBlindsArchivesItsActualBoardOutcomeAndPrivateCards() throws Exception {
        var svc=service(GameTableServiceTest::call,sessions,
                ()->Deck.standard().shuffled(new Random(42)),3);
        var created=svc.createTable("旅人","PLAYER",null);
        String token=created.sessionToken();
        assertThat(created.view().actorSeat()).isEqualTo(5); // Prevents background agent work while arranging the boundary.
        var session=sessions.find(tokenHash(token)).orElseThrow();
        var saved=json.readValue(session.metadata(),GameTableService.SavedTable.class);
        var seededSeats=new ArrayList<TournamentSeat>();
        for(int seat=0;seat<6;seat++) {
            if(seat==0) seededSeats.add(new TournamentSeat(saved.players().get(seat),seat,1,
                    TournamentSeatStatus.FUNDED,null));
            else if(seat==5) seededSeats.add(new TournamentSeat(saved.players().get(seat),seat,59_999,
                    TournamentSeatStatus.FUNDED,null));
            else seededSeats.add(new TournamentSeat(saved.players().get(seat),seat,0,
                    TournamentSeatStatus.ELIMINATED,7-seat));
        }
        var checkpoint=new TournamentCheckpoint(saved.id(),TournamentMode.PLAYER,TournamentStatus.BETWEEN_HANDS,
                seededSeats,4,112,14,Optional.empty(),Map.of());
        Tournament.restore(checkpoint); // The fixture is a valid durable state, not a service-only shortcut.
        var stored=store.load(saved.id()).orElseThrow();
        store.commit(new TournamentCommit(UUID.randomUUID(),stored.version(),checkpoint,List.of()));
        svc.close();

        var resumed=service(GameTableServiceTest::call,sessions,GameTableServiceTest::immediateSettlementDeck,11);
        var between=resumed.current(token);
        assertThat(between.status()).isEqualTo("BETWEEN_HANDS");
        var terminal=resumed.nextHand(token,command(between));

        assertThat(terminal.status()).isEqualTo("COMPLETE");
        assertThat(terminal.handNumber()).isEqualTo(113);
        assertThat(terminal.board()).containsExactly(
                new TableView.CardView("5","♣"),new TableView.CardView("7","♦"),
                new TableView.CardView("9","♥"),new TableView.CardView("J","♦"),
                new TableView.CardView("K","♦"));
        assertThat(terminal.holeCards()).isEmpty();
        assertThat(terminal.seats().stream().mapToLong(TableView.SeatView::stack).sum()).isEqualTo(60_000L);
        assertThat(terminal.seats().get(0).status()).isEqualTo("ELIMINATED");
        assertThat(terminal.seats().get(0).emotion()).isEqualTo("NERVOUS");
        assertThat(terminal.seats().get(5).status()).isEqualTo("WINNER");
        assertThat(terminal.actionLog()).extracting(TableView.ActionLogView::action)
                .endsWith("开始第 113 手牌","本手结算");
        assertThat(terminal.actionLog().getLast().summary()).isEqualTo("旅人收下 6001 筹码。");

        var terminalSession=sessions.find(tokenHash(token)).orElseThrow();
        var terminalState=json.readValue(terminalSession.metadata(),GameTableService.SavedTable.class);
        assertThat(terminalState.lastBoard()).hasSize(5);
        assertThat(terminalState.lastHumanHoleCards()).containsExactly(
                new Card(Suit.SPADES,Rank.ACE),new Card(Suit.HEARTS,Rank.ACE));
        assertThat(terminalSession.metadata()).doesNotContain("\"rank\":\"TWO\"","\"rank\":\"THREE\"");

        resumed.close();
        var restarted=service(GameTableServiceTest::call,sessions,GameTableServiceTest::immediateSettlementDeck,11);
        assertThat(restarted.current(token)).isEqualTo(terminal);
        var replay=restarted.replay(token,terminal.tableId(),0);
        assertThat(replay.frames().getLast()).isEqualTo(terminal);
        assertThat(replay.frames()).filteredOn(frame->frame.sequence()>=terminal.sequence())
                .allSatisfy(frame->{
                    assertThat(frame.handNumber()).isEqualTo(113);
                    assertThat(frame.board()).hasSize(5);
                    assertThat(frame.holeCards()).isEmpty();
                    assertThat(frame.actionLog()).extracting(TableView.ActionLogView::action)
                            .endsWith("开始第 113 手牌","本手结算");
                });
    }

    @Test void eliminatedHumanCanStartAndWatchTheNextHandWithoutPrivateCards() throws Exception {
        AgentDecisionProvider provider=o -> o.persona().equals(AgentRoster.all().getFirst())
                ? new AgentDecision(PlayerAction.fold(),"先歇一手。",AgentEmotion.CALM,"弃牌",List.of())
                : allIn(o);
        var svc=service(provider, sessions);
        var created=svc.createTable("淘汰验收","PLAYER",null);
        String token=created.sessionToken();
        var turn=await(svc,token,v->Objects.equals(v.actorSeat(),5));
        assertThat(turn.holeCards()).hasSize(2);
        svc.act(token,action(turn,"ALL_IN"));
        var ended=await(svc,token,v->!v.status().equals("IN_HAND"));
        assertThat(ended.status()).isEqualTo("BETWEEN_HANDS");
        assertThat(ended.seats().get(5).status()).isEqualTo("ELIMINATED");
        assertThat(ended.holeCards()).isEmpty();
        assertThat(ended.canAdvance()).isTrue();
        assertThatThrownBy(() -> svc.talk(token, new TableTalkRequest("场外施压", UUID.randomUUID(),
                ended.tableId(), ended.version())))
                .isInstanceOf(TableSessionException.class)
                .hasMessage("已淘汰玩家只能观战，不能继续影响牌桌");
        svc.close();
        var restored=service(provider,sessions);
        assertThat(restored.current(token)).isEqualTo(ended);
        assertThat(restored.current(token).holeCards()).isEmpty();
        var next=restored.nextHand(token,command(ended));
        assertThat(next.holeCards()).isEmpty();
        assertThat(next.legalActions().types()).isEmpty();
        assertThat(next.canAdvance()).isTrue();
        advanceWhenIdle(restored,token,command(next));
        assertThat(await(restored,token,v->v.version()>next.version()).version()).isEqualTo(next.version()+1);
        var frames=restored.replay(token,ended.tableId(),0).frames();
        assertThat(frames).filteredOn(frame->frame.sequence()==turn.sequence()).singleElement()
                .satisfies(frame->assertThat(frame.holeCards()).hasSize(2));
        assertThat(frames).filteredOn(frame->frame.sequence()>=ended.sequence())
                .allSatisfy(frame->assertThat(frame.holeCards()).isEmpty());
    }

    @Test void localAgentsPlaySeveralHandsWithoutOpeningWithCollectiveStackCalls() throws Exception {
        var provider=new LocalAgentDecisionProvider();
        var svc=service(o->{var decision=provider.decide(o);
            assertThat(AgentDecisionPolicy.isLegal(o.legalActions(),decision.action())).isTrue();
            return decision;},sessions);
        var created=svc.createTable("","SPECTATOR",null);var view=created.view();
        for(int hand=0;hand<5;hand++) {
            for(int action=0;action<150&&view.status().equals("IN_HAND");action++) {
                long version=view.version();advanceWhenIdle(svc,created.sessionToken(),command(view));
                view=await(svc,created.sessionToken(),v->v.version()>version);
            }
            assertThat(view.status()).isNotEqualTo("IN_HAND");
            assertThat(view.seats().stream().mapToLong(TableView.SeatView::stack).sum()).isEqualTo(60000);
            if(hand==0) assertThat(view.seats().stream().filter(s->s.stack()>0).count()).isGreaterThanOrEqualTo(3);
            if(view.status().equals("COMPLETE")) break;
            if(hand<4) view=svc.nextHand(created.sessionToken(),command(view));
        }
        assertThat(view.handNumber()).isGreaterThan(1);
    }

    @Test void privateAgentMemorySurvivesNextHandAndServiceRestartWithoutLeakingIntoReplay() throws Exception {
        var observations=new java.util.concurrent.LinkedBlockingQueue<AgentObservation>();
        AgentDecisionProvider provider=o->{observations.add(o);var decision=call(o);
            return new AgentDecision(decision.action(),"这句话不要重复",AgentEmotion.SUSPICIOUS,"公开说明",
                    List.of("private-note-"+o.persona().key()));};
        var svc=service(provider,sessions);var created=svc.createTable("","SPECTATOR",null);var view=created.view();
        for(int i=0;i<40&&view.status().equals("IN_HAND");i++) {
            long version=view.version();advanceWhenIdle(svc,created.sessionToken(),command(view));
            view=await(svc,created.sessionToken(),v->v.version()>version);
        }
        assertThat(view.status()).isEqualTo("BETWEEN_HANDS");
        assertThat(observations).anySatisfy(o->assertThat(o.memory().notes()).containsOnly("private-note-"+o.persona().key()));
        assertThat(observations).allSatisfy(o->assertThat(o.memory().notes())
                .allMatch(note->note.equals("private-note-"+o.persona().key())));
        assertThat(view.chat()).hasSize(6); // identical model utterance is said once per agent
        assertThat(view.seats()).anySatisfy(s->assertThat(s.emotion()).isEqualTo("DELIGHTED"));
        svc.close();var restored=service(provider,sessions);
        assertThat(restored.current(created.sessionToken())).isEqualTo(view);
        var next=restored.nextHand(created.sessionToken(),command(view));observations.clear();
        advanceWhenIdle(restored,created.sessionToken(),command(next));
        var observation=observations.poll(5,TimeUnit.SECONDS);assertThat(observation).isNotNull();
        assertThat(observation.memory().notes()).contains("private-note-"+observation.persona().key());
        assertThat(observation.memory().opponents()).isNotEmpty();
        var committed=await(restored,created.sessionToken(),v->v.version()>next.version());
        assertThat(json.writeValueAsString(restored.replay(created.sessionToken(),committed.tableId(),0)))
                .doesNotContain("private-note", "opponents", "recentUtterances", "memoryUpdates");
        var other=restored.createTable("","SPECTATOR",null);observations.clear();
        advanceWhenIdle(restored,other.sessionToken(),command(other.view()));
        assertThat(observations.poll(5,TimeUnit.SECONDS).memory()).isEqualTo(AgentMemory.empty());
    }
}
