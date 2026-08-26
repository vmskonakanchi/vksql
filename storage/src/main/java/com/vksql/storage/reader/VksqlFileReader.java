package com.vksql.storage.reader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

import com.vksql.storage.format.FileFooter;
import com.vksql.storage.writer.FileFooterDeSerializer;

public class VksqlFileReader {
    private final Path path;
    private FileFooter fileFooter;

    public VksqlFileReader(Path path) {
        this.path = path;

        try {
            read();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void read() throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");

        raf.seek(raf.length() - 8);

        int footerLength = raf.readInt(); // 4 bytes
        byte[] magicByte = new byte[4];
        raf.readFully(magicByte); // 4 bytes

        raf.seek(raf.length() - 8 - footerLength);

        byte[] footerBytes = new byte[footerLength];

        raf.readFully(footerBytes);

        fileFooter = new FileFooterDeSerializer(footerBytes)
                .deSerialize();

        raf.close();

    }

    public FileFooter getFooter() {
        return this.fileFooter;
    }
}
