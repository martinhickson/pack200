package io.pack200;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarOutputStream;
import java.util.zip.GZIPInputStream;

public class Main {
	
    public static void extractFile(String zipFile, String fileName, String outputFile) throws IOException {
        // Wrap the file system in a try-with-resources statement
        // to auto-close it when finished and prevent a memory leak
        try (FileSystem fileSystem = FileSystems.newFileSystem(Paths.get(zipFile), null)) {
            Path fileToExtract = fileSystem.getPath(fileName);
            Files.copy(fileToExtract, Paths.get(outputFile), StandardCopyOption.REPLACE_EXISTING);
        }
    }
	
	public static void main(String[] args) throws IOException {
        String packGzFile = "c:/work/guava/guava.jar.pack.gz"; // Replace with your actual file path
        String outputJarFile = "c:/work/guava/guava-io-0.0.1.jar"; // Replace with the desired output JAR file path
        String outputJarFile2 = "c:/work/guava/guava-io-0.0.2.jar"; // Replace with the desired output JAR file path
        String outputClassFile = "c:/work/guava/Equivalence$Wrapper-0.0.1.class"; // Replace with the desired output JAR file path
        String outputClassFile2 = "c:/work/guava/Equivalence$Wrapper-0.0.2.class"; 
        String class1 = "com/google/common/base/Equivalence$Wrapper.class";
        
        try {
        	InputStream in = new GZIPInputStream(new FileInputStream(packGzFile));
        	Pack200.Unpacker unpacker = Pack200.newUnpacker();
        	JarOutputStream out = new JarOutputStream(new FileOutputStream(outputJarFile));
        	unpacker.unpack(in, out);
        	out.close();
        	in.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
//        try {
//        	InputStream in = new GZIPInputStream(new FileInputStream(packGzFile));
//        	java.util.jar.Pack200.Unpacker unpacker = java.util.jar.Pack200.newUnpacker();
//        	JarOutputStream out = new JarOutputStream(new FileOutputStream(outputJarFile2));
//        	unpacker.unpack(in, out);
//        	out.close();
//        	in.close();
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
        
        extractFile(outputJarFile, class1, outputClassFile);
        //extractFile(outputJarFile2, class1, outputClassFile2);
    }
}