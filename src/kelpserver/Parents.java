package kelpserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static kelpserver.Http.problem;

/**
 * The Parent Portal. A kid (or teen) asks for a parent from Kelp; the parent gets an email with a link, opens it, sees
 * who's asking, picks which social features to allow, and approves. Kids below their region's consent age have
 * everything social off until a parent does this. Later, parents sign in with a link emailed to them (no password to
 * remember) and can change the switches, see WHO their kid messages (never the messages), or delete the account.
 */
public final class Parents {
    private static final long LINK_HOURS = 72;
    private static final long PARENT_SESSION_HOURS = 24;
    private final Store store;
    private final Config config;
    private final Mail mail;
    private final Social social;
    private final Runnable0 deleter;

    /** Deletes a player's account and everything of theirs. */
    public interface Runnable0 {
        void delete(String uuid) throws Exception;
    }

    private final Accounts accounts;

    public Parents(Store store, Config config, Mail mail, Accounts accounts, Social social, Runnable0 deleter) {
        this.store = store;
        this.config = config;
        this.mail = mail;
        this.accounts = accounts;
        this.social = social;
        this.deleter = deleter;
    }

    public void routes(Http.Router r) {
        r.setLimit("parents", 10);
        // From Kelp: ask a parent
        r.add("POST /api/me/parent", 2048, "parents", req -> {
            Map<String, Object> me = accounts.require(req);
            if ("adult".equals(me.get("group"))) throw problem(400, "Parent links are for players under 18.");
            String email = email(req.field(req.json(), "email", 254));
            String token = Secrets.token(24);
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("child", Players.uuid(me));
            link.put("email", email);
            link.put("expires", System.currentTimeMillis() + LINK_HOURS * 3_600_000);
            store.put("parentLinks", Secrets.sha256(token), link);
            mail.send(email, "Your kid wants to use Kelp's social features",
                    me.get("name") + " uses Kelp, a Minecraft launcher, and asked for you to manage their account.\n\n"
                            + "Open this link to see what they can do, choose what to allow, and approve:\n"
                            + config.publicUrl + "/parents/approve#" + token + "\n\n"
                            + "The link works for " + LINK_HOURS / 24 + " days. If you don't know what this is, ignore it: nothing changes.\n");
            return Http.Response.ok();
        });
        // The approve page asks what the link is for
        r.add("GET /api/parents/link/{token}", 0, "parents", req -> {
            Map<String, Object> link = link(req.value("token"));
            Map<String, Object> child = store.get("accounts", (String) link.get("child"));
            if (child == null) throw problem(404, "That account doesn't exist any more.");
            return Http.Response.json(Map.of("name", child.get("name"), "group", child.get("group"), "social", child.get("social")));
        });
        r.add("POST /api/parents/link/{token}/approve", 4096, "parents", req -> {
            String tokenHash = Secrets.sha256(Store.safeId(req.value("token")));
            Map<String, Object> link = link(req.value("token"));
            Map<String, Object> switches = Json.object(req.json().get("social"));
            String child = (String) link.get("child");
            if (!store.has("accounts", child)) throw problem(404, "That account doesn't exist any more.");
            store.update("accounts", child, a -> {
                a.put("parentEmail", link.get("email"));
                a.put("parentEmailHash", Secrets.sha256((String) link.get("email")));
                if (switches != null) a.put("social", cleanSwitches(switches));
                return a;
            });
            store.delete("parentLinks", tokenHash);
            return Http.Response.json(Map.of("session", parentSession((String) link.get("email"))));
        });
        // Parents sign in with an emailed link
        r.add("POST /api/parents/login", 2048, "parents", req -> {
            String email = email(req.field(req.json(), "email", 254));
            if (!children(email).isEmpty()) {
                String token = parentSession(email);
                mail.send(email, "Sign in to Kelp's Parent Portal", "Open this link to manage your kid's Kelp account:\n"
                        + config.publicUrl + "/parents/portal#" + token + "\n\nIt works for " + PARENT_SESSION_HOURS + " hours.\n");
            }
            // The same answer either way, so nobody can find out which emails have kids on Kelp
            return Http.Response.json(Map.of("sent", true));
        });
        r.add("GET /api/parents/children", 0, "parents", req -> {
            String email = parent(req);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> child : children(email)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uuid", child.get("uuid"));
                row.put("name", child.get("name"));
                row.put("group", child.get("group"));
                row.put("social", child.get("social"));
                row.put("profilePublic", child.get("profilePublic"));
                row.put("contacts", social.contacts(Players.uuid(child)));
                out.add(row);
            }
            return Http.Response.json(out);
        });
        r.add("PUT /api/parents/children/{uuid}", 4096, "parents", req -> {
            Map<String, Object> child = myChild(req);
            Map<String, Object> body = req.json();
            Map<String, Object> switches = Json.object(body.get("social"));
            store.update("accounts", Players.uuid(child), a -> {
                if (switches != null) a.put("social", cleanSwitches(switches));
                if (body.get("profilePublic") instanceof Boolean b) a.put("profilePublic", b);
                return a;
            });
            return Http.Response.ok();
        });
        r.add("DELETE /api/parents/children/{uuid}", 0, "parents", req -> {
            Map<String, Object> child = myChild(req);
            deleter.delete(Players.uuid(child));
            return Http.Response.ok();
        });
    }

    private Map<String, Object> link(String token) {
        Map<String, Object> link = store.get("parentLinks", Secrets.sha256(Store.safeId(token)));
        if (link == null || ((Number) link.get("expires")).longValue() < System.currentTimeMillis()) {
            throw problem(404, "That link has expired. Ask your kid to send a new one from Kelp.");
        }
        return link;
    }

    private String parentSession(String email) throws java.io.IOException {
        String token = Secrets.token(24);
        store.put("parentSessions", Secrets.sha256(token), Map.of("email", email, "expires", System.currentTimeMillis() + PARENT_SESSION_HOURS * 3_600_000));
        return token;
    }

    /** The signed-in parent's email. */
    private String parent(Http.Request req) {
        String token = Accounts.bearer(req);
        Map<String, Object> s = null;
        if (token != null && token.length() <= 64) {
            try {
                s = store.get("parentSessions", Secrets.sha256(token));
            } catch (IllegalArgumentException e) {
                s = null;
            }
        }
        if (s == null || ((Number) s.get("expires")).longValue() < System.currentTimeMillis()) throw problem(401, "Sign in again with a new link.");
        return (String) s.get("email");
    }

    private Map<String, Object> myChild(Http.Request req) {
        String email = parent(req);
        String id = Social.clean(req.value("uuid"));
        for (Map<String, Object> child : children(email)) if (id.equals(child.get("uuid"))) return child;
        throw problem(404, "That isn't one of your kids' accounts.");
    }

    private List<Map<String, Object>> children(String email) {
        String hash = Secrets.sha256(email);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : store.all("accounts")) if (hash.equals(a.get("parentEmailHash"))) out.add(a);
        return out;
    }

    static Map<String, Object> cleanSwitches(Map<String, Object> asked) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String f : Rules.FEATURES) out.put(f, Boolean.TRUE.equals(asked.get(f)));
        return out;
    }

    static String email(String raw) {
        String e = raw.strip().toLowerCase(Locale.ROOT);
        if (!e.matches("[^\\s@<>\"]{1,64}@[^\\s@<>\"]{1,190}\\.[a-z]{2,}")) throw problem(400, "That email address doesn't look right.");
        return e;
    }
}
