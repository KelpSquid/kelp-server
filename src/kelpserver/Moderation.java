package kelpserver;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static kelpserver.Http.problem;

/**
 * Everything players share, and keeping it safe. Screenshots, clips and custom capes go into a queue and only become
 * public once the admin (Samuel) approves them. Anyone can report a player, post, cape, message or Store item; reports
 * go to the same admin queue. The admin can also lock accounts, mark beta testers, and move an account to a new
 * Minecraft account with its recovery code.
 */
public final class Moderation {
    private static final int MAX_PICTURE = 8 * 1024 * 1024;
    private static final int MAX_CLIP = 60 * 1024 * 1024;
    private static final int MAX_CAPE = 256 * 1024;
    private static final Set<String> REPORT_KINDS = Set.of("player", "post", "cape", "message", "store-item");

    private final Store store;
    private final Config config;
    private final Accounts accounts;

    public Moderation(Store store, Config config, Accounts accounts) {
        this.store = store;
        this.config = config;
        this.accounts = accounts;
    }

    public void routes(Http.Router r) {
        r.setLimit("upload", 10);
        // Sharing a screenshot or clip: the file is the body; kind and title come in the query
        r.add("POST /api/posts", MAX_CLIP, "upload", req -> {
            Map<String, Object> me = accounts.require(req);
            if (!Accounts.allowed(me, "posting")) throw problem(403, "Sharing is off for your account. A parent can turn it on.");
            String kind = req.query("kind");
            String title = req.query("title") == null ? "" : req.query("title").strip();
            if (title.length() > 80) throw problem(400, "That title is too long.");
            if (!title.isEmpty() && Filter.problem(title) != null) throw problem(400, Filter.problem(title));
            String type;
            if ("screenshot".equals(kind)) {
                if (req.body().length > MAX_PICTURE) throw problem(413, "That picture is too big.");
                BufferedImage picture = read(req.body());
                if (picture == null || picture.getWidth() > 8192 || picture.getHeight() > 8192) throw problem(400, "That isn't a picture.");
                type = req.body()[0] == (byte) 0x89 ? "image/png" : "image/jpeg";
            } else if ("clip".equals(kind)) {
                if (req.body().length < 12 || !new String(req.body(), 0, 4).equals("RIFF") || !new String(req.body(), 8, 4).equals("AVI ")) {
                    throw problem(400, "That isn't a clip.");
                }
                type = "video/x-msvideo";
            } else {
                throw problem(400, "Share a screenshot or a clip.");
            }
            String id = Secrets.token(12);
            store.saveFile(id, req.body());
            Map<String, Object> post = new LinkedHashMap<>();
            post.put("id", id);
            post.put("owner", Players.uuid(me));
            post.put("kind", kind);
            post.put("type", type);
            post.put("title", title);
            post.put("status", "pending");
            post.put("created", System.currentTimeMillis());
            store.put("posts", id, post);
            return Http.Response.json(Map.of("id", id, "status", "pending"));
        });
        r.add("GET /api/posts/{id}", req -> Http.Response.json(publicPost(visiblePost(req))));
        r.add("GET /api/posts/{id}/file", req -> {
            Map<String, Object> post = visiblePost(req);
            return Http.Response.bytes((String) post.get("type"), Files.readAllBytes(store.file((String) post.get("id"))));
        });
        r.add("DELETE /api/posts/{id}", req -> {
            Map<String, Object> me = accounts.require(req);
            Map<String, Object> post = store.get("posts", Store.safeId(req.value("id")));
            if (post == null || !Players.uuid(me).equals(post.get("owner"))) throw problem(404, "No such post.");
            deletePost(post);
            return Http.Response.ok();
        });
        r.add("GET /api/players/{name}/posts", req -> {
            Map<String, Object> owner = null;
            for (Map<String, Object> a : store.all("accounts")) if (req.value("name").equalsIgnoreCase((String) a.get("name"))) owner = a;
            if (owner == null) throw problem(404, "No player called that.");
            Map<String, Object> viewer = accounts.optional(req);
            boolean self = viewer != null && Players.uuid(viewer).equals(Players.uuid(owner));
            boolean open = Boolean.TRUE.equals(owner.get("profilePublic")) && Accounts.allowed(owner, "profile");
            List<Map<String, Object>> out = new ArrayList<>();
            if (open || self) {
                for (Map<String, Object> p : store.all("posts")) {
                    if (Players.uuid(owner).equals(p.get("owner")) && ("approved".equals(p.get("status")) || self)) out.add(publicPost(p));
                }
            }
            out.sort(Comparator.comparingLong((Map<String, Object> p) -> ((Number) p.get("created")).longValue()).reversed());
            return Http.Response.json(out);
        });

        // Custom capes: a PNG in Minecraft's cape layout (64x32, or a taller strip for animation)
        r.add("POST /api/me/capes", MAX_CAPE, "upload", req -> {
            Map<String, Object> me = accounts.require(req);
            BufferedImage cape = read(req.body());
            if (cape == null || req.body()[0] != (byte) 0x89) throw problem(400, "A cape has to be a PNG.");
            boolean layout = cape.getWidth() == 64 && cape.getHeight() >= 32 && cape.getHeight() % 32 == 0 && cape.getHeight() <= 32 * 32;
            boolean big = cape.getWidth() % 64 == 0 && cape.getWidth() <= 512 && cape.getHeight() * 2 == cape.getWidth();
            if (!layout && !big) throw problem(400, "A cape is 64x32 (or a multiple, or a tall strip of frames).");
            long mine = store.all("capes").stream().filter(c -> Players.uuid(me).equals(c.get("owner"))).count();
            if (mine >= 20) throw problem(400, "You have 20 capes already. Delete one first.");
            String id = Secrets.token(12);
            store.saveFile(id, req.body());
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", id);
            record.put("owner", Players.uuid(me));
            record.put("status", "pending");
            record.put("created", System.currentTimeMillis());
            store.put("capes", id, record);
            return Http.Response.json(Map.of("id", id, "status", "pending"));
        });
        r.add("GET /api/capes/{id}.png", req -> {
            Map<String, Object> cape = store.get("capes", Store.safeId(req.value("id")));
            if (cape == null || !"approved".equals(cape.get("status"))) throw problem(404, "No such cape.");
            return Http.Response.bytes("image/png", Files.readAllBytes(store.file((String) cape.get("id"))));
        });

        // Reporting
        r.setLimit("report", 20);
        r.add("POST /api/reports", 8192, "report", req -> {
            Map<String, Object> me = accounts.optional(req);
            Map<String, Object> body = req.json();
            String kind = req.field(body, "kind", 20);
            if (!REPORT_KINDS.contains(kind)) throw problem(400, "Report a player, post, cape, message or Store item.");
            String target = req.field(body, "target", 128);
            String reason = req.field(body, "reason", 500);
            String id = Secrets.token(12);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("id", id);
            report.put("kind", kind);
            report.put("target", target);
            report.put("reason", reason);
            report.put("from", me == null ? null : Players.uuid(me));
            report.put("status", "open");
            report.put("created", System.currentTimeMillis());
            store.put("reports", id, report);
            return Http.Response.json(Map.of("id", id));
        });

        // The admin's side
        r.setLimit("admin", 120);
        r.add("GET /api/admin/queue", 0, "admin", req -> {
            admin(req);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("posts", store.all("posts").stream().filter(p -> "pending".equals(p.get("status"))).toList());
            out.put("capes", store.all("capes").stream().filter(p -> "pending".equals(p.get("status"))).toList());
            out.put("reports", store.all("reports").stream().filter(p -> "open".equals(p.get("status"))).toList());
            return Http.Response.json(out);
        });
        r.add("GET /api/admin/files/{id}", 0, "admin", req -> {
            admin(req);
            return Http.Response.bytes("application/octet-stream", Files.readAllBytes(store.file(Store.safeId(req.value("id")))));
        });
        for (String what : new String[] {"posts", "capes"}) {
            r.add("POST /api/admin/" + what + "/{id}/{decision}", 1024, "admin", req -> {
                admin(req);
                String decision = req.value("decision");
                if (!decision.equals("approve") && !decision.equals("reject")) throw problem(404, "Approve or reject.");
                String id = Store.safeId(req.value("id"));
                if (!store.has(what, id)) throw problem(404, "Not found.");
                store.update(what, id, p -> {
                    p.put("status", decision.equals("approve") ? "approved" : "rejected");
                    p.put("decided", System.currentTimeMillis());
                    return p;
                });
                if (decision.equals("reject")) Files.deleteIfExists(store.file(id)); // rejected uploads aren't kept
                return Http.Response.ok();
            });
        }
        r.add("POST /api/admin/reports/{id}/close", 1024, "admin", req -> {
            admin(req);
            String id = Store.safeId(req.value("id"));
            if (!store.has("reports", id)) throw problem(404, "Not found.");
            store.update("reports", id, p -> {
                p.put("status", "closed");
                return p;
            });
            return Http.Response.ok();
        });
        r.add("POST /api/admin/players/{uuid}/{flag}", 1024, "admin", req -> {
            admin(req);
            String flag = req.value("flag");
            Map<String, String> flags = Map.of("lock", "locked", "beta", "betaTester", "github", "githubContributor", "discord", "discordMember");
            if (!flags.containsKey(flag)) throw problem(404, "Unknown flag.");
            String id = req.value("uuid").replace("-", "").toLowerCase();
            if (!store.has("accounts", Store.safeId(id))) throw problem(404, "No such player.");
            boolean on = !Boolean.FALSE.equals(req.json().get("on"));
            store.update("accounts", id, a -> {
                a.put(flags.get(flag), on);
                return a;
            });
            return Http.Response.ok();
        });
        // Recovery: someone lost their Minecraft account; with the old account's recovery code, their things move over
        r.add("POST /api/admin/recover", 4096, "admin", req -> {
            admin(req);
            Map<String, Object> body = req.json();
            String from = req.field(body, "from", 36).replace("-", "").toLowerCase();
            String to = req.field(body, "to", 36).replace("-", "").toLowerCase();
            String code = req.field(body, "code", 40).toUpperCase().strip();
            Map<String, Object> old = store.get("accounts", Store.safeId(from));
            Map<String, Object> fresh = store.get("accounts", Store.safeId(to));
            if (old == null || fresh == null) throw problem(404, "Both accounts have to exist (the new one signs in to Kelp first).");
            if (!Secrets.same(Secrets.sha256(code), (String) old.get("recoveryHash"))) throw problem(403, "That recovery code doesn't match.");
            for (String k : new String[] {"emblem", "cape", "effects", "squidCount", "betaTester", "githubContributor", "discordMember", "created"}) {
                if (old.containsKey(k)) fresh.put(k, old.get(k));
            }
            String newCode = Secrets.recoveryCode();
            fresh.put("recoveryHash", Secrets.sha256(newCode));
            store.put("accounts", to, fresh);
            for (String collection : new String[] {"posts", "capes"}) {
                for (Map<String, Object> p : store.all(collection)) {
                    if (from.equals(p.get("owner"))) {
                        p.put("owner", to);
                        store.put(collection, (String) p.get("id"), p);
                    }
                }
            }
            old.put("locked", true);
            old.put("recoveryHash", null); // each code works once
            store.put("accounts", from, old);
            return Http.Response.json(Map.of("newRecoveryCode", newCode));
        });
    }

    private Map<String, Object> visiblePost(Http.Request req) {
        Map<String, Object> post = store.get("posts", Store.safeId(req.value("id")));
        if (post == null) throw problem(404, "No such post.");
        Map<String, Object> viewer = accounts.optional(req);
        boolean owner = viewer != null && Players.uuid(viewer).equals(post.get("owner"));
        if (!"approved".equals(post.get("status")) && !owner && !isAdmin(req)) throw problem(404, "No such post.");
        return post;
    }

    private static Map<String, Object> publicPost(Map<String, Object> p) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : new String[] {"id", "kind", "type", "title", "status", "created"}) out.put(k, p.get(k));
        return out;
    }

    void deletePost(Map<String, Object> post) throws IOException {
        Files.deleteIfExists(store.file((String) post.get("id")));
        store.delete("posts", (String) post.get("id"));
    }

    /** Deletes everything a player shared (when they delete their account). */
    public void deleteEverythingOf(String uuid) throws IOException {
        for (String collection : new String[] {"posts", "capes"}) {
            for (Map<String, Object> p : store.all(collection)) {
                if (uuid.equals(p.get("owner"))) {
                    Files.deleteIfExists(store.file((String) p.get("id")));
                    store.delete(collection, (String) p.get("id"));
                }
            }
        }
    }

    boolean isAdmin(Http.Request req) {
        String key = req.header("X-Admin-Key");
        return key != null && !config.adminKeyHash.isEmpty() && Secrets.same(Secrets.sha256(key), config.adminKeyHash);
    }

    void admin(Http.Request req) {
        if (!isAdmin(req)) throw problem(403, "Admins only.");
    }

    private static BufferedImage read(byte[] data) {
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
