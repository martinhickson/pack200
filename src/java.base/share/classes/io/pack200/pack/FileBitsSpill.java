/*
 * Copyright (c) 2026, IcedTea-Web / pack200 contributors.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

package io.pack200.pack;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import io.pack200.Pack200;

/**
 * Oversized pack {@code file_bits} go to a sized temp file instead of a
 * {@code ByteArrayOutputStream}.
 * <p>
 * Do <em>not</em> default to {@code java.io.tmpdir}. Locked-down VDI and
 * JNLP {@code SecurityManager} hosts often deny write there (the same reason
 * native extracts belong under the cache tree). Prefer a directory the caller
 * already writes: {@link Pack200.Unpacker#SPILL_DIR}, else the pack file's
 * parent, else a last-resort tmpdir probe that fails loudly.
 */
final class FileBitsSpill {
    static final long DEFAULT_THRESHOLD = 4L * 1024 * 1024;
    static final String SYS_SPILL_DIR = "io.pack200.unpack.spill.dir";
    static final String SYS_SPILL_THRESHOLD = "io.pack200.unpack.spill.threshold";

    private FileBitsSpill() {}

    static long threshold() {
        PropMap props = Utils.currentPropMap();
        String raw = props != null
                ? props.getProperty(Pack200.Unpacker.SPILL_THRESHOLD)
                : null;
        if (raw == null || raw.isEmpty()) {
            raw = System.getProperty(SYS_SPILL_THRESHOLD);
        }
        if (raw != null && !raw.isEmpty()) {
            try {
                long v = Long.parseLong(raw.trim());
                return v < 0 ? DEFAULT_THRESHOLD : v;
            } catch (NumberFormatException ignore) {
                return DEFAULT_THRESHOLD;
            }
        }
        return DEFAULT_THRESHOLD;
    }

    static boolean shouldSpill(long size) {
        return size > threshold() || size > Integer.MAX_VALUE;
    }

    /**
     * Directory that is already known writable, or a tmpdir last resort.
     *
     * @param implicitParent pack-file parent (or cache sibling parent); may be null
     */
    static File resolveDirectory(File implicitParent) throws IOException {
        PropMap props = Utils.currentPropMap();
        String raw = props != null
                ? props.getProperty(Pack200.Unpacker.SPILL_DIR)
                : null;
        if (raw == null || raw.isEmpty()) {
            raw = System.getProperty(SYS_SPILL_DIR);
        }
        if (raw != null && !raw.isEmpty()) {
            return requireWritableDir(new File(raw), "unpack.spill.dir");
        }
        if (implicitParent != null) {
            return requireWritableDir(implicitParent, "pack/cache parent");
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp == null || tmp.isEmpty()) {
            throw new IOException("No unpack.spill.dir and java.io.tmpdir is unset; "
                    + "set unpack.spill.dir to a writable directory next to the output JAR");
        }
        return probeTmpdir(new File(tmp));
    }

    static Handle create(File implicitParent, long size) throws IOException {
        File dir = resolveDirectory(implicitParent);
        Path path = Files.createTempFile(dir.toPath(), "p200-", ".bits");
        path.toFile().deleteOnExit();
        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            if (size > 0) {
                channel.truncate(size);
                channel.position(0);
            }
        } catch (IOException e) {
            closeQuietly(channel);
            deleteQuietly(path);
            throw e;
        }
        if (Utils.currentPropMap() != null) {
            Utils.log.info("fileBits spill size=" + size + " path=" + path);
        }
        return new Handle(path, channel);
    }

    static File requireWritableDir(File dir, String label) throws IOException {
        if (dir == null) {
            throw new IOException("Missing " + label);
        }
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create " + label + ": " + dir.getAbsolutePath());
        }
        if (!dir.canWrite()) {
            throw new IOException(label + " is not writable: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * Last resort only. A write probe fails fast when tmpdir is blocked
     * instead of dying later mid-unpack with a 90 MiB native resource.
     */
    static File probeTmpdir(File tmp) throws IOException {
        requireWritableDir(tmp, "java.io.tmpdir");
        Path probe = null;
        try {
            probe = Files.createTempFile(tmp.toPath(), "p200-", ".probe");
        } catch (IOException e) {
            throw new IOException("java.io.tmpdir is not usable for file_bits spill ("
                    + tmp.getAbsolutePath()
                    + "); set unpack.spill.dir to a directory the process can write "
                    + "(for example the JAR cache directory)", e);
        } finally {
            deleteQuietly(probe);
        }
        return tmp;
    }

    static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignore) {
        }
    }

    static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignore) {
        }
    }

    static final class Handle {
        final Path path;
        final FileChannel channel;
        long written;

        Handle(Path path, FileChannel channel) {
            this.path = path;
            this.channel = channel;
        }

        void release() {
            closeQuietly(channel);
            deleteQuietly(path);
        }
    }
}
