package io.pack200.it;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Invokes the JDK 11 {@code pack200} tool. Used to normalize ({@code --repack})
 * and to produce {@code .pack.gz} files. Unpacking under test is
 * {@code io.pack200}, not this tool.
 */
public final class Jdk11Pack200 {

    private Jdk11Pack200() {}

    public static Path tool() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        String name = File.separatorChar == '\\' ? "pack200.exe" : "pack200";
        Path candidate = javaHome.resolve("bin").resolve(name);
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        if (javaHome.getFileName() != null && "jre".equalsIgnoreCase(javaHome.getFileName().toString())) {
            candidate = javaHome.getParent().resolve("bin").resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("JDK 11 pack200 not found under " + javaHome
                + " — this module requires a full JDK 11 (not a JRE)");
    }

    /** In-place pack then unpack so class files match what the unpacker will emit. */
    public static void repack(Path jar) throws IOException, InterruptedException {
        run(List.of(tool().toString(), "--repack", jar.toAbsolutePath().toString()));
    }

    public static void packGzip(Path jar, Path packGz) throws IOException, InterruptedException {
        Files.createDirectories(packGz.getParent());
        run(List.of(
                tool().toString(),
                packGz.toAbsolutePath().toString(),
                jar.toAbsolutePath().toString()));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !"repack".equals(args[0])) {
            throw new IllegalArgumentException("Usage: Jdk11Pack200 repack <dir-of-jars>");
        }
        Path dir = Path.of(args[1]);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + dir);
        }
        try (var stream = Files.list(dir)) {
            List<Path> jars = stream
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .collect(Collectors.toList());
            if (jars.isEmpty()) {
                throw new IllegalStateException("No jars to --repack in " + dir);
            }
            for (Path jar : jars) {
                System.out.println("pack200 --repack " + jar.getFileName());
                repack(jar);
            }
        }
    }

    private static void run(List<String> command) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(command);
        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("Timed out: " + cmd);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Command failed (" + process.exitValue() + "): " + cmd + "\n" + output);
        }
    }
}
