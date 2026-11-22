package veil.web;

import veil.core.VeilCoreEngine;
import veil.models.ChatMessage;
import veil.models.EntityCategory;
import veil.models.ProviderType;
import veil.models.SanitizationResult;
import veil.policies.PolicyEngine;
import veil.providers.ProviderException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory multi-turn conversations for the Salon room.
 *
 * Each conversation owns one vault session, so a given email or key keeps the same token for the
 * whole conversation. For every message we keep two forms: {@code content} (what the person sees:
 * their original words / the restored reply) and {@code wire} (what actually crossed to the provider:
 * tokens, never originals). History is re-sent in wire form, trimmed to a rolling character budget so
 * conversations can run for hundreds of turns. Nothing here is written to disk.
 */
final class ChatService {

    interface Sink {
        void send(Map<String, Object> message) throws IOException;
    }

    static final class Msg {
        final String role;
        final String content;
        final String wire;
        final Instant ts = Instant.now();
        int detected;
        int masked;
        Map<String, Integer> categories = new LinkedHashMap<>();
        String provider;
        String model;

        Msg(String role, String content, String wire) {
            this.role = role;
            this.content = content;
            this.wire = wire;
        }
    }

    static final class Convo {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final String sid = "chat-" + id;
        final Instant created = Instant.now();
        volatile Instant updated = created;
        String title = "New conversation";
        String system;
        String systemWire;
        final List<Msg> messages = new ArrayList<>();
        volatile boolean busy;
    }

    private static final int MAX_CONVERSATIONS = 50;
    private static final int MAX_MESSAGES = 4000;

    private final VeilCoreEngine core;
    private final Map<String, Convo> convos = Collections.synchronizedMap(new LinkedHashMap<>());

    ChatService(VeilCoreEngine core) {
        this.core = core;
    }

    // ───────────── CRUD ─────────────

    Map<String, Object> create(String system) {
        Convo c = new Convo();
        if (system != null && !system.isBlank()) {
            SanitizationResult res = core.sanitizeText(c.sid, system.trim(), core.getActiveProfile());
            if (res.isBlocked()) {
                throw new IllegalArgumentException("The scene was refused by your mask: " + res.getBlockReason());
            }
            c.system = system.trim();
            c.systemWire = res.getSanitizedText();
        }
        synchronized (convos) {
            while (convos.size() >= MAX_CONVERSATIONS) {
                String oldest = convos.values().stream().min(Comparator.comparing(x -> x.updated)).map(x -> x.id).orElse(null);
                if (oldest == null) break;
                delete(oldest);
            }
            convos.put(c.id, c);
        }
        return view(c, false);
    }

    List<Map<String, Object>> list() {
        List<Convo> all;
        synchronized (convos) {
            all = new ArrayList<>(convos.values());
        }
        all.sort(Comparator.comparing((Convo c) -> c.updated).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Convo c : all) {
            out.add(view(c, false));
        }
        return out;
    }

    Map<String, Object> get(String id) {
        Convo c = convos.get(id);
        return c == null ? null : view(c, true);
    }

    boolean delete(String id) {
        Convo c = convos.remove(id);
        if (c == null) {
            return false;
        }
        try {
            core.getTokenVault().clearSession(c.sid);
        } catch (RuntimeException ignored) {
            // vault may be locked; the mapping is gone with the lock anyway
        }
        return true;
    }

    private Map<String, Object> view(Convo c, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("title", c.title);
        m.put("session", c.sid);
        m.put("system", c.system);
        m.put("count", c.messages.size());
        m.put("updated", c.updated.toString());
        m.put("busy", c.busy);
        if (full) {
            List<Map<String, Object>> msgs = new ArrayList<>();
            synchronized (c) {
                for (Msg x : c.messages) {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("role", x.role);
                    mm.put("content", x.content);
                    if ("user".equals(x.role)) {
                        mm.put("wire", x.wire);
                    }
                    mm.put("ts", x.ts.toString());
                    mm.put("detected", x.detected);
                    mm.put("masked", x.masked);
                    mm.put("categories", x.categories);
                    mm.put("provider", x.provider);
                    mm.put("model", x.model);
                    msgs.add(mm);
                }
            }
            m.put("messages", msgs);
        }
        return m;
    }

    // ───────────── one turn ─────────────

    void turn(String id, String text, ProviderType provider, Sink sink) throws IOException {
        Convo c = convos.get(id);
        if (c == null) {
            sink.send(error("That conversation no longer exists.", 404, false));
            return;
        }
        if (text == null || text.isBlank()) {
            sink.send(error("Say something first.", 400, false));
            return;
        }
        synchronized (c) {
            if (c.busy) {
                sink.send(error("The model is still answering the previous message.", 409, false));
                return;
            }
            if (c.messages.size() >= MAX_MESSAGES) {
                sink.send(error("This conversation is full. Start a new one.", 413, false));
                return;
            }
            c.busy = true;
        }
        try {
            // Rolling window: newest messages first until the character budget is spent.
            List<ChatMessage> prior = new ArrayList<>();
            int trimmed;
            int budget = core.getConfigManager().getConfig().getChatContextChars();
            synchronized (c) {
                int start = c.messages.size();
                int used = 0;
                for (int i = c.messages.size() - 1; i >= 0; i--) {
                    int len = c.messages.get(i).wire.length();
                    if (used + len > budget && start < c.messages.size()) {
                        break;
                    }
                    used += len;
                    start = i;
                }
                while (start < c.messages.size() && "assistant".equals(c.messages.get(start).role)) {
                    start++; // never begin the window mid-exchange
                }
                trimmed = start;
                if (c.systemWire != null) {
                    prior.add(new ChatMessage("system", c.systemWire));
                }
                for (int i = start; i < c.messages.size(); i++) {
                    prior.add(new ChatMessage(c.messages.get(i).role, c.messages.get(i).wire));
                }
            }

            final int trimmedCount = trimmed;
            AtomicBoolean cancelled = new AtomicBoolean(false);
            VeilCoreEngine.TurnObserver observer = new VeilCoreEngine.TurnObserver() {
                @Override
                public void onSanitized(SanitizationResult res, ProviderType p, String model) {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("t", "meta");
                    meta.put("sanitized", res.getSanitizedText());
                    meta.put("detected", res.getDetectedEntities().size());
                    meta.put("masked", res.getMaskedCount());
                    meta.put("categories", displayCounts(PolicyEngine.countByCategory(res.getDetectedEntities())));
                    meta.put("trimmed", trimmedCount);
                    meta.put("provider", p.getDisplayName());
                    meta.put("model", model);
                    push(meta);
                }

                @Override
                public void onDelta(String restored) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("t", "delta");
                    d.put("d", restored);
                    push(d);
                }

                @Override
                public boolean cancelled() {
                    return cancelled.get();
                }

                private void push(Map<String, Object> m) {
                    try {
                        sink.send(m);
                    } catch (IOException e) {
                        cancelled.set(true); // the window went away or Stop was pressed
                        throw new UncheckedIOException(e);
                    }
                }
            };

            VeilCoreEngine.TurnResult r;
            try {
                r = core.runChatTurn(c.sid, prior, text, provider, "SALON", observer);
            } catch (UncheckedIOException gone) {
                return;
            }

            if (!(r.cancelled && r.restoredReply.isEmpty())) {
                Msg user = new Msg("user", text, r.sanitizedUser);
                user.detected = r.detected;
                user.masked = r.masked;
                user.categories = displayCounts(r.categories);
                Msg ai = new Msg("assistant", r.restoredReply, r.rawReply);
                ai.provider = r.provider.getDisplayName();
                ai.model = r.model;
                synchronized (c) {
                    if (c.messages.isEmpty()) {
                        c.title = titleOf(text);
                    }
                    c.messages.add(user);
                    c.messages.add(ai);
                    c.updated = Instant.now();
                }
            }
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("t", "done");
            done.put("reply", r.restoredReply);
            done.put("model", r.model);
            done.put("provider", r.provider.getDisplayName());
            done.put("cancelled", r.cancelled);
            done.put("count", c.messages.size());
            done.put("title", c.title);
            try {
                sink.send(done);
            } catch (IOException ignored) {
                // client already gone
            }
        } catch (ProviderException e) {
            safeSend(sink, error(e.getMessage(), e.getHttpStatus(), e.getHttpStatus() == 403));
        } catch (IllegalStateException e) {
            safeSend(sink, error(e.getMessage() == null ? "The vault is locked. Unlock it in Depths first." : e.getMessage(), 423, false));
        } finally {
            c.busy = false;
        }
    }

    // ───────────── helpers ─────────────

    private static void safeSend(Sink sink, Map<String, Object> m) {
        try {
            sink.send(m);
        } catch (IOException ignored) {
            // nothing more to do
        }
    }

    private static Map<String, Object> error(String message, int status, boolean blocked) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "error");
        m.put("message", message);
        m.put("status", status);
        m.put("blocked", blocked);
        return m;
    }

    private static Map<String, Integer> displayCounts(Map<EntityCategory, Integer> counts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        counts.forEach((k, v) -> out.put(k.getDisplayName(), v));
        return out;
    }

    private static String titleOf(String text) {
        String line = text.strip().split("\\R", 2)[0];
        return line.length() > 60 ? line.substring(0, 57) + "…" : line;
    }
}
