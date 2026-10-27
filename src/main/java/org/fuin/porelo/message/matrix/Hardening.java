package org.fuin.porelo.message.matrix;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * Keeps the running transport's tokens from the account's other processes. A fresh executable is
 * dumpable whatever started it: until this runs, any process of the account reads its {@code
 * /proc/<pid>/environ}, which holds the tokens of a Porelo that hands them as the environment, and
 * its memory, which holds those read from stdin once they are read. Not dumpable, the process's
 * {@code /proc} files and memory belong to root.
 *
 * <p>The flag is read back, since a call that returns 0 proves nothing. In the native executable
 * {@code main} runs on the process's first thread; in a JVM it does not, which is why only the
 * native executable is tested for it.
 */
final class Hardening {

    private static final int PR_GET_DUMPABLE = 3;

    private static final int PR_SET_DUMPABLE = 4;

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MemoryLayout ERRNO_LAYOUT = Linker.Option.captureStateLayout();

    private static final VarHandle ERRNO =
            ERRNO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    /**
     * The one shape of the call, registered for the native image in its {@code
     * reachability-metadata.json}; a second shape needs a second entry there.
     */
    private static final MethodHandle PRCTL =
            LINKER.downcallHandle(
                    LINKER.defaultLookup()
                            .find("prctl")
                            .orElseThrow(() -> new IllegalStateException("libc has no prctl")),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG),
                    Linker.Option.captureCallState("errno"));

    private Hardening() {}

    /** Makes the process not dumpable, or fails with what the kernel said. */
    static void notDumpable() {
        prctl("PR_SET_DUMPABLE", PR_SET_DUMPABLE, 0L);
        if (prctl("PR_GET_DUMPABLE", PR_GET_DUMPABLE, 0L) != 0) {
            throw new IllegalStateException("the process is still dumpable after PR_SET_DUMPABLE");
        }
    }

    private static int prctl(final String what, final int option, final long arg) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment errno = arena.allocate(ERRNO_LAYOUT);
            final int result = (int) PRCTL.invokeExact(errno, option, arg, 0L, 0L, 0L);
            if (result < 0) {
                throw new IllegalStateException(
                        what + " failed, errno " + (int) ERRNO.get(errno, 0L));
            }
            return result;
        } catch (final IllegalStateException ex) {
            throw ex;
        } catch (final Throwable ex) {
            throw new IllegalStateException(what + " could not be called", ex);
        }
    }
}
