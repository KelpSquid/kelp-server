import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Builds kelp-server.jar (java tools/Build.java), or with "test" also runs the end-to-end test first.
 * Needs only a JDK 21 or newer: no build tools, no downloads.
 */
public class Build {
    public static void main(String[] args) throws Exception {
        Path out = Path.of("build");
        Path classes = out.resolve("classes");
        delete(out);
        Files.createDirectories(classes);
        compile(list(Path.of("src")), classes);
        if (args.length > 0 && args[0].equals("test")) {
            Path testClasses = out.resolve("test");
            List<Path> sources = list(Path.of("src"));
            sources.addAll(list(Path.of("test")));
            compile(sources, testClasses);
            Process test = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Djava.awt.headless=true",
                    "-cp", testClasses.toString(), "kelpserver.ServerTest").inheritIO().start();
            if (test.waitFor() != 0) throw new IllegalStateException("tests failed");
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "kelpserver.Server");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(out.resolve("kelp-server.jar")), manifest);
             Stream<Path> files = Files.walk(classes)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(f).toString().replace(java.io.File.separatorChar, '/')));
                jar.write(Files.readAllBytes(f));
                jar.closeEntry();
            }
        }
        System.out.println("Built build/kelp-server.jar");
    }

    static List<Path> list(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            return new ArrayList<>(files.filter(f -> f.toString().endsWith(".java")).toList());
        }
    }

    static void compile(List<Path> sources, Path out) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                "-encoding", "UTF-8", "--release", "21", "-d", out.toString()));
        for (Path s : sources) command.add(s.toString());
        if (new ProcessBuilder(command).inheritIO().start().waitFor() != 0) throw new IllegalStateException("compile failed");
    }

    static void delete(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> files = Files.walk(p)) {
            for (Path f : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }
}
