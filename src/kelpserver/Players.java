package kelpserver;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static kelpserver.Http.problem;

/**
 * What other players see: capes, emblems, badges, Squid Count, profiles and the leaderboard. Squid asks for many
 * players at once (everyone nearby) to draw their capes and name tags.
 */
public final class Players {
    /** Capes everyone has, and the event capes (which need their badge). */
    static final Set<String> EVERYONE_CAPES = Set.of("kelp", "squid");
    static final Map<String, String> EVENT_CAPES = Map.of("early-player", "early-player", "beta-tester", "beta-tester");
    static final Set<String> EFFECTS = Set.of("ENCHANTED", "GLOW", "RAINBOW", "BUBBLES", "FLAMES", "SPARKLES", "HEARTS", "NOTES", "SNOW", "WATER");

    private final Store store;
    private final Config config;
    private final Accounts accounts;

    public Players(Store store, Config config, Accounts accounts) {
        this.store = store;
        this.config = config;
        this.accounts = accounts;
    }

    public void routes(Http.Router r) {
        r.add("PUT /api/me/emblem", 16 * 1024, "api", req -> {
            Map<String, Object> me = accounts.require(req);
            String text = req.field(req.json(), "emblem", 8000);
            Emblem emblem = Emblem.parse(text);
            int points = squidCount(me);
            int most = Emblem.UNLOCKS.layers(points);
            if (emblem.layers.isEmpty()) throw problem(400, "An emblem needs at least one layer.");
            if (emblem.layers.size() > most) throw problem(400, "You can have " + most + " layers.");
            for (Emblem.Layer layer : emblem.layers) {
                // The server checks the unlocks too, so an edited Kelp can't skip them
                if (layer.shape.points > points) throw problem(400, layer.shape.label + " unlocks at " + layer.shape.points + " Squid Count.");
                if ((layer.flipX || layer.flipY) && points < Emblem.UNLOCKS.FLIP) throw problem(400, "Flipping unlocks at " + Emblem.UNLOCKS.FLIP + ".");
                if (layer.turn != 0 && points < Emblem.UNLOCKS.TURN) throw problem(400, "Turning unlocks at " + Emblem.UNLOCKS.TURN + ".");
                boolean basic = false;
                for (int c : Emblem.BASIC_COLORS) basic |= c == (layer.color & 0xFFFFFF);
                if (!basic && points < Emblem.UNLOCKS.ALL_COLORS) throw problem(400, "More colors unlock at " + Emblem.UNLOCKS.ALL_COLORS + ".");
            }
            String clean = emblem.text();
            store.update("accounts", uuid(me), a -> {
                a.put("emblem", clean);
                return a;
            });
            return Http.Response.ok();
        });
        r.add("PUT /api/me/cape", req -> {
            Map<String, Object> me = accounts.require(req);
            Map<String, Object> body = req.json();
            String cape = req.field(body, "cape", 80);
            if (!capeAllowed(me, cape)) throw problem(403, "You can't wear that cape.");
            List<String> effects = new ArrayList<>();
            List<Object> asked = Json.array(body.get("effects"));
            if (asked != null) {
                for (Object e : asked) {
                    if (e instanceof String s && EFFECTS.contains(s) && !effects.contains(s) && effects.size() < 4) effects.add(s);
                }
            }
            store.update("accounts", uuid(me), a -> {
                a.put("cape", cape);
                a.put("effects", effects);
                return a;
            });
            return Http.Response.ok();
        });
        r.add("PUT /api/me/squidcount", req -> {
            Map<String, Object> me = accounts.require(req);
            Map<String, Object> body = req.json();
            if (!(body.get("points") instanceof Number points) || !(Json.array(body.get("advancements")) instanceof List<Object> done)) {
                throw problem(400, "Send points and advancements.");
            }
            // A sanity check: the most an advancement is worth is 50, and there are only so many
            int p = points.intValue();
            if (p < 0 || done.size() > 2000 || p > done.size() * 50) throw problem(400, "That Squid Count doesn't add up.");
            store.update("accounts", uuid(me), a -> {
                a.put("squidCount", p);
                return a;
            });
            return Http.Response.ok();
        });
        // Everyone nearby, for capes and name tags (public things only)
        r.add("GET /api/players", req -> {
            String list = req.query("uuids");
            if (list == null || list.isBlank()) throw problem(400, "Which players?");
            String[] ids = list.split(",");
            if (ids.length > 100) throw problem(400, "At most 100 at a time.");
            Map<String, Object> out = new LinkedHashMap<>();
            for (String id : ids) {
                String clean = id.replace("-", "").toLowerCase();
                if (!clean.matches("[0-9a-f]{32}")) continue;
                Map<String, Object> a = store.get("accounts", clean);
                if (a == null || Boolean.TRUE.equals(a.get("locked"))) continue;
                out.put(clean, nameTag(a));
            }
            return Http.Response.json(out);
        });
        r.add("GET /api/players/{uuid}/emblem.png", req -> {
            String id = req.value("uuid").replace("-", "").toLowerCase();
            if (!id.matches("[0-9a-f]{32}")) throw problem(404, "No such player.");
            Map<String, Object> a = store.get("accounts", id);
            if (a == null || !(a.get("emblem") instanceof String text)) throw problem(404, "No emblem.");
            return Http.Response.bytes("image/png", png(Emblem.parse(text).draw()));
        });
        r.add("GET /api/profile/{name}", req -> Http.Response.json(profile(req.value("name"), accounts.optional(req))));
        r.add("GET /api/leaderboard", req -> Http.Response.json(leaderboard(100)));
    }

    static String uuid(Map<String, Object> account) {
        return (String) account.get("uuid");
    }

    static int squidCount(Map<String, Object> account) {
        return account.get("squidCount") instanceof Number n ? n.intValue() : 0;
    }

    boolean capeAllowed(Map<String, Object> me, String cape) {
        if (cape.equals("none") || EVERYONE_CAPES.contains(cape)) return true;
        if (EVENT_CAPES.containsKey(cape)) return badges(me, config).contains(EVENT_CAPES.get(cape));
        if (cape.startsWith("official:")) return cape.substring(9).matches("[a-z0-9-]{1,40}"); // shown with a "doesn't own it" tag unless worn on Mojang's side
        if (cape.startsWith("store:")) return cape.substring(6).matches("[a-z0-9-]{1,40}");
        if (cape.startsWith("custom:")) {
            String id = cape.substring(7);
            if (!id.matches("[a-f0-9]{24}")) return false;
            Map<String, Object> upload = store.get("capes", id);
            return upload != null && uuid(me).equals(upload.get("owner")) && "approved".equals(upload.get("status"));
        }
        return false;
    }

    /** The badges an account has right now, in Kelp's order. */
    static List<String> badges(Map<String, Object> a, Config config) {
        List<String> out = new ArrayList<>();
        String id = (String) a.get("uuid");
        if (config.devPlayers.contains(id)) out.add("dev");
        if (Boolean.TRUE.equals(a.get("betaTester"))) out.add("beta-tester");
        long created = a.get("created") instanceof Number n ? n.longValue() : Long.MAX_VALUE;
        LocalDate made = Instant.ofEpochMilli(created == Long.MAX_VALUE ? 0 : created).atZone(ZoneOffset.UTC).toLocalDate();
        if (created != Long.MAX_VALUE && made.isBefore(config.launch.plusYears(1))) out.add("early-player");
        Map<String, Object> links = Json.object(a.get("links"));
        if (links != null && links.get("github") != null && Boolean.TRUE.equals(a.get("githubContributor"))) out.add("github-contributor");
        if (links != null && links.get("discord") != null && Boolean.TRUE.equals(a.get("discordMember"))) out.add("discord-member");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (created != Long.MAX_VALUE && made.getMonth() == today.getMonth() && made.getDayOfMonth() == today.getDayOfMonth()
                && made.isBefore(today)) out.add("birthday"); // their Kelp birthday: the day they first signed in
        return out;
    }

    /** What anyone can see about a player in the game: name, cape, effects, emblem, badges. */
    Map<String, Object> nameTag(Map<String, Object> a) {
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("name", a.get("name"));
        tag.put("cape", a.getOrDefault("cape", "none"));
        tag.put("effects", a.getOrDefault("effects", List.of()));
        tag.put("emblem", a.get("emblem"));
        tag.put("badges", badges(a, config));
        return tag;
    }

    /** A player's profile page data: everything public if their profile is, else just their name tag. */
    Map<String, Object> profile(String name, Map<String, Object> viewer) {
        Map<String, Object> a = byName(name);
        if (a == null || Boolean.TRUE.equals(a.get("locked"))) throw problem(404, "No player called that.");
        Map<String, Object> out = nameTag(a);
        boolean owner = viewer != null && uuid(viewer).equals(uuid(a));
        boolean open = Boolean.TRUE.equals(a.get("profilePublic")) && Accounts.allowed(a, "profile");
        out.put("public", open);
        if (open || owner) {
            out.put("uuid", uuid(a));
            out.put("squidCount", squidCount(a));
            out.put("since", a.get("created"));
        }
        return out;
    }

    Map<String, Object> byName(String name) {
        for (Map<String, Object> a : store.all("accounts")) {
            if (name.equalsIgnoreCase((String) a.get("name"))) return a;
        }
        return null;
    }

    /** The highest Squid Counts, only of players whose profiles are public. */
    List<Map<String, Object>> leaderboard(int limit) {
        List<Map<String, Object>> open = new ArrayList<>();
        for (Map<String, Object> a : store.all("accounts")) {
            if (Boolean.TRUE.equals(a.get("profilePublic")) && Accounts.allowed(a, "profile") && squidCount(a) > 0) open.add(a);
        }
        open.sort(Comparator.comparingInt(Players::squidCount).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, open.size()); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("name", open.get(i).get("name"));
            row.put("squidCount", squidCount(open.get(i)));
            row.put("badges", badges(open.get(i), config));
            out.add(row);
        }
        return out;
    }

    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
