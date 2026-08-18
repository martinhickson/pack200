package io.pack200.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import io.pack200.Pack200;

/**
 * After the webstart plugin normalizes (pack/unpack) and signs third-party
 * jars, pack with JDK 11 {@code pack200} and unpack with {@code io.pack200}.
 * Signatures on the unpacked jars must still verify.
 */
class SignedJarUnpackIT {

    @Test
    void jdk11PackThenIoPack200UnpackKeepsSignatures() throws Exception {
        Path jnlpDir = requiredDir("jnlp.workDirectory");
        Path work = Path.of(System.getProperty("it.workDirectory", "target/it-work"));
        Path packedDir = work.resolve("packed");
        Path unpackedDir = work.resolve("unpacked");
        Path spillDir = work.resolve("spill");
        Files.createDirectories(packedDir);
        Files.createDirectories(unpackedDir);
        Files.createDirectories(spillDir);

        List<Path> signedJars = signedJars(jnlpDir);
        assertFalse(signedJars.isEmpty(),
                "webstart produced no signed jars under " + jnlpDir
                        + " (normalize is pack200.enabled pack/unpack before sign)");

        int checked = 0;
        for (Path signed : signedJars) {
            Path packGz = packedDir.resolve(signed.getFileName().toString() + ".pack.gz");
            Path unpacked = unpackedDir.resolve(signed.getFileName().toString());
            Jdk11Pack200.packGzip(signed, packGz);
            unpackWithIoPack200(packGz, unpacked, spillDir);
            assertEquals(0, countSpillOrphans(spillDir), "leftover p200-*.bits after " + signed.getFileName());
            assertSignaturesMatch(signed, unpacked);
            jarsignerVerified(unpacked);
            checked++;
        }
        assertTrue(checked >= 3, "expected fixture + third-party jars, got " + checked);
    }

    private static void unpackWithIoPack200(Path packGz, Path outJar, Path spillDir) throws IOException {
        Pack200.Unpacker unpacker = Pack200.newUnpacker();
        unpacker.properties().put(Pack200.Unpacker.SPILL_DIR, spillDir.toAbsolutePath().toString());
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(packGz)));
             JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(outJar)))) {
            unpacker.unpack(in, jos);
        }
    }

    private static void assertSignaturesMatch(Path signed, Path unpacked) throws Exception {
        try (JarFile expected = new JarFile(signed.toFile(), true);
             JarFile actual = new JarFile(unpacked.toFile(), true)) {
            List<String> sigNames = signatureEntryNames(expected);
            assertFalse(sigNames.isEmpty(), signed.getFileName() + " has no META-INF signature files");
            for (String name : sigNames) {
                assertEquals(sha256(expected, name), sha256(actual, name), name);
            }
            Enumeration<JarEntry> entries = actual.entries();
            int signedEntries = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                try (InputStream in = actual.getInputStream(entry)) {
                    in.readAllBytes();
                }
                if (entry.isDirectory() || isSignatureFile(entry.getName())) {
                    continue;
                }
                assertNotNull(entry.getCodeSigners(),
                        unpacked.getFileName() + " entry not signed: " + entry.getName());
                signedEntries++;
            }
            assertTrue(signedEntries > 0, unpacked.getFileName() + " had no signed entries");
        }
    }

    private static void jarsignerVerified(Path jar) throws Exception {
        Path jarsigner = jarsignerTool();
        Process process = new ProcessBuilder(jarsigner.toString(), "-verify", jar.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            fail("jarsigner -verify timed out for " + jar.getFileName());
        }
        assertEquals(0, process.exitValue(), "jarsigner -verify failed for " + jar.getFileName() + "\n" + output);
        assertTrue(output.contains("jar verified"), output);
    }

    private static List<Path> signedJars(Path jnlpDir) throws IOException {
        try (Stream<Path> stream = Files.list(jnlpDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".jar")
                                && !n.startsWith("unprocessed_")
                                && !n.endsWith(".pack.gz");
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static List<String> signatureEntryNames(JarFile jar) {
        List<String> names = new ArrayList<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (isSignatureFile(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static boolean isSignatureFile(String name) {
        String upper = name.toUpperCase();
        return upper.equals("META-INF/MANIFEST.MF")
                || (upper.startsWith("META-INF/") && (upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC")));
    }

    private static String sha256(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, "missing " + name);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = jar.getInputStream(entry)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
        }
        return toHex(md.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static long countSpillOrphans(Path spillDir) throws IOException {
        if (!Files.isDirectory(spillDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(spillDir)) {
            return stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("p200-") && n.endsWith(".bits");
                    })
                    .count();
        }
    }

    private static Path requiredDir(String property) {
        String raw = System.getProperty(property);
        assertNotNull(raw, "missing system property " + property);
        Path dir = Path.of(raw);
        assertTrue(Files.isDirectory(dir), property + " is not a directory: " + dir);
        return dir;
    }

    private static Path jarsignerTool() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        String name = File.separatorChar == '\\' ? "jarsigner.exe" : "jarsigner";
        Path candidate = javaHome.resolve("bin").resolve(name);
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        if (javaHome.getParent() != null) {
            candidate = javaHome.getParent().resolve("bin").resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("jarsigner not found under " + javaHome);
    }
}
