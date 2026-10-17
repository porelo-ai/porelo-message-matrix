package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Holds a file's lock from a process of its own, as a second poll of the same account would, until
 * its stdin closes. Says "held" on stdout once it holds it.
 */
final class LockHolder {

    private LockHolder() {}

    public static void main(final String[] args) throws IOException {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            System.out.println("held");
            System.out.flush();
            while (System.in.read() >= 0) {
                // until the test closes stdin
            }
        }
    }
}
