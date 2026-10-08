package com.rentrewards.challenge.service;

import com.rentrewards.challenge.model.MemberAccount;
import com.rentrewards.challenge.model.PaymentEvent;
import com.rentrewards.challenge.model.PointsResult;
import com.rentrewards.challenge.model.ProcessingOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardsEngineTest {

    private RewardsEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RewardsEngine(new PointsCalculator(), new ProcessedEventStore());
    }

    @Test
    void awardsOnePointPerDollarByDefault() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("1500"), false, LocalDate.of(2026, 3, 1));

        PointsResult result = engine.processPayment(event, member);

        assertEquals(1500, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
        assertFalse(result.isSkippedAsDuplicate());
    }

    @Test
    void appliesLinkedAccountMultiplier() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("1500"), true, LocalDate.of(2026, 3, 1));

        PointsResult result = engine.processPayment(event, member);

        assertEquals(3000, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
    }

    @Test
    void appliesStreakBonusWhenEligible() {
        MemberAccount member = new MemberAccount("member-1", 6);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("2000"), true, LocalDate.of(2026, 3, 1));

        // base = 2000 * 2 (linked) = 4000; +10% streak bonus = 4400
        PointsResult result = engine.processPayment(event, member);

        assertEquals(4400, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
    }

    @Test
    void enforcesMonthlyPointsCap() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("150000"), false, LocalDate.of(2026, 3, 1));

        PointsResult result = engine.processPayment(event, member);

        assertEquals(100_000, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
    }

    @Test
    void reportsCappedWhenALaterEventCannotAwardMorePoints() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent first = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("150000"), false, LocalDate.of(2026, 3, 1));
        PaymentEvent second = new PaymentEvent("evt-2", "member-1",
                new BigDecimal("50"), false, LocalDate.of(2026, 3, 2));

        engine.processPayment(first, member);
        PointsResult result = engine.processPayment(second, member);

        assertEquals(0, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.CAPPED, result.getOutcome());
        assertFalse(result.isSkippedAsDuplicate());
        assertEquals(100_000, member.getPointsForMonth(YearMonth.of(2026, 3)));
    }

    @Test
    void doesNotDoubleAwardPointsWhenSameWebhookEventIsResent() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("1500"), false, LocalDate.of(2026, 3, 1));

        PointsResult first = engine.processPayment(event, member);
        PointsResult retry = engine.processPayment(event, member);

        assertEquals(1500, first.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, first.getOutcome());
        assertEquals(0, retry.getPointsAwarded());
        assertEquals(ProcessingOutcome.DUPLICATE, retry.getOutcome());
        assertTrue(retry.isSkippedAsDuplicate());
    }

    @Test
    void doesNotDoubleAwardPointsWhenAnOlderEventIsResentOutOfOrder() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent eventA = new PaymentEvent("evt-A", "member-1",
                new BigDecimal("1000"), false, LocalDate.of(2026, 3, 1));
        PaymentEvent eventB = new PaymentEvent("evt-B", "member-1",
                new BigDecimal("2000"), false, LocalDate.of(2026, 3, 5));

        // Processing order as it can realistically happen with webhook retries:
        // A arrives, then B arrives, then A is redelivered by the processor.
        engine.processPayment(eventA, member);
        engine.processPayment(eventB, member);
        PointsResult resentA = engine.processPayment(eventA, member);

        assertEquals(0, resentA.getPointsAwarded());
        assertEquals(ProcessingOutcome.DUPLICATE, resentA.getOutcome());
        assertTrue(resentA.isSkippedAsDuplicate());
        assertEquals(3000, member.getPointsForMonth(YearMonth.of(2026, 3)));
    }

    @RepeatedTest(8)
    void awardsPointsAtMostOnceWhenTheSameEventArrivesConcurrently() throws Exception {
        int workerCount = 16;
        RewardsEngine concurrentEngine = new RewardsEngine(
                new PointsCalculator(), new ProcessedEventStore());
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent(
                "evt-concurrent",
                "member-1",
                new BigDecimal("1500"),
                false,
                LocalDate.of(2026, 3, 1));
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<PointsResult>> futures = new ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return concurrentEngine.processPayment(event, member);
                }));
            }

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            long awardedResults = 0;
            for (Future<PointsResult> future : futures) {
                if (future.get(2, TimeUnit.SECONDS).getOutcome() == ProcessingOutcome.AWARDED) {
                    awardedResults++;
                }
            }

            assertEquals(1, awardedResults,
                    "at most one worker may award points for the same eventId");
            assertEquals(1500, member.getPointsForMonth(YearMonth.of(2026, 3)));
        } finally {
            executor.shutdownNow();
        }
    }

    @RepeatedTest(8)
    void neverExceedsMonthlyCapWhenDifferentEventsArriveConcurrently() throws Exception {
        MemberAccount member = new MemberAccount("member-1", 0);
        List<PaymentEvent> deliveries = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            deliveries.add(new PaymentEvent("evt-" + i, "member-1",
                    new BigDecimal("10000"), false, LocalDate.of(2026, 3, 1)));
        }

        List<PointsResult> results = processConcurrently(engine, member, deliveries);

        assertEquals(100_000, member.getPointsForMonth(YearMonth.of(2026, 3)));
        assertEquals(100_000, results.stream().mapToLong(PointsResult::getPointsAwarded).sum());
        assertEquals(10, countOutcome(results, ProcessingOutcome.AWARDED));
        assertEquals(6, countOutcome(results, ProcessingOutcome.CAPPED));
        assertEquals(0, countOutcome(results, ProcessingOutcome.DUPLICATE));
    }

    @RepeatedTest(8)
    void awardsEachEventOnceWhenRedeliveriesOfManyEventsInterleave() throws Exception {
        int eventCount = 5;
        int deliveriesPerEvent = 8;
        MemberAccount member = new MemberAccount("member-1", 0);
        List<PaymentEvent> events = new ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            events.add(new PaymentEvent("evt-" + i, "member-1",
                    new BigDecimal("100"), false, LocalDate.of(2026, 3, 1 + i)));
        }
        List<PaymentEvent> deliveries = new ArrayList<>();
        for (int round = 0; round < deliveriesPerEvent; round++) {
            deliveries.addAll(events);
        }

        List<PointsResult> results = processConcurrently(engine, member, deliveries);

        assertEquals(eventCount, countOutcome(results, ProcessingOutcome.AWARDED));
        assertEquals(eventCount * (deliveriesPerEvent - 1),
                countOutcome(results, ProcessingOutcome.DUPLICATE));
        assertEquals(eventCount * 100, member.getPointsForMonth(YearMonth.of(2026, 3)));
    }

    private static long countOutcome(List<PointsResult> results, ProcessingOutcome outcome) {
        return results.stream().filter(result -> result.getOutcome() == outcome).count();
    }

    private static List<PointsResult> processConcurrently(
            RewardsEngine engine, MemberAccount member, List<PaymentEvent> deliveries)
            throws Exception {
        int workerCount = deliveries.size();
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<PointsResult>> futures = new ArrayList<>();
            for (PaymentEvent delivery : deliveries) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return engine.processPayment(delivery, member);
                }));
            }

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            List<PointsResult> results = new ArrayList<>();
            for (Future<PointsResult> future : futures) {
                results.add(future.get(2, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
