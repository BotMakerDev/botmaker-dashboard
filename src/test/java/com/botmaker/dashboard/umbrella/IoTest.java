package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-module work runs at once and answers in order; one repository's fetches never overlap. */
class IoTest {

    @Test
    void parallelWorkRunsTogetherAndAnswersInTheItemsOrder() throws Exception {
        CountDownLatch together = new CountDownLatch(3);
        List<String> answers = Io.parallel(List.of("a", "b", "c"), item -> {
            together.countDown();
            try {
                // Each waits for the other two: only a parallel run gets past this.
                assertTrue(together.await(5, TimeUnit.SECONDS), "the items did not run at once");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return item.toUpperCase();
        });

        assertEquals(List.of("A", "B", "C"), answers);
    }

    @Test
    void oneRepositorysWorkNeverOverlapsAndAnotherRepositoryDoesNotWait() {
        Path sdk = Path.of("/u/botmaker-sdk");
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();

        Io.parallel(List.of(1, 2, 3, 4), i -> Io.inRepository(sdk, () -> {
            most.accumulateAndGet(inside.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inside.decrementAndGet();
            return i;
        }));
        assertEquals(1, most.get(), "two fetches into one repository at once");

        // Held for the SDK, taken at once for the CLI.
        CountDownLatch cliRan = new CountDownLatch(1);
        Io.inRepository(sdk, () -> {
            Io.async(() -> Io.inRepository(Path.of("/u/botmaker-cli"), () -> {
                cliRan.countDown();
                return null;
            }));
            try {
                assertTrue(cliRan.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }
}
