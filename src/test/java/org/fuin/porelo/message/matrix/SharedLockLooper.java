package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

/** Poll lock acquisitions in a separate JVM, since file locks belong to a process. */
final class SharedLockLooper {

    private SharedLockLooper() {}

    /** Runs back-to-back polls, or one acquisition whose exit code is the poll's refusal. */
    public static void main(final String[] args) throws IOException, InterruptedException {
        final Path state = Path.of(args[0]);
        final boolean once = "once".equals(args[1]);
        while (true) {
            try (FileChannel channel = Keep.lockFile(state);
                    FileLock held = Keep.shared(channel, state)) {
                System.out.println("held");
                System.out.flush();
                if (once) {
                    return;
                }
                if ("hold".equals(args[1])) {
                    while (System.in.read() >= 0) {
                        // The test releases this poll by closing stdin.
                    }
                    return;
                }
                Thread.sleep(ThreadLocalRandom.current().nextLong(1000, 3001));
            } catch (final Failure failure) {
                if (once) {
                    System.err.println(failure.getMessage());
                    System.exit(failure.exitCode());
                }
                // Porelo retries a refused poll; the backup must be able to finish meanwhile.
                Thread.sleep(10);
            }
        }
    }
}
