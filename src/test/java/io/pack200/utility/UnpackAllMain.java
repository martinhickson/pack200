package io.pack200.utility;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.GZIPInputStream;

import io.pack200.Pack200;

public class UnpackAllMain {

    public static List<File> findFilesByExtension(File startPath, String extension) {
        List<File> matches = new ArrayList<>();
        File[] files = startPath.listFiles();
        if (files == null) {
            return matches;
        }
        MatchExtensionPredicate filter = new MatchExtensionPredicate(extension);

        for (File file : files) {
            if (file.isDirectory()) {
                // Recurse into subdirectories
                matches.addAll(findFilesByExtension(file, extension));
            } else if (filter.test(file.toPath())) {
                // Add the file to the list if it matches the extension
                matches.add(file);
            }
        }
        return matches;
    }

    private static List<File> getAllPackGzFiles(String path) {
        return findFilesByExtension(new File(path), ".pack.gz");
    }

    private static void unpackJar(File file) {
        try {
            InputStream in = new GZIPInputStream(new FileInputStream(file));
            Pack200.Unpacker unpacker = Pack200.newUnpacker();
            String jarPath = file.getAbsolutePath().replaceAll(".jar.pack.gz", ".jar");
            JarOutputStream out = new JarOutputStream(new FileOutputStream(jarPath));
            System.out.println("output jar path = " + jarPath);
            unpacker.unpack(in, out);
            out.close();
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {
        // Replace with your actual file path
        String path = "C:/applications/wildfly/standalone/deployments/sonata.ear/sonata.war/downloads";
        List<File> result = getAllPackGzFiles(path);
        for (File file : result) {
            System.out.println(file.getAbsolutePath());
            unpackJar(file);
        }
    }
}