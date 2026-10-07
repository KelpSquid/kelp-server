package kelpserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Where the server keeps everything: a folder per collection ("accounts", "posts"...) with one JSON file per record,
 * plus a folder of uploaded files. Records are read once and kept in memory; every change is written to a temporary
 * file first and then swapped in, so a crash or power cut never leaves half a record. Simple, and plenty for many
 * thousands of players; easy to back up (copy the folder).
 */
public final class Store {
    private final Path root;
    private final Map<String, Map<String, Map<String, Object>>> cache = new ConcurrentHashMap<>();

    public Store(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    public Path root() {
        return root;
    }

    /** Ids are kept to letters, numbers, - and _ so they're always safe as file names. */
    public static String safeId(String id) {
        if (id == null || id.isEmpty() || id.length() > 128 || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("bad id");
        }
        return id;
    }

    private synchronized Map<String, Map<String, Object>> collection(String name) {
        return cache.computeIfAbsent(safeId(name), n -> {
            Map<String, Map<String, Object>> records = new ConcurrentHashMap<>();
            Path folder = root.resolve(n);
            if (Files.isDirectory(folder)) {
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                        try {
                            Map<String, Object> record = Json.object(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
                            if (record != null) records.put(f.getFileName().toString().replace(".json", ""), record);
                        } catch (RuntimeException e) {
                            System.err.println("Skipping a damaged record " + f + ": " + e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return records;
        });
    }

    /** A copy of a record, or null. */
    public Map<String, Object> get(String collection, String id) {
        Map<String, Object> record = collection(collection).get(safeId(id));
        return record == null ? null : copy(record);
    }

    public boolean has(String collection, String id) {
        return collection(collection).containsKey(safeId(id));
    }

    public synchronized void put(String collection, String id, Map<String, Object> record) throws IOException {
        Path folder = root.resolve(safeId(collection));
        Files.createDirectories(folder);
        Path file = folder.resolve(safeId(id) + ".json");
        Path part = folder.resolve(id + ".json.part");
        Files.writeString(part, Json.write(record), StandardCharsets.UTF_8);
        try {
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        }
        collection(collection).put(id, copy(record));
    }

    /** Changes a record (or makes it, starting from an empty one) under the store's lock, and saves it. */
    public synchronized Map<String, Object> update(String collection, String id, UnaryOperator<Map<String, Object>> change) throws IOException {
        Map<String, Object> record = get(collection, id);
        Map<String, Object> changed = change.apply(record == null ? new LinkedHashMap<>() : record);
        put(collection, id, changed);
        return changed;
    }

    public synchronized void delete(String collection, String id) throws IOException {
        Files.deleteIfExists(root.resolve(safeId(collection)).resolve(safeId(id) + ".json"));
        collection(collection).remove(id);
    }

    /** The ids of every record in a collection. */
    public List<String> ids(String collection) {
        return new ArrayList<>(collection(collection).keySet());
    }

    /** Copies of every record in a collection. */
    public List<Map<String, Object>> all(String collection) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : collection(collection).values()) out.add(copy(r));
        return out;
    }

    // ---- Uploaded files (pictures, clips) ----

    public Path file(String id) {
        return root.resolve("files").resolve(safeId(id));
    }

    public void saveFile(String id, byte[] data) throws IOException {
        Files.createDirectories(root.resolve("files"));
        Path part = root.resolve("files").resolve(safeId(id) + ".part");
        Files.write(part, data);
        Files.move(part, file(id), StandardCopyOption.REPLACE_EXISTING);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> record) {
        return (Map<String, Object>) deepCopy(record);
    }

    private static Object deepCopy(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) out.add(deepCopy(x));
            return out;
        }
        return o;
    }
}
