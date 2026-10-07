package kelpserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static kelpserver.Http.problem;

/**
 * Friends, blocking and direct messages, with the safety rules built in:
 * - Messages only go between friends, and only if both are allowed to message (kids' parents decide).
 * - Becoming friends takes both players: one asks, the other accepts.
 * - Blocking stops requests and messages both ways, and ends the friendship.
 * - Every message goes through the {@link Filter}: no swearing, phone numbers, emails, addresses or links.
 * - Messages are kept (so reported ones can be checked), but parents only ever see WHO their kid talks to.
 */
public final class Social {
    private final Store store;
    private final Accounts accounts;

    public Social(Store store, Accounts accounts) {
        this.store = store;
        this.accounts = accounts;
    }

    public void routes(Http.Router r) {
        r.setLimit("social", 60);
        r.add("GET /api/friends", 0, "social", req -> {
            Map<String, Object> me = accounts.require(req);
            String id = Players.uuid(me);
            List<Map<String, Object>> friends = new ArrayList<>();
            List<Map<String, Object>> incoming = new ArrayList<>();
            List<Map<String, Object>> outgoing = new ArrayList<>();
            for (Map<String, Object> f : store.all("friends")) {
                String a = (String) f.get("a");
                String b = (String) f.get("b");
                if (!id.equals(a) && !id.equals(b)) continue;
                String other = id.equals(a) ? b : a;
                Map<String, Object> o = store.get("accounts", other);
                if (o == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uuid", other);
                row.put("name", o.get("name"));
                if ("friends".equals(f.get("status"))) friends.add(row);
                else if (id.equals(f.get("asker"))) outgoing.add(row);
                else incoming.add(row);
            }
            return Http.Response.json(Map.of("friends", friends, "incoming", incoming, "outgoing", outgoing));
        });
        r.add("POST /api/friends/request", 1024, "social", req -> {
            Map<String, Object> me = accounts.require(req);
            if (!Accounts.allowed(me, "dms")) throw problem(403, "Friends and messages are off for your account. A parent can turn them on.");
            String name = req.field(req.json(), "name", 16);
            Map<String, Object> other = null;
            for (Map<String, Object> a : store.all("accounts")) if (name.equalsIgnoreCase((String) a.get("name"))) other = a;
            if (other == null || Players.uuid(other).equals(Players.uuid(me))) throw problem(404, "No player called that.");
            if (blocked(Players.uuid(me), Players.uuid(other))) throw problem(404, "No player called that."); // a block looks like nobody's there
            if (!Accounts.allowed(other, "dms")) throw problem(403, "That player can't add friends right now.");
            String key = pair(Players.uuid(me), Players.uuid(other));
            Map<String, Object> existing = store.get("friends", key);
            if (existing != null) {
                if ("friends".equals(existing.get("status"))) return Http.Response.json(Map.of("status", "friends"));
                if (!Players.uuid(me).equals(existing.get("asker"))) { // they asked us already: this accepts
                    store.update("friends", key, f -> {
                        f.put("status", "friends");
                        return f;
                    });
                    return Http.Response.json(Map.of("status", "friends"));
                }
                return Http.Response.json(Map.of("status", "asked"));
            }
            long asked = store.all("friends").stream().filter(f -> Players.uuid(me).equals(f.get("asker")) && "asked".equals(f.get("status"))).count();
            if (asked >= 50) throw problem(429, "You have lots of friend requests waiting. Wait for some answers first.");
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("a", key.split("_")[0]);
            f.put("b", key.split("_")[1]);
            f.put("asker", Players.uuid(me));
            f.put("status", "asked");
            f.put("created", System.currentTimeMillis());
            store.put("friends", key, f);
            return Http.Response.json(Map.of("status", "asked"));
        });
        r.add("POST /api/friends/{uuid}/accept", 1024, "social", req -> {
            Map<String, Object> me = accounts.require(req);
            String key = pair(Players.uuid(me), clean(req.value("uuid")));
            Map<String, Object> f = store.get("friends", key);
            if (f == null || Players.uuid(me).equals(f.get("asker"))) throw problem(404, "No request from that player.");
            if (!Accounts.allowed(me, "dms")) throw problem(403, "Friends and messages are off for your account. A parent can turn them on.");
            store.update("friends", key, x -> {
                x.put("status", "friends");
                return x;
            });
            return Http.Response.ok();
        });
        r.add("DELETE /api/friends/{uuid}", 0, "social", req -> {
            Map<String, Object> me = accounts.require(req);
            store.delete("friends", pair(Players.uuid(me), clean(req.value("uuid"))));
            return Http.Response.ok();
        });
        r.add("POST /api/blocks/{uuid}", 1024, "social", req -> {
            Map<String, Object> me = accounts.require(req);
            String other = clean(req.value("uuid"));
            store.put("blocks", Players.uuid(me) + "_" + other, Map.of("by", Players.uuid(me), "who", other));
            store.delete("friends", pair(Players.uuid(me), other));
            return Http.Response.ok();
        });
        r.add("DELETE /api/blocks/{uuid}", 0, "social", req -> {
            Map<String, Object> me = accounts.require(req);
            store.delete("blocks", Players.uuid(me) + "_" + clean(req.value("uuid")));
            return Http.Response.ok();
        });
        r.setLimit("message", 30);
        r.add("POST /api/messages/{uuid}", 4096, "message", req -> {
            Map<String, Object> me = accounts.require(req);
            String other = clean(req.value("uuid"));
            Map<String, Object> them = store.get("accounts", other);
            if (them == null || !canMessage(me, them)) throw problem(403, "You can only message friends who can get messages.");
            String text = req.field(req.json(), "text", 500);
            String why = Filter.problem(text);
            if (why != null) throw problem(400, why);
            String conversation = pair(Players.uuid(me), other);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("from", Players.uuid(me));
            message.put("text", text);
            message.put("time", System.currentTimeMillis());
            store.update("messages", conversation, c -> {
                List<Object> list = Json.array(c.get("list"));
                if (list == null) list = new ArrayList<>();
                list.add(message);
                while (list.size() > 500) list.remove(0); // the newest 500 are kept
                c.put("list", list);
                c.put("last", System.currentTimeMillis());
                return c;
            });
            return Http.Response.ok();
        });
        r.add("GET /api/messages/{uuid}", 0, "social", req -> {
            Map<String, Object> me = accounts.require(req);
            String other = clean(req.value("uuid"));
            Map<String, Object> c = store.get("messages", pair(Players.uuid(me), other));
            long since = req.query("since") == null ? 0 : Long.parseLong(req.query("since"));
            List<Object> out = new ArrayList<>();
            if (c != null && !blocked(Players.uuid(me), other)) {
                for (Object o : Json.array(c.get("list"))) {
                    if (((Number) Json.object(o).get("time")).longValue() > since) out.add(o);
                }
            }
            return Http.Response.json(out);
        });
    }

    /** Whether two players can message: friends, neither blocked, both allowed to message. */
    boolean canMessage(Map<String, Object> me, Map<String, Object> them) {
        if (!Accounts.allowed(me, "dms") || !Accounts.allowed(them, "dms")) return false;
        String a = Players.uuid(me);
        String b = Players.uuid(them);
        if (blocked(a, b)) return false;
        Map<String, Object> f = store.get("friends", pair(a, b));
        return f != null && "friends".equals(f.get("status"));
    }

    boolean blocked(String a, String b) {
        return store.has("blocks", a + "_" + b) || store.has("blocks", b + "_" + a);
    }

    /** Who a player messages (for their parent): names and when they last talked, never the messages. */
    List<Map<String, Object>> contacts(String uuid) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : store.all("friends")) {
            if (!"friends".equals(f.get("status"))) continue;
            String other = uuid.equals(f.get("a")) ? (String) f.get("b") : uuid.equals(f.get("b")) ? (String) f.get("a") : null;
            if (other == null) continue;
            Map<String, Object> o = store.get("accounts", other);
            Map<String, Object> c = store.get("messages", pair(uuid, other));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", other);
            row.put("name", o == null ? "?" : o.get("name"));
            row.put("lastTalked", c == null ? null : c.get("last"));
            out.add(row);
        }
        out.sort(Comparator.comparingLong((Map<String, Object> row) -> row.get("lastTalked") instanceof Number n ? n.longValue() : 0).reversed());
        return out;
    }

    /** Removes a player's friendships, blocks and messages (when they delete their account). */
    void deleteEverythingOf(String uuid) throws IOException {
        for (String collection : new String[] {"friends", "blocks", "messages"}) {
            for (String id : store.ids(collection)) if (id.contains(uuid)) store.delete(collection, id);
        }
    }

    /** One id for a pair of players, whichever way round. */
    static String pair(String a, String b) {
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    static String clean(String uuid) {
        String c = uuid.replace("-", "").toLowerCase();
        if (!c.matches("[0-9a-f]{32}")) throw problem(404, "No such player.");
        return c;
    }
}
