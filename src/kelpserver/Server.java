package kelpserver;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Kelp's server: accounts, capes, emblems, badges, profiles, sharing (checked first), friends and messages, the
 * Parent Portal, the leaderboard and the website, in one small Java program with no outside libraries.
 *
 * Run it with: java -jar kelp-server.jar [data folder]. Settings go in server.properties in the data folder. It's
 * meant to sit behind Cloudflare, which handles HTTPS and passes the visitor's address along.
 */
public final class Server {
    public final Store store;
    public final Config config;
    public final Mail mail;
    public final Accounts accounts;
    private HttpServer http;

    public Server(Path data, Config config, Mail mail) throws IOException {
        this.store = new Store(data);
        this.config = config;
        this.mail = mail;
        this.accounts = new Accounts(store, config);
    }

    public Http.Router router() {
        Http.Router r = new Http.Router();
        Players players = new Players(store, config, accounts);
        Moderation moderation = new Moderation(store, config, accounts);
        Social social = new Social(store, accounts);
        Parents parents = new Parents(store, config, mail, accounts, social, uuid -> deleteEverything(uuid, moderation, social));
        accounts.routes(r);
        players.routes(r);
        moderation.routes(r);
        social.routes(r);
        parents.routes(r);
        new Pages(players, store).routes(r);
        // Deleting your account deletes everything about you
        r.add("DELETE /api/me", req -> {
            Map<String, Object> me = accounts.require(req);
            deleteEverything(Players.uuid(me), moderation, social);
            return Http.Response.ok();
        });
        return r;
    }

    private void deleteEverything(String uuid, Moderation moderation, Social social) throws IOException {
        moderation.deleteEverythingOf(uuid);
        social.deleteEverythingOf(uuid);
        accounts.deleteAccount(uuid);
    }

    /** Starts answering on the configured port (0 picks a free one, for tests) and gives back the port. */
    public int start(int port) throws IOException {
        Http.Router router = router();
        http = HttpServer.create(new InetSocketAddress(port), 128);
        http.createContext("/", router::handle);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return http.getAddress().getPort();
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    public static void main(String[] args) throws IOException {
        Path data = Path.of(args.length > 0 ? args[0] : "data");
        Config config = Config.load(data);
        Server server = new Server(data, config, new Mail(config));
        int port = server.start(config.port);
        System.out.println("Kelp's server is running on port " + port + " (" + config.publicUrl + "), data in " + data.toAbsolutePath());
        if (config.adminKeyHash.isEmpty()) System.out.println("No admin key set: put adminKeyHash=<sha256 of your key> in server.properties.");
    }
}
