package io.pack200.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.pack200.Pack200;

class FileBitsSpillTest {

    @Test
    void explicitSpillDirWinsOverTmpdir(@TempDir Path tmp) throws Exception {
        File spill = tmp.resolve("spill").toFile();
        assertTrue(spill.mkdirs());
        File otherTmp = tmp.resolve("not-used-tmpdir").toFile();
        assertTrue(otherTmp.mkdirs());
        String prevTmp = System.getProperty("java.io.tmpdir");
        String prevSpill = System.getProperty(FileBitsSpill.SYS_SPILL_DIR);
        try {
            System.setProperty("java.io.tmpdir", otherTmp.getAbsolutePath());
            System.setProperty(FileBitsSpill.SYS_SPILL_DIR, spill.getAbsolutePath());
            File resolved = FileBitsSpill.resolveDirectory(null);
            assertEquals(spill.getCanonicalFile(), resolved.getCanonicalFile());
            FileBitsSpill.Handle handle = FileBitsSpill.create(null, 64);
            assertTrue(handle.path.startsWith(spill.toPath()));
            assertFalse(handle.path.startsWith(otherTmp.toPath()));
            handle.release();
            assertFalse(Files.exists(handle.path));
        } finally {
            restoreProperty("java.io.tmpdir", prevTmp);
            restoreProperty(FileBitsSpill.SYS_SPILL_DIR, prevSpill);
        }
    }

    @Test
    void implicitParentUsedWhenNoProperty(@TempDir Path tmp) throws Exception {
        File parent = tmp.resolve("cache-slot").toFile();
        assertTrue(parent.mkdirs());
        String prevSpill = System.getProperty(FileBitsSpill.SYS_SPILL_DIR);
        try {
            System.clearProperty(FileBitsSpill.SYS_SPILL_DIR);
            File resolved = FileBitsSpill.resolveDirectory(parent);
            assertEquals(parent.getCanonicalFile(), resolved.getCanonicalFile());
        } finally {
            restoreProperty(FileBitsSpill.SYS_SPILL_DIR, prevSpill);
        }
    }

    @Test
    void tmpdirLastResortFailsLoudlyWhenUnwritable(@TempDir Path tmp) throws Exception {
        File notADir = tmp.resolve("not-a-dir").toFile();
        Files.writeString(notADir.toPath(), "x");
        String prevTmp = System.getProperty("java.io.tmpdir");
        String prevSpill = System.getProperty(FileBitsSpill.SYS_SPILL_DIR);
        try {
            System.clearProperty(FileBitsSpill.SYS_SPILL_DIR);
            System.setProperty("java.io.tmpdir", notADir.getAbsolutePath());
            IOException thrown = assertThrows(IOException.class,
                    () -> FileBitsSpill.resolveDirectory(null));
            assertTrue(thrown.getMessage().contains("unpack.spill.dir")
                    || thrown.getMessage().contains("java.io.tmpdir")
                    || thrown.getMessage().contains("not-a-dir"));
        } finally {
            restoreProperty("java.io.tmpdir", prevTmp);
            restoreProperty(FileBitsSpill.SYS_SPILL_DIR, prevSpill);
        }
    }

    @Test
    void unpackLargeResourceMatchesAndDeletesSpill(@TempDir Path tmp) throws Exception {
        byte[] blob = patternedBytes(5 * 1024 * 1024 + 17);
        File pack = packJarWithBlob(tmp, "blob.bin", blob);
        File spillDir = tmp.resolve("spill").toFile();
        assertTrue(spillDir.mkdirs());
        File outJar = tmp.resolve("out.jar").toFile();
        Pack200.Unpacker unpacker = Pack200.newUnpacker();
        unpacker.properties().put(Pack200.Unpacker.SPILL_DIR, spillDir.getAbsolutePath());
        try (InputStream in = Files.newInputStream(pack.toPath());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outJar.toPath()))) {
            unpacker.unpack(in, jos);
        }
        assertArrayEquals(blob, readJarEntry(outJar, "blob.bin"));
        assertEquals(0, countSpillFiles(spillDir.toPath()));
    }

    @Test
    void unpackFileSpillsBesidePackNotTmpdir(@TempDir Path tmp) throws Exception {
        File packDir = tmp.resolve("pack-parent").toFile();
        assertTrue(packDir.mkdirs());
        byte[] blob = patternedBytes(64 * 1024);
        File pack = packJarWithBlob(packDir.toPath(), "native.bin", blob);
        File otherTmp = tmp.resolve("tmpdir").toFile();
        assertTrue(otherTmp.mkdirs());
        String prevTmp = System.getProperty("java.io.tmpdir");
        String prevSpill = System.getProperty(FileBitsSpill.SYS_SPILL_DIR);
        try {
            System.setProperty("java.io.tmpdir", otherTmp.getAbsolutePath());
            System.clearProperty(FileBitsSpill.SYS_SPILL_DIR);
            File outJar = tmp.resolve("from-file.jar").toFile();
            Pack200.Unpacker unpacker = Pack200.newUnpacker();
            unpacker.properties().put(Pack200.Unpacker.SPILL_THRESHOLD, "1");
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outJar.toPath()))) {
                unpacker.unpack(pack, jos);
            }
            assertArrayEquals(blob, readJarEntry(outJar, "native.bin"));
            assertEquals(0, countSpillFiles(otherTmp.toPath()));
            assertEquals(0, countSpillFiles(packDir.toPath()));
        } finally {
            restoreProperty("java.io.tmpdir", prevTmp);
            restoreProperty(FileBitsSpill.SYS_SPILL_DIR, prevSpill);
        }
    }

    @Test
    void storedAndDeflatedMatch(@TempDir Path tmp) throws Exception {
        byte[] blob = patternedBytes(256 * 1024);
        File pack = packJarWithBlob(tmp, "data.bin", blob);
        byte[] stored = unpackWithHint(tmp, pack, Pack200.Unpacker.FALSE);
        byte[] deflated = unpackWithHint(tmp, pack, Pack200.Unpacker.TRUE);
        assertArrayEquals(blob, stored);
        assertArrayEquals(blob, deflated);
    }

    @Test
    void failedReadLeavesNoSpillOrphans(@TempDir Path tmp) throws Exception {
        byte[] blob = patternedBytes(128 * 1024);
        File pack = packJarWithBlob(tmp, "big.bin", blob);
        File spillDir = tmp.resolve("spill-fail").toFile();
        assertTrue(spillDir.mkdirs());
        Pack200.Unpacker unpacker = Pack200.newUnpacker();
        unpacker.properties().put(Pack200.Unpacker.SPILL_DIR, spillDir.getAbsolutePath());
        unpacker.properties().put(Pack200.Unpacker.SPILL_THRESHOLD, "1");
        File outJar = tmp.resolve("fail.jar").toFile();
        assertThrows(IOException.class, () -> {
            try (InputStream raw = Files.newInputStream(pack.toPath());
                 InputStream in = new FailAfterBytes(raw, pack.length() - 32);
                 JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outJar.toPath()))) {
                unpacker.unpack(in, jos);
            }
        });
        assertEquals(0, countSpillFiles(spillDir.toPath()));
    }

    private static byte[] unpackWithHint(Path tmp, File pack, String deflateHint) throws Exception {
        File out = Files.createTempFile(tmp, "u-", ".jar").toFile();
        Pack200.Unpacker unpacker = Pack200.newUnpacker();
        unpacker.properties().put(Pack200.Unpacker.DEFLATE_HINT, deflateHint);
        unpacker.properties().put(Pack200.Unpacker.SPILL_DIR, tmp.toString());
        unpacker.properties().put(Pack200.Unpacker.SPILL_THRESHOLD, "1");
        try (InputStream in = Files.newInputStream(pack.toPath());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out.toPath()))) {
            unpacker.unpack(in, jos);
        }
        return readJarEntry(out, "data.bin");
    }

    private static File packJarWithBlob(Path dir, String entryName, byte[] blob) throws Exception {
        File jar = Files.createTempFile(dir, "src-", ".jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            JarEntry je = new JarEntry(entryName);
            je.setMethod(ZipEntry.DEFLATED);
            jos.putNextEntry(je);
            jos.write(blob);
            jos.closeEntry();
        }
        File pack = new File(jar.getPath() + ".pack");
        Pack200.Packer packer = Pack200.newPacker();
        packer.properties().put(Pack200.Packer.SEGMENT_LIMIT, "-1");
        try (JarFile jf = new JarFile(jar);
             OutputStream out = Files.newOutputStream(pack.toPath())) {
            packer.pack(jf, out);
        }
        return pack;
    }

    private static byte[] readJarEntry(File jar, String name) throws Exception {
        try (JarFile jf = new JarFile(jar)) {
            JarEntry je = jf.getJarEntry(name);
            try (InputStream in = jf.getInputStream(je)) {
                return in.readAllBytes();
            }
        }
    }

    private static byte[] patternedBytes(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i * 31 + (i >>> 8));
        }
        return b;
    }

    private static long countSpillFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("p200-") && n.endsWith(".bits");
                    })
                    .count();
        }
    }

    private static void restoreProperty(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }

    /** Cuts the pack stream short so readFiles fails after spill is open. */
    private static final class FailAfterBytes extends FilterInputStream {
        private long left;

        FailAfterBytes(InputStream in, long allow) {
            super(new BufferedInputStream(in));
            this.left = allow;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                throw new IOException("test cut");
            }
            int b = super.read();
            if (b >= 0) {
                left--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                throw new IOException("test cut");
            }
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) {
                left -= n;
            }
            return n;
        }
    }
}
