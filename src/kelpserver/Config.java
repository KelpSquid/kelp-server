package kelpserver;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * The server's settings, from server.properties in its data folder. Secrets (the admin key, the mail password) live
 * only there, never in the code. Anything not set has a safe default for testing on one computer.
 */
public final class Config {
    public final int port;
    public final String publicUrl;
    /** Mojang's session server, which says whether a player really signed in (changeable for tests). */
    public final String mojangSessions;
    /** The admin key's SHA-256, in hex. The key itself is only known to the admin. */
    public final String adminKeyHash;
    public final List<String> devPlayers;
    public final LocalDate launch;
    public final String mailHost;
    public final int mailPort;
    public final String mailUser;
    public final String mailPassword;
    public final String mailFrom;

    public Config(Properties p) {
        port = Integer.parseInt(p.getProperty("port", "8080"));
        publicUrl = p.getProperty("publicUrl", "http://localhost:" + port).replaceAll("/$", "");
        mojangSessions = p.getProperty("mojangSessions", "https://sessionserver.mojang.com").replaceAll("/$", "");
        adminKeyHash = p.getProperty("adminKeyHash", "");
        devPlayers = Arrays.stream(p.getProperty("devPlayers", "").split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.replace("-", "").toLowerCase()).toList();
        launch = LocalDate.parse(p.getProperty("launch", "2028-01-01"));
        mailHost = p.getProperty("mailHost", "");
        mailPort = Integer.parseInt(p.getProperty("mailPort", "587"));
        mailUser = p.getProperty("mailUser", "");
        mailPassword = p.getProperty("mailPassword", "");
        mailFrom = p.getProperty("mailFrom", "Kelp <no-reply@kelplauncher.org>");
    }

    public static Config load(Path dataFolder) throws IOException {
        Properties p = new Properties();
        Path file = dataFolder.resolve("server.properties");
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file)) {
                p.load(in);
            }
        }
        return new Config(p);
    }
}
