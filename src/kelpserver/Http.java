package kelpserver;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small web framework on top of Java's built-in HTTP server: routes like "GET /api/players/{uuid}", requests with
 * their path values, query, headers and body, and answers as JSON, HTML or files. Every route says how big a body it
 * takes; bigger ones are turned away before they're read. Every address gets a limited number of requests a minute.
 */
public final class Http {
    private Http() {
    }

    /** A problem to answer with: an HTTP status and a message for the player. */
    public static final class Problem extends RuntimeException {
        public final int status;

        public Problem(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static Problem problem(int status, String message) {
        return new Problem(status, message);
    }

    public record Request(String method, String path, Map<String, String> pathValues, Map<String, String> query,
                          Map<String, String> headers, byte[] body, String address) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }

        public String value(String name) {
            return pathValues.get(name);
        }

        public String query(String name) {
            return query.get(name);
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** The body as a JSON object (an empty body is an empty object). */
        public Map<String, Object> json() {
            if (body.length == 0) return new LinkedHashMap<>();
            try {
                Map<String, Object> map = Json.object(Json.parse(text()));
                if (map == null) throw problem(400, "Expected a JSON object.");
                return map;
            } catch (IllegalArgumentException e) {
                throw problem(400, "That isn't valid JSON.");
            }
        }

        /** A text field from the JSON body, required, at most max long. */
        public String field(Map<String, Object> json, String name, int max) {
            Object v = json.get(name);
            if (!(v instanceof String s) || s.isBlank()) throw problem(400, "Missing " + name + ".");
            if (s.length() > max) throw problem(400, name + " is too long.");
            return s.strip();
        }
    }

    public record Response(int status, String type, byte[] body, Map<String, String> headers) {
        public static Response json(Object value) {
            return json(200, value);
        }

        public static Response json(int status, Object value) {
            return new Response(status, "application/json; charset=utf-8", Json.write(value).getBytes(StandardCharsets.UTF_8), Map.of());
        }

        public static Response html(String page) {
            return new Response(200, "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        public static Response bytes(String type, byte[] body) {
            return new Response(200, type, body, Map.of("Cache-Control", "public, max-age=300"));
        }

        public static Response ok() {
            return json(Map.of("ok", true));
        }

        public static Response redirect(String to) {
            return new Response(302, "text/plain", new byte[0], Map.of("Location", to));
        }
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    private record Route(String method, String[] parts, int maxBody, String limitGroup, Handler handler) {
    }

    /** The routes, and sending each request to the right one. */
    public static final class Router {
        private final List<Route> routes = new ArrayList<>();
        private final RateLimit limits = new RateLimit();

        /** A route like "GET /api/players/{uuid}". maxBody is the biggest body in bytes; limit names its rate group. */
        public void add(String route, int maxBody, String limitGroup, Handler handler) {
            String[] mp = route.split(" ", 2);
            routes.add(new Route(mp[0], mp[1].split("/"), maxBody, limitGroup, handler));
        }

        public void add(String route, Handler handler) {
            add(route, 64 * 1024, "api", handler);
        }

        public void setLimit(String group, int perMinute) {
            limits.set(group, perMinute);
        }

        void handle(HttpExchange exchange) throws IOException {
            Response response;
            try {
                response = route(exchange);
            } catch (Problem p) {
                response = Response.json(p.status, Map.of("error", p.getMessage()));
            } catch (IllegalArgumentException e) { // a bad id or number in the request
                response = Response.json(400, Map.of("error", "That request doesn't look right."));
            } catch (Exception e) {
                System.err.println("Error on " + exchange.getRequestURI() + ": " + e);
                e.printStackTrace();
                response = Response.json(500, Map.of("error", "Something went wrong on the server."));
            }
            for (Map.Entry<String, String> h : response.headers().entrySet()) exchange.getResponseHeaders().add(h.getKey(), h.getValue());
            exchange.getResponseHeaders().set("Content-Type", response.type());
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            if (response.type().startsWith("text/html")) {
                // Pages run only our own script and styles, and can't be put inside other sites
                exchange.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
            }
            byte[] body = exchange.getRequestMethod().equals("HEAD") ? new byte[0] : response.body();
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        }

        private Response route(HttpExchange exchange) throws Exception {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            String[] parts = path.split("/");
            boolean pathMatched = false;
            for (Route r : routes) {
                Map<String, String> values = match(r.parts(), parts);
                if (values == null) continue;
                pathMatched = true;
                if (!r.method().equals(method) && !(method.equals("HEAD") && r.method().equals("GET"))) continue;
                String address = address(exchange);
                if (!limits.allow(r.limitGroup(), address)) throw problem(429, "Too many requests. Wait a minute and try again.");
                byte[] body = readBody(exchange, r.maxBody());
                Map<String, String> headers = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> h : exchange.getRequestHeaders().entrySet()) {
                    if (!h.getValue().isEmpty()) headers.put(h.getKey().toLowerCase(), h.getValue().get(0));
                }
                return r.handler().handle(new Request(method, path, values, query(exchange.getRequestURI().getRawQuery()), headers, body, address));
            }
            throw problem(pathMatched ? 405 : 404, pathMatched ? "That isn't allowed here." : "Nothing here.");
        }

        private static Map<String, String> match(String[] pattern, String[] parts) {
            if (pattern.length != parts.length) return null;
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < pattern.length; i++) {
                String p = pattern[i];
                if (p.startsWith("{") && p.endsWith("}")) {
                    String v = URLDecoder.decode(parts[i], StandardCharsets.UTF_8);
                    if (v.isEmpty() || v.length() > 128) return null;
                    values.put(p.substring(1, p.length() - 1), v);
                } else if (!p.equals(parts[i])) {
                    return null;
                }
            }
            return values;
        }

        /** The visitor's address: Cloudflare's header when the request came through it, else the connection's. */
        private static String address(HttpExchange exchange) {
            String forwarded = exchange.getRequestHeaders().getFirst("CF-Connecting-IP");
            if (forwarded != null && forwarded.length() < 64) return forwarded;
            return exchange.getRemoteAddress().getAddress().getHostAddress();
        }

        private static byte[] readBody(HttpExchange exchange, int max) throws IOException {
            String length = exchange.getRequestHeaders().getFirst("Content-Length");
            if (length != null) {
                try {
                    if (Long.parseLong(length) > max) throw problem(413, "That's too big.");
                } catch (NumberFormatException e) {
                    throw problem(400, "Bad Content-Length.");
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = exchange.getRequestBody()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (out.size() + n > max) throw problem(413, "That's too big.");
                    out.write(buffer, 0, n);
                }
            }
            return out.toByteArray();
        }

        private static Map<String, String> query(String raw) {
            Map<String, String> q = new LinkedHashMap<>();
            if (raw == null) return q;
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                q.putIfAbsent(k, v);
            }
            return q;
        }
    }

    /** At most so many requests a minute from one address, per group of routes. */
    static final class RateLimit {
        private final Map<String, Integer> perMinute = new ConcurrentHashMap<>();
        private final Map<String, int[]> counts = new ConcurrentHashMap<>(); // key -> {minute, count}

        void set(String group, int limit) {
            perMinute.put(group, limit);
        }

        boolean allow(String group, String address) {
            int limit = perMinute.getOrDefault(group, 240);
            int minute = (int) (System.currentTimeMillis() / 60_000);
            int[] c = counts.compute(group + "|" + address, (k, old) -> old == null || old[0] != minute ? new int[] {minute, 1} : new int[] {minute, old[1] + 1});
            if (counts.size() > 100_000) counts.entrySet().removeIf(e -> e.getValue()[0] != minute); // forget old minutes
            return c[1] <= limit;
        }
    }

    /** Text safe to put in a web page. */
    public static String html(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
