/*
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package io.pack200;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/*
 * @test
 * @bug 8000650 8150469
 * @summary unpack200.exe should check gzip crc
 * @compile -XDignore.symbol.file Utils.java PackChecksum.java
 * @run main PackChecksum
 * @author kizune
 */
public class PackChecksumTest {
    final int TRAILER_LEN = 8;
    final List<String> cmdsList = new ArrayList<>();
    static enum Case {
        CRC32,
        ISIZE,
        BOTH;

    };

    @Test
    public void testBrokenTrailer() throws Exception {
        testBrokenTrailer(Case.CRC32); // negative
        testBrokenTrailer(Case.ISIZE); // negative
        testBrokenTrailer(Case.BOTH);  // negative
    }

    @Test
    public void testMultipleSegments() throws Exception {
        File inputJar = new File("target/input.jar");
        TestUtils.copyFile(TestUtils.getGoldenJar(), inputJar);
        cmdsList.clear();

        File testPack = new File("target/out.jar.pack.gz");

        cmdsList.clear();
        cmdsList.add(TestUtils.getJavaCmd());
        cmdsList.add("-cp");
        cmdsList.add("target/classes");
        cmdsList.add("io.pack200.Driver");
        // force multiple segments
        cmdsList.add("--segment-limit=100");
        cmdsList.add(testPack.getAbsolutePath());
        cmdsList.add(inputJar.getAbsolutePath());
        TestUtils.runExec(cmdsList);

        File destFile = new File("target/dst.jar");
        cmdsList.clear();
        cmdsList.add(TestUtils.getJavaCmd());
        cmdsList.add("-cp");
        cmdsList.add("target/classes");
        cmdsList.add("io.pack200.Driver");
        cmdsList.add("--unpack");
        cmdsList.add(testPack.getAbsolutePath());
        cmdsList.add(destFile.getAbsolutePath());

        try {
            TestUtils.runExec(cmdsList);
            assertTrue("file not created: " + destFile, destFile.exists());
        } finally {
            if (inputJar.exists())
                inputJar.delete();
            if (testPack.exists())
                testPack.delete();
            if (destFile.exists())
                destFile.delete();
        }
    }

    void testBrokenTrailer(Case type) throws Exception {
        System.out.println("Testing: case " + type);
        // Create a fresh .jar file
        File testFile = new File("target/src_tools.jar");
        File testPack = new File("target/src_tools.pack.gz");
        generateJar(testFile);

        cmdsList.clear();
        // Create .pack file
        cmdsList.add(TestUtils.getJavaCmd());
        cmdsList.add("-cp");
        cmdsList.add("target/classes");
        cmdsList.add("io.pack200.Driver");
        cmdsList.add(testPack.getAbsolutePath());
        cmdsList.add(testFile.getAbsolutePath());
        TestUtils.runExec(cmdsList);

        // mutate the checksum of the packed file
        RandomAccessFile raf = new RandomAccessFile(testPack, "rw");

        switch (type) {
            case CRC32:
                raf.seek(raf.length() - TRAILER_LEN);
                raf.writeInt(0x0dea0a0d);
                break;
            case ISIZE:
                raf.seek(raf.length() - (TRAILER_LEN/2));
                raf.writeInt(0x0b0e0e0f);
                break;
            default:
                raf.seek(raf.length() - (TRAILER_LEN));
                raf.writeLong(0x0dea0a0d0b0e0e0fL);
                break;
        }

        raf.close();

        File dstFile = new File("target/dst_tools.jar");
        if (dstFile.exists()) {
            dstFile.delete();
        }
        cmdsList.clear();
        cmdsList.add(TestUtils.getJavaCmd());
        cmdsList.add("-cp");
        cmdsList.add("target/classes");
        cmdsList.add("io.pack200.Driver");
        cmdsList.add("--unpack");
        cmdsList.add(testPack.getAbsolutePath());
        cmdsList.add(dstFile.getAbsolutePath());

        boolean processFailed = false;
        try {
            TestUtils.runExec(cmdsList);
        } catch (RuntimeException re) {
            // unpack200 should exit with non-zero exit code
            processFailed = true;
        } finally {
            // tidy up
            if (testFile.exists())
                testFile.delete();

            if (testPack.exists())
                testPack.delete();

            if (!processFailed) {
                throw new Exception("case " + type +
                        ": file with incorrect CRC, unpacked without the error.");
            }
            if (dstFile.exists()) {
                dstFile.delete();
                //throw new Exception("case " + type + ":  file exists: " + dstFile);
            }
        }
    }

    void generateJar(File result) throws IOException {
        if (result.exists()) {
            result.delete();
        }

        try (JarOutputStream output = new JarOutputStream(new FileOutputStream(result)); ) {
            for (int i = 0 ; i < 100 ; i++) {
                JarEntry e = new JarEntry("F-" + i + ".txt");
                output.putNextEntry(e);
            }
            output.flush();
            output.close();
        }
    }

}
