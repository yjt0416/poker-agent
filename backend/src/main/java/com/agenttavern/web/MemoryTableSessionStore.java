package com.agenttavern.web;

import com.agenttavern.web.port.TableSessionStore;
import java.util.*;

final class MemoryTableSessionStore implements TableSessionStore {
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, List<Frame>> history = new HashMap<>();
    public synchronized Optional<Session> find(String hash) { return Optional.ofNullable(sessions.get(hash)); }
    public synchronized void save(Session session, long expected, Frame frame) {
        long actual = find(session.tokenHash()).map(Session::sequence).orElse(0L);
        if (actual != expected) throw new ConcurrentModificationException("session version changed");
        sessions.put(session.tokenHash(), session);
        history.computeIfAbsent(session.tokenHash(), ignored -> new ArrayList<>()).add(frame);
    }
    public synchronized List<Frame> frames(String hash, long after, int limit) {
        return history.getOrDefault(hash, List.of()).stream().filter(f -> f.sequence() > after).limit(limit).toList();
    }
}
