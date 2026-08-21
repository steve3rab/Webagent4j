package io.webagent4j.browsercrawler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ClaimGateTest {

    @Test
    void firstClaimOfAUrlSucceeds() {
        ClaimGate gate = new ClaimGate(10);
        assertThat(gate.tryClaim(URI.create("https://example.com/a")))
                .isEqualTo(ClaimGate.Outcome.CLAIMED);
    }

    @Test
    void secondClaimOfTheSameUrlIsRejectedAsAlreadyClaimed() {
        ClaimGate gate = new ClaimGate(10);
        URI url = URI.create("https://example.com/a");
        gate.tryClaim(url);
        assertThat(gate.tryClaim(url)).isEqualTo(ClaimGate.Outcome.ALREADY_CLAIMED);
    }

    @Test
    void claimBeyondMaxPagesIsRejectedAsLimitReached() {
        ClaimGate gate = new ClaimGate(1);
        gate.tryClaim(URI.create("https://example.com/a"));
        assertThat(gate.tryClaim(URI.create("https://example.com/b")))
                .isEqualTo(ClaimGate.Outcome.LIMIT_REACHED);
    }

    @Test
    void claimedCountNeverExceedsMaxPagesUnderConcurrentDistinctUrls() throws InterruptedException {
        int maxPages = 10;
        int attempts = 20;
        ClaimGate gate = new ClaimGate(maxPages);
        List<URI> urls =
                IntStream.range(0, attempts)
                        .mapToObj(i -> URI.create("https://example.com/page-" + i))
                        .collect(Collectors.toList());

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        try {
            List<Runnable> tasks =
                    urls.stream()
                            .<Runnable>map(
                                    url ->
                                            () -> {
                                                await(startGate);
                                                if (gate.tryClaim(url)
                                                        == ClaimGate.Outcome.CLAIMED) {
                                                    claimed.incrementAndGet();
                                                }
                                            })
                            .toList();
            tasks.forEach(pool::execute);
            startGate.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(claimed.get()).isEqualTo(maxPages);
        assertThat(gate.claimedCount()).isEqualTo(maxPages);
    }

    @Test
    void concurrentClaimsOfTheSameUrlOnlyOneWins() throws InterruptedException {
        ClaimGate gate = new ClaimGate(100);
        URI url = URI.create("https://example.com/same");
        int attempts = 50;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        try {
            for (int i = 0; i < attempts; i++) {
                pool.execute(
                        () -> {
                            await(startGate);
                            if (gate.tryClaim(url) == ClaimGate.Outcome.CLAIMED) {
                                claimed.incrementAndGet();
                            }
                        });
            }
            startGate.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(claimed.get()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
