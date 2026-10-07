package kelpserver;

import com.sun.net.httpserver.HttpServer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** Runs the whole server against a fake Mojang, and checks every flow a player, parent and admin go through. */
public class ServerTest {
    static int failures;
    static String base;
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Map<String, String[]> joined = new ConcurrentHashMap<>(); // challenge -> {uuid, name}
    static final String ADMIN_KEY = "test-admin-key";

    static void check(String what, Object got, Object expected) {
        boolean ok = String.valueOf(got).equals(String.valueOf(expected));
        System.out.println((ok ? "PASS " : "FAIL ") + what + " -> " + got + (ok ? "" : " (expected " + expected + ")"));
        if (!ok) failures++;
    }

    record Answer(int status, Map<String, Object> json, String text, byte[] bytes) {
        Object get(String key) {
            return json == null ? null : json.get(key);
        }
    }

    static Answer call(String method, String path, String token, Object body) throws Exception {
        return call(method, path, token, body == null ? new byte[0] : Json.write(body).getBytes(StandardCharsets.UTF_8), null);
    }

    static Answer call(String method, String path, String token, byte[] body, String adminKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (adminKey != null) b.header("X-Admin-Key", adminKey);
        HttpResponse<byte[]> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        String text = new String(r.body(), StandardCharsets.UTF_8);
        Map<String, Object> json = null;
        try {
            json = Json.object(Json.parse(text));
        } catch (RuntimeException e) {
            // not JSON (a page or a file)
        }
        return new Answer(r.statusCode(), json, text, r.body());
    }

    /** Signs a player in through the fake Mojang; signs them up first with this birth year and region if they're new. */
    static String signIn(String uuid, String name, int birthYear, String region, String[] recovery) throws Exception {
        String challenge = (String) call("POST", "/api/login/start", null, null).get("challenge");
        joined.put(challenge, new String[] {uuid, name}); // Kelp told Mojang it joined
        Answer finish = call("POST", "/api/login/finish", null, Map.of("name", name, "challenge", challenge));
        if (finish.get("session") != null) return (String) finish.get("session");
        Answer signup = call("POST", "/api/signup", null, Map.of("signup", finish.get("signup"), "birthYear", birthYear, "region", region));
        if (recovery != null) recovery[0] = (String) signup.get("recoveryCode");
        return (String) signup.get("session");
    }

    static String id(int n) {
        return String.format("%032x", n);
    }

    public static void main(String[] args) throws Exception {
        // A fake Mojang session server: says yes only to players the test "joined"
        HttpServer mojang = HttpServer.create(new InetSocketAddress(0), 0);
        mojang.createContext("/", ex -> {
            String q = ex.getRequestURI().getQuery();
            String serverId = q.replaceAll(".*serverId=([^&]*).*", "$1");
            String user = q.replaceAll(".*username=([^&]*).*", "$1");
            String[] who = joined.remove(serverId);
            byte[] body = who != null && who[1].equals(user) ? ("{\"id\":\"" + who[0] + "\",\"name\":\"" + who[1] + "\"}").getBytes() : new byte[0];
            ex.sendResponseHeaders(body.length > 0 ? 200 : 204, body.length > 0 ? body.length : -1);
            if (body.length > 0) ex.getResponseBody().write(body);
            ex.close();
        });
        mojang.start();

        Path data = Files.createTempDirectory("kelp-server-test");
        Properties p = new Properties();
        p.setProperty("mojangSessions", "http://localhost:" + mojang.getAddress().getPort());
        p.setProperty("adminKeyHash", Secrets.sha256(ADMIN_KEY));
        p.setProperty("devPlayers", id(1));
        p.setProperty("launch", Year.now().getValue() + "-01-01");
        Config config = new Config(p);
        Server server = new Server(data, config, new Mail(config));
        int port = server.start(0);
        base = "http://localhost:" + port;
        int year = Year.now().getValue();

        // ---- Signing up at different ages and regions ----
        String[] recovery = new String[1];
        String sam = signIn(id(1), "Sam", year - 30, "US", recovery);
        Answer me = call("GET", "/api/me", sam, null);
        check("an adult signs up with everything social on, a public profile, and a recovery code",
                me.get("group") + " " + Json.object(me.get("social")).get("dms") + " " + me.get("profilePublic") + " " + recovery[0].matches("KELP(-[A-Z2-9]{4}){4}"),
                "adult true true true");
        check("the dev gets the wrench, and everyone in the first year is an early player", me.get("badges"), "[dev, early-player]");
        String kid = signIn(id(2), "Kiddo", year - 9, "US", null);
        Answer kidMe = call("GET", "/api/me", kid, null);
        check("a kid starts with everything social off and a private profile",
                kidMe.get("group") + " " + Json.object(kidMe.get("social")).values() + " " + kidMe.get("profilePublic"), "child [false, false, false, false, false, false, false] false");
        String brit = signIn(id(3), "Brit", year - 16, "GB", null);
        Answer britMe = call("GET", "/api/me", brit, null);
        check("a UK teen can use social features, but their profile starts private",
                britMe.get("group") + " " + Json.object(britMe.get("social")).get("dms") + " " + britMe.get("profilePublic"), "teen true false");
        check("in Germany, 15 is still below the age of consent (16)", Rules.group(year - 16, "DE") + " " + Rules.group(year - 16, "US"), "child teen");
        check("signing in again finds the account (no second sign-up)", signIn(id(1), "Sam", 0, "", null) != null, true);
        Answer badLogin = call("POST", "/api/login/finish", null, Map.of("name", "Sam", "challenge", "nope"));
        check("a sign-in Mojang didn't confirm is refused", badLogin.status(), 400);
        check("pages need a real session", call("GET", "/api/me", "made-up", null).status(), 401);

        // ---- Emblems and capes, with the unlocks checked by the server too ----
        check("a basic emblem is saved", call("PUT", "/api/me/emblem", sam, Map.of("emblem", "CIRCLE 3C78E0 0.5 0.5 0.9 0.0 0 0\nSTAR FFFFFF 0.5 0.5 0.5 0.0 0 0")).status(), 200);
        Answer squidShape = call("PUT", "/api/me/emblem", sam, Map.of("emblem", "SQUID 202020 0.5 0.5 0.5 0.0 0 0"));
        check("a locked shape is refused", squidShape.status() + " " + squidShape.get("error"), "400 Squid unlocks at 600 Squid Count.");
        check("Squid Count that doesn't add up is refused", call("PUT", "/api/me/squidcount", sam, Map.of("points", 999, "advancements", List.of("a"))).status(), 400);
        check("a real Squid Count is saved", call("PUT", "/api/me/squidcount", sam, Map.of("points", 700, "advancements", java.util.Collections.nCopies(20, "x"))).status(), 200);
        check("then the squid shape unlocks", call("PUT", "/api/me/emblem", sam, Map.of("emblem", "SQUID 202020 0.5 0.5 0.5 0.0 0 0")).status(), 200);
        check("the emblem is drawn as a picture", call("GET", "/api/players/" + id(1) + "/emblem.png", null, null).bytes()[1] == 'P', true);
        check("everyone can wear the Kelp cape", call("PUT", "/api/me/cape", sam, Map.of("cape", "kelp", "effects", List.of("GLOW", "nonsense"))).status(), 200);
        check("early players can wear the Early Player cape", call("PUT", "/api/me/cape", sam, Map.of("cape", "early-player")).status(), 200);
        check("but not the Beta Tester cape without the badge", call("PUT", "/api/me/cape", sam, Map.of("cape", "beta-tester")).status(), 403);
        Answer tags = call("GET", "/api/players?uuids=" + id(1) + "," + id(2) + ",bad", null, null);
        check("Squid gets everyone's name tags at once (and skips nonsense)", tags.json().keySet() + " " + Json.object(tags.get(id(1))).get("cape"),
                "[" + id(1) + ", " + id(2) + "] early-player");

        // ---- Sharing, checked first ----
        byte[] picture = png(64, 64);
        check("a kid can't share until a parent allows it", call("POST", "/api/posts?kind=screenshot&title=Hi", kid, picture, null).status(), 403);
        Answer posted = call("POST", "/api/posts?kind=screenshot&title=My%20base", sam, picture, null);
        String postId = (String) posted.get("id");
        check("a shared screenshot waits for the admin", posted.get("status"), "pending");
        check("nobody else can see it yet", call("GET", "/api/posts/" + postId + "/file", null, null).status(), 404);
        check("a title with a phone number is refused", call("POST", "/api/posts?kind=screenshot&title=call%20555-123-4567", sam, picture, null).status(), 400);
        check("not a picture is refused", call("POST", "/api/posts?kind=screenshot", sam, "hello".getBytes(), null).status(), 400);
        Answer queue = call("GET", "/api/admin/queue", null, new byte[0], ADMIN_KEY);
        check("the admin sees it in the queue (and others can't)", ((List<?>) queue.get("posts")).size() + " " + call("GET", "/api/admin/queue", sam, null).status(), "1 403");
        call("POST", "/api/admin/posts/" + postId + "/approve", null, new byte[0], ADMIN_KEY);
        check("once approved, everyone can see it", call("GET", "/api/posts/" + postId + "/file", null, null).status(), 200);
        check("and it shows on their profile", ((List<?>) Json.parse(call("GET", "/api/players/Sam/posts", null, null).text())).size(), 1);
        check("a custom cape needs the right size", call("POST", "/api/me/capes", sam, png(10, 10), null).status(), 400);
        String capeId = (String) call("POST", "/api/me/capes", sam, png(64, 32), null).get("id");
        check("an unapproved custom cape can't be worn", call("PUT", "/api/me/cape", sam, Map.of("cape", "custom:" + capeId)).status(), 403);
        call("POST", "/api/admin/capes/" + capeId + "/approve", null, new byte[0], ADMIN_KEY);
        check("an approved one can", call("PUT", "/api/me/cape", sam, Map.of("cape", "custom:" + capeId)).status(), 200);
        check("reports reach the queue", call("POST", "/api/reports", kid, Map.of("kind", "player", "target", id(1), "reason", "test")).status(), 200);

        // ---- Friends and messages ----
        String alex = signIn(id(4), "Alex", year - 25, "US", null);
        check("asking to be friends", call("POST", "/api/friends/request", sam, Map.of("name", "Alex")).get("status"), "asked");
        check("messages need friends first", call("POST", "/api/messages/" + id(4), sam, Map.of("text", "hi")).status(), 403);
        call("POST", "/api/friends/" + id(1) + "/accept", alex, null);
        check("friends can message", call("POST", "/api/messages/" + id(4), sam, Map.of("text", "want to build?")).status(), 200);
        check("swearing is filtered", call("POST", "/api/messages/" + id(4), sam, Map.of("text", "what the f u c k")).status(), 400);
        check("emails are filtered", call("POST", "/api/messages/" + id(4), sam, Map.of("text", "mail me at bob (at) gmail (dot) com")).status(), 400);
        check("links are filtered", call("POST", "/api/messages/" + id(4), sam, Map.of("text", "join discord.gg/abc")).status(), 400);
        check("the friend gets the message", ((List<?>) Json.parse(call("GET", "/api/messages/" + id(1), alex, null).text())).size(), 1);
        check("a kid can't add friends until a parent allows it", call("POST", "/api/friends/request", kid, Map.of("name", "Sam")).status(), 403);
        call("POST", "/api/blocks/" + id(1), alex, null);
        check("after a block, no messages either way", call("POST", "/api/messages/" + id(4), sam, Map.of("text", "hello?")).status(), 403);

        // ---- Parents ----
        check("a kid asks a parent from Kelp", call("POST", "/api/me/parent", kid, Map.of("email", "Parent@Example.com")).status(), 200);
        String[] mail = server.mail.outbox.get(server.mail.outbox.size() - 1);
        String linkToken = mail[2].replaceAll("(?s).*approve#([0-9a-f]+).*", "$1");
        check("the parent gets an email with an approve link", mail[0] + " " + linkToken.matches("[0-9a-f]{48}"), "parent@example.com true");
        check("the link says who's asking", call("GET", "/api/parents/link/" + linkToken, null, null).get("name"), "Kiddo");
        Map<String, Object> allow = Map.of("dms", true, "posting", false, "profile", false);
        Answer approved = call("POST", "/api/parents/link/" + linkToken + "/approve", null, Map.of("social", allow));
        check("approving signs the parent in, and the link only works once",
                (approved.get("session") != null) + " " + call("GET", "/api/parents/link/" + linkToken, null, null).status(), "true 404");
        check("now the kid can add friends (but still not share)", call("POST", "/api/friends/request", kid, Map.of("name", "Alex")).get("status") + " "
                + call("POST", "/api/posts?kind=screenshot", kid, picture, null).status(), "asked 403");
        call("POST", "/api/friends/" + id(2) + "/accept", alex, null);
        call("POST", "/api/messages/" + id(4), kid, Map.of("text", "hi alex"));
        check("signing in by email always says it's sent", call("POST", "/api/parents/login", null, Map.of("email", "nobody@example.com")).get("sent") + " "
                + call("POST", "/api/parents/login", null, Map.of("email", "parent@example.com")).get("sent"), "true true");
        mail = server.mail.outbox.get(server.mail.outbox.size() - 1);
        String parentToken = mail[2].replaceAll("(?s).*portal#([0-9a-f]+).*", "$1");
        Answer kids = call("GET", "/api/parents/children", parentToken, null);
        List<?> list = (List<?>) Json.parse(kids.text());
        Map<String, Object> first = Json.object(list.get(0));
        check("the parent sees their kid and who they message, not what they say",
                first.get("name") + " " + first.get("contacts").toString().contains("Alex") + " " + kids.text().contains("hi alex"), "Kiddo true false");
        check("a parent can't touch someone else's kid", call("PUT", "/api/parents/children/" + id(1), parentToken, Map.of("social", Map.of("dms", true))).status(), 404);
        call("PUT", "/api/parents/children/" + id(2), parentToken, Map.of("social", Map.of("dms", false)));
        check("a parent can switch things off", call("POST", "/api/messages/" + id(4), kid, Map.of("text", "hi again")).status(), 403);
        call("DELETE", "/api/parents/children/" + id(2), parentToken, null);
        check("a parent can delete the account", call("GET", "/api/me", kid, null).status(), 401);

        // ---- Recovery, deleting, and the website ----
        String newSam = signIn(id(5), "SamNew", year - 30, "US", null);
        Answer wrong = call("POST", "/api/admin/recover", null, Json.write(Map.of("from", id(1), "to", id(5), "code", "KELP-AAAA-AAAA-AAAA-AAAA")).getBytes(), ADMIN_KEY);
        Answer right = call("POST", "/api/admin/recover", null, Json.write(Map.of("from", id(1), "to", id(5), "code", recovery[0])).getBytes(), ADMIN_KEY);
        Answer moved = call("GET", "/api/me", newSam, null);
        check("recovery needs the right code, moves the emblem and Squid Count, and locks the old account",
                wrong.status() + " " + right.status() + " " + moved.get("squidCount") + " " + (moved.get("emblem") != null) + " " + call("GET", "/api/me", sam, null).status(),
                "403 200 700 true 403");
        call("DELETE", "/api/me", newSam, null);
        check("deleting your account deletes what you shared", call("GET", "/api/posts/" + postId, null, null).status(), 404);
        check("the website's pages load", call("GET", "/", null, null).status() + " " + call("GET", "/privacy", null, null).status() + " "
                + call("GET", "/parents", null, null).status() + " " + call("GET", "/leaderboard", null, null).status() + " " + call("GET", "/u/Alex", null, null).status(),
                "200 200 200 200 200");
        check("names are made safe on pages", Http.html("<script>&\"'"), "&lt;script&gt;&amp;&quot;&#39;");
        check("too-big bodies are turned away before they're read", call("PUT", "/api/me/emblem", alex, new byte[20_000], null).status(), 413);
        check("ids that could be file paths are refused", call("GET", "/api/posts/..%2F..%2Fserver", null, null).status() >= 400, true);
        check("unknown pages are 404", call("GET", "/nope", null, null).status(), 404);

        server.stop();
        mojang.stop(0);
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }
}
