package kelpserver;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static kelpserver.Http.problem;

/**
 * Accounts, signing in, and who may do what.
 *
 * Signing in uses Minecraft's own proof, the way game servers do: Kelp asks for a challenge, tells Mojang "this
 * player is joining the server called <challenge>" with the player's own Minecraft token (which never comes here),
 * then the server asks Mojang whether that player joined it. If yes, they really own that Minecraft account. The first
 * time, they also give their birth year and region, and get a recovery code to keep.
 *
 * Sessions are random tokens; only their fingerprints are stored, so a copy of the server's files can't sign anyone in.
 */
public final class Accounts {
    private static final long SESSION_DAYS = 90;
    private final Store store;
    private final Config config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Long> challenges = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> signups = new ConcurrentHashMap<>();

    public Accounts(Store store, Config config) {
        this.store = store;
        this.config = config;
    }

    public void routes(Http.Router r) {
        r.setLimit("login", 20);
        r.add("POST /api/login/start", 1024, "login", req -> {
            String challenge = Secrets.token(20);
            long now = System.currentTimeMillis();
            challenges.entrySet().removeIf(e -> e.getValue() < now);
            if (challenges.size() > 50_000) throw problem(503, "Too busy. Try again in a moment.");
            challenges.put(challenge, now + 5 * 60_000);
            return Http.Response.json(Map.of("challenge", challenge));
        });
        r.add("POST /api/login/finish", 4096, "login", req -> {
            Map<String, Object> body = req.json();
            String name = req.field(body, "name", 16);
            String challenge = req.field(body, "challenge", 64);
            Long expires = challenges.remove(challenge);
            if (expires == null || expires < System.currentTimeMillis()) throw problem(400, "That sign-in took too long. Try again.");
            String[] player = askMojang(name, challenge);
            if (player == null) throw problem(401, "Minecraft didn't confirm that sign-in. Try again.");
            String uuid = player[0];
            Map<String, Object> account = store.get("accounts", uuid);
            if (account == null) {
                // First time: a short-lived pass to finish signing up with
                String pass = Secrets.token(24);
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("uuid", uuid);
                pending.put("name", player[1]);
                pending.put("expires", System.currentTimeMillis() + 15 * 60_000);
                signups.put(Secrets.sha256(pass), pending);
                return Http.Response.json(Map.of("signup", pass, "name", player[1]));
            }
            if (Boolean.TRUE.equals(account.get("locked"))) throw problem(403, "This account is locked. Contact support.");
            if (!player[1].equals(account.get("name"))) store.update("accounts", uuid, a -> {
                a.put("name", player[1]); // they changed their Minecraft name
                return a;
            });
            return Http.Response.json(Map.of("session", newSession(uuid), "account", summary(store.get("accounts", uuid))));
        });
        r.add("POST /api/signup", 4096, "login", req -> {
            Map<String, Object> body = req.json();
            Map<String, Object> pending = signups.remove(Secrets.sha256(req.field(body, "signup", 64)));
            if (pending == null || (long) pending.get("expires") < System.currentTimeMillis()) throw problem(400, "That sign-up took too long. Sign in again.");
            if (!(body.get("birthYear") instanceof Number year) || !Rules.validBirthYear(year.intValue())) throw problem(400, "Pick your birth year.");
            String region = req.field(body, "region", 2).toUpperCase();
            if (!Rules.validRegion(region)) throw problem(400, "Pick your region.");
            String uuid = (String) pending.get("uuid");
            if (store.has("accounts", uuid)) throw problem(409, "This account already exists. Sign in.");
            String group = Rules.group(year.intValue(), region);
            String recovery = Secrets.recoveryCode();
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("uuid", uuid);
            account.put("name", pending.get("name"));
            account.put("created", System.currentTimeMillis());
            account.put("region", region);
            account.put("group", group); // the birth year itself isn't kept
            account.put("recoveryHash", Secrets.sha256(recovery));
            account.put("social", defaultSocial(group, region));
            account.put("profilePublic", !group.equals("child") && !(Rules.privateUnder18(region) && group.equals("teen")));
            store.put("accounts", uuid, account);
            return Http.Response.json(Map.of("session", newSession(uuid), "recoveryCode", recovery, "account", summary(account)));
        });
        r.add("GET /api/me", req -> Http.Response.json(summary(require(req))));
        r.add("POST /api/logout", req -> {
            String token = bearer(req);
            if (token != null) store.delete("sessions", Secrets.sha256(token));
            return Http.Response.ok();
        });
        r.add("PUT /api/me/profile-public", req -> {
            Map<String, Object> account = require(req);
            boolean on = Boolean.TRUE.equals(req.json().get("public"));
            if (on && !allowed(account, "profile")) throw problem(403, "A parent has to allow a public profile first.");
            store.update("accounts", (String) account.get("uuid"), a -> {
                a.put("profilePublic", on);
                return a;
            });
            return Http.Response.ok();
        });
    }

    /** The social switches a new account starts with: all off below the consent age, all on otherwise. */
    static Map<String, Object> defaultSocial(String group, String region) {
        Map<String, Object> social = new LinkedHashMap<>();
        for (String f : Rules.FEATURES) social.put(f, !group.equals("child"));
        return social;
    }

    /** Whether an account may use a social feature right now. */
    public static boolean allowed(Map<String, Object> account, String feature) {
        if (account == null || Boolean.TRUE.equals(account.get("locked"))) return false;
        Map<String, Object> social = Json.object(account.get("social"));
        return social != null && Boolean.TRUE.equals(social.get(feature));
    }

    /** What an account's owner sees about it (and what Kelp shows). */
    public Map<String, Object> summary(Map<String, Object> a) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : new String[] {"uuid", "name", "created", "region", "group", "social", "profilePublic", "cape", "effects", "emblem", "squidCount"}) {
            if (a.containsKey(k)) out.put(k, a.get(k));
        }
        out.put("badges", Players.badges(a, config));
        out.put("parentLinked", a.get("parentEmailHash") != null);
        return out;
    }

    private String newSession(String uuid) throws IOException {
        String token = Secrets.token(32);
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("uuid", uuid);
        session.put("expires", System.currentTimeMillis() + SESSION_DAYS * 86_400_000L);
        store.put("sessions", Secrets.sha256(token), session);
        return token;
    }

    static String bearer(Http.Request req) {
        String auth = req.header("Authorization");
        return auth != null && auth.startsWith("Bearer ") ? auth.substring(7).strip() : null;
    }

    /** The signed-in account, or a 401 problem. */
    public Map<String, Object> require(Http.Request req) {
        Map<String, Object> account = optional(req);
        if (account == null) throw problem(401, "Sign in first.");
        if (Boolean.TRUE.equals(account.get("locked"))) throw problem(403, "This account is locked. Contact support.");
        return account;
    }

    /** The signed-in account, or null for visitors. */
    public Map<String, Object> optional(Http.Request req) {
        String token = bearer(req);
        if (token == null || token.length() > 128) return null;
        Map<String, Object> session;
        try {
            session = store.get("sessions", Secrets.sha256(token));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (session == null || ((Number) session.get("expires")).longValue() < System.currentTimeMillis()) return null;
        return store.get("accounts", (String) session.get("uuid"));
    }

    /** Asks Mojang whether this player joined the server named challenge. Gives {uuid, name}, or null if not. */
    String[] askMojang(String name, String challenge) throws IOException, InterruptedException {
        String url = config.mojangSessions + "/session/minecraft/hasJoined?username=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&serverId=" + URLEncoder.encode(challenge, StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().isBlank()) return null;
        Map<String, Object> profile = Json.object(Json.parse(response.body()));
        if (profile == null || !(profile.get("id") instanceof String id) || !(profile.get("name") instanceof String n)) return null;
        return new String[] {id.replace("-", "").toLowerCase(), n};
    }

    /** Deletes an account and its sessions (the rest of its data is deleted by each part of the server). */
    public void deleteAccount(String uuid) throws IOException {
        for (String id : store.ids("sessions")) {
            Map<String, Object> s = store.get("sessions", id);
            if (s != null && uuid.equals(s.get("uuid"))) store.delete("sessions", id);
        }
        store.delete("accounts", uuid);
    }
}
