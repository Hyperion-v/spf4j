/*
 * Copyright (c) 2001, Zoltan Farkas All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.spf4j.stackmonitor;

import com.google.common.base.Predicate;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import gnu.trove.set.hash.THashSet;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Set;
import javax.annotation.Nonnull;
import org.spf4j.base.Throwables;

/**
 * This is a high performance sampling collector.
 * The goal is for the sampling overhead to be minimal.
 * This is better than the SimpleStackCollector in 2 ways:
 * 1) No HashMap is created during sampling. Resulting in less garbage generated by sampling.
 * 2) Stack trace for the sampling Thread is not created at all, saving some time and creating less garbage.
 *
 * @author zoly
 */
public final class FastStackCollector extends AbstractStackCollector {

    private static final MethodHandle GET_THREADS;
    private static final MethodHandle DUMP_THREADS;

    private static final String[] IGNORED_THREADS = {
            "Finalizer",
            "Signal Dispatcher",
            "Reference Handler",
            "Attach Listener",
            "VM JFR Buffer Thread"
    };

    private final Predicate<Thread> threadFilter;

    public FastStackCollector(final boolean collectForMain, final String ... xtraIgnoredThreads) {
        this(createNameBasedFilter(collectForMain, xtraIgnoredThreads));
    }

    public static Predicate<Thread> createNameBasedFilter(final boolean collectForMain,
            final String[] xtraIgnoredThreads) {
        final Set<String> ignoredThreads = new THashSet<>(Arrays.asList(IGNORED_THREADS));
        if (!collectForMain) {
            ignoredThreads.add("main");
        }
        ignoredThreads.addAll(Arrays.asList(xtraIgnoredThreads));

       return new ThreadNamesPredicate(ignoredThreads);
    }

    public FastStackCollector(final Predicate<Thread> threadFilter) {
        this.threadFilter = threadFilter;
    }


    static {
        final java.lang.reflect.Method getThreads;
        final java.lang.reflect.Method dumpThreads;
        try {

            getThreads = Thread.class.getDeclaredMethod("getThreads");
            dumpThreads = Thread.class.getDeclaredMethod("dumpThreads", Thread[].class);
        } catch (SecurityException ex) {
            throw new RuntimeException(ex);
        } catch (NoSuchMethodException ex) {
            throw new RuntimeException(ex);
        }
        AccessController.doPrivileged((PrivilegedAction) () -> {
          getThreads.setAccessible(true);
          dumpThreads.setAccessible(true);
          return null; // nothing to return
        });
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            GET_THREADS = lookup.unreflect(getThreads);
            DUMP_THREADS = lookup.unreflect(dumpThreads);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }

    }

    public static Thread[] getThreads() {
        try {
            return (Thread[]) GET_THREADS.invokeExact();
        } catch (RuntimeException | Error ex) {
          throw ex;
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
    }


    public static  StackTraceElement[][] getStackTraces(final Thread... threads) {
        StackTraceElement[][] stackDump;
        try {
            stackDump = (StackTraceElement[][]) DUMP_THREADS.invokeExact(threads);
        } catch (RuntimeException | Error ex) {
          throw ex;
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
        return stackDump;
    }

    @SuppressFBWarnings("NOS_NON_OWNED_SYNCHRONIZATION") // jdk printstreams are sync I don't want interleaving.
    public static void dumpToPrintStream(final PrintStream stream) {
      synchronized (stream) {
        Thread[] threads = getThreads();
        StackTraceElement[][] stackTraces = getStackTraces(threads);
        for (int i = 0; i < threads.length; i++) {
          StackTraceElement[] stackTrace = stackTraces[i];
          if (stackTrace != null && stackTrace.length > 0) {
            Thread thread = threads[i];
            stream.println("Thread " + thread.getName());
            try {
              Throwables.writeTo(stackTrace, stream, Throwables.Detail.SHORT_PACKAGE);
            } catch (IOException ex) {
              throw new RuntimeException(ex);
            }
          }
        }
      }
    }



    private Thread[] requestFor = new Thread[] {};


    @Override
    @SuppressFBWarnings("EXS_EXCEPTION_SOFTENING_NO_CHECKED")
    public void sample(final Thread ignore) {
            Thread[] threads = getThreads();
            final int nrThreads = threads.length;
            if (requestFor.length < nrThreads) {
                requestFor = new Thread[nrThreads - 1];
            }
            int j = 0;
            for (int i = 0; i < nrThreads; i++) {
                Thread th = threads[i];
                if (ignore != th && !threadFilter.apply(th)) { // not interested in these traces
                    requestFor[j++] = th;
                }
            }
            Arrays.fill(requestFor, j, requestFor.length, null);
            StackTraceElement[][] stackDump = getStackTraces(requestFor);
            for (int i = 0; i < j; i++) {
                StackTraceElement[] stackTrace = stackDump[i];
                if (stackTrace != null && stackTrace.length > 0) {
                    addSample(stackTrace);
                } else {
                    addSample(new StackTraceElement[] {
                            new StackTraceElement("Thread", requestFor[i].getName(), "", 0)
                        });
                }
            }
    }

    public static final class ThreadNamesPredicate implements Predicate<Thread> {

        private final Set<String> ignoredThreadNames;

        public ThreadNamesPredicate(final Set<String> ignoredThreadNames) {
            this.ignoredThreadNames = ignoredThreadNames;
        }

        @Override
        public boolean apply(@Nonnull final Thread input) {
            return ignoredThreadNames.contains(input.getName());
        }
    }

}