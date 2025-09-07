package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("포인트 서비스 동시성 테스트 코드")
class PointServiceConcurrencyTest {

    private PointService pointService;
    private UserPointTable userPointTable;
    private PointHistoryTable pointHistoryTable;

    @BeforeEach
    void setUp() {
        userPointTable = new UserPointTable();
        pointHistoryTable = new PointHistoryTable();
        pointService = new PointService(userPointTable, pointHistoryTable);
    }

    @Test
    @DisplayName("동시 포인트 충전 - Race Condition이 발생하지 않아야 한다")
    void concurrentCharge_NoRaceCondition() throws InterruptedException {
        // given: 초기 포인트 설정
        long userId = 1L;
        long initialPoint = 1000L;
        userPointTable.insertOrUpdate(userId, initialPoint);
        
        int threadCount = 10;
        long chargeAmount = 100L;
        long expectedFinalPoint = initialPoint + (threadCount * chargeAmount);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<CompletableFuture<UserPoint>> futures = new ArrayList<>();

        // when: 동시에 포인트 충전
        for (int i = 0; i < threadCount; i++) {
            CompletableFuture<UserPoint> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return pointService.chargePoint(userId, chargeAmount);
                } finally {
                    latch.countDown();
                }
            }, executor);
            futures.add(future);
        }

        // 모든 스레드가 완료될 때까지 대기
        latch.await();
        executor.shutdown();

        // then: 최종 포인트가 예상값과 일치하는지 확인
        UserPoint finalPoint = pointService.getUserPoint(userId);
        assertThat(finalPoint.point()).isEqualTo(expectedFinalPoint);
        
        // 모든 충전이 성공했는지 확인
        for (CompletableFuture<UserPoint> future : futures) {
            assertThat(future.isDone()).isTrue();
        }
    }

    @Test
    @DisplayName("동시 포인트 사용 - 잔고 부족 시 적절히 처리되어야 한다")
    void concurrentUse_InsufficientBalance() throws InterruptedException {
        // given: 제한된 초기 포인트 설정
        long userId = 1L;
        long initialPoint = 500L;
        userPointTable.insertOrUpdate(userId, initialPoint);
        
        int threadCount = 10;
        long useAmount = 100L;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // when: 동시에 포인트 사용
        for (int i = 0; i < threadCount; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    pointService.usePoint(userId, useAmount);
                    successCount.incrementAndGet();
                } catch (IllegalStateException e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }, executor);
        }

        // 모든 스레드가 완료될 때까지 대기
        latch.await();
        executor.shutdown();

        // then: 성공한 횟수와 실패한 횟수 확인
        assertThat(successCount.get()).isEqualTo(5); // 500원 / 100원 = 5번 성공
        assertThat(failureCount.get()).isEqualTo(5); // 나머지 5번은 실패
        
        // 최종 포인트가 0이 되어야 함
        UserPoint finalPoint = pointService.getUserPoint(userId);
        assertThat(finalPoint.point()).isEqualTo(0L);
    }

    @Test
    @DisplayName("동시 포인트 충전 및 사용 - 데이터 일관성이 보장되어야 한다")
    void concurrentChargeAndUse_DataConsistency() throws InterruptedException {
        // given: 초기 포인트 설정
        long userId = 1L;
        long initialPoint = 1000L;
        userPointTable.insertOrUpdate(userId, initialPoint);
        
        int chargeThreadCount = 5;
        int useThreadCount = 3;
        long chargeAmount = 200L;
        long useAmount = 300L;
        
        // 예상 최종 포인트: 1000 + (5 * 200) - (3 * 300) = 1000 + 1000 - 900 = 1100
        long expectedFinalPoint = initialPoint + (chargeThreadCount * chargeAmount) - (useThreadCount * useAmount);
        
        ExecutorService executor = Executors.newFixedThreadPool(chargeThreadCount + useThreadCount);
        CountDownLatch latch = new CountDownLatch(chargeThreadCount + useThreadCount);
        AtomicInteger chargeSuccessCount = new AtomicInteger(0);
        AtomicInteger useSuccessCount = new AtomicInteger(0);
        AtomicInteger useFailureCount = new AtomicInteger(0);

        // when: 동시에 포인트 충전 및 사용
        // 충전 스레드들
        for (int i = 0; i < chargeThreadCount; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    pointService.chargePoint(userId, chargeAmount);
                    chargeSuccessCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }, executor);
        }

        // 사용 스레드들
        for (int i = 0; i < useThreadCount; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    pointService.usePoint(userId, useAmount);
                    useSuccessCount.incrementAndGet();
                } catch (IllegalStateException e) {
                    useFailureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }, executor);
        }

        // 모든 스레드가 완료될 때까지 대기
        latch.await();
        executor.shutdown();

        // then: 결과 검증
        assertThat(chargeSuccessCount.get()).isEqualTo(chargeThreadCount);
        assertThat(useSuccessCount.get() + useFailureCount.get()).isEqualTo(useThreadCount);
        
        // 최종 포인트 확인
        UserPoint finalPoint = pointService.getUserPoint(userId);
        assertThat(finalPoint.point()).isEqualTo(expectedFinalPoint);
        
        // 거래 내역 확인
        List<PointHistory> histories = pointService.getPointHistories(userId);
        assertThat(histories).hasSize(chargeThreadCount + useSuccessCount.get());
    }

    @Test
    @DisplayName("다른 사용자 동시 처리 - 서로 간섭하지 않아야 한다")
    void concurrentDifferentUsers_NoInterference() throws InterruptedException {
        // given: 두 사용자의 초기 포인트 설정
        long user1Id = 1L;
        long user2Id = 2L;
        long initialPoint = 1000L;
        userPointTable.insertOrUpdate(user1Id, initialPoint);
        userPointTable.insertOrUpdate(user2Id, initialPoint);
        
        int threadCount = 5;
        long chargeAmount = 100L;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount * 2);
        CountDownLatch latch = new CountDownLatch(threadCount * 2);

        // when: 두 사용자에 대해 동시에 포인트 충전
        // 사용자 1 충전
        for (int i = 0; i < threadCount; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    pointService.chargePoint(user1Id, chargeAmount);
                } finally {
                    latch.countDown();
                }
            }, executor);
        }

        // 사용자 2 충전
        for (int i = 0; i < threadCount; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    pointService.chargePoint(user2Id, chargeAmount);
                } finally {
                    latch.countDown();
                }
            }, executor);
        }

        // 모든 스레드가 완료될 때까지 대기
        latch.await();
        executor.shutdown();

        // then: 각 사용자의 포인트가 독립적으로 계산되었는지 확인
        UserPoint user1FinalPoint = pointService.getUserPoint(user1Id);
        UserPoint user2FinalPoint = pointService.getUserPoint(user2Id);
        
        long expectedPoint = initialPoint + (threadCount * chargeAmount);
        assertThat(user1FinalPoint.point()).isEqualTo(expectedPoint);
        assertThat(user2FinalPoint.point()).isEqualTo(expectedPoint);
        
        // 각 사용자의 거래 내역이 독립적인지 확인
        List<PointHistory> user1Histories = pointService.getPointHistories(user1Id);
        List<PointHistory> user2Histories = pointService.getPointHistories(user2Id);
        
        assertThat(user1Histories).hasSize(threadCount);
        assertThat(user2Histories).hasSize(threadCount);
        
        // 사용자 1의 내역에는 사용자 2의 거래가 없어야 함
        assertThat(user1Histories).allMatch(history -> history.userId() == user1Id);
        assertThat(user2Histories).allMatch(history -> history.userId() == user2Id);
    }

    @Test
    @DisplayName("동시 포인트 조회 - 읽기 작업은 동시에 수행되어야 한다")
    void concurrentRead_ShouldNotBlock() throws InterruptedException {
        // given: 초기 포인트 설정
        long userId = 1L;
        long initialPoint = 1000L;
        userPointTable.insertOrUpdate(userId, initialPoint);
        
        int threadCount = 20;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<CompletableFuture<UserPoint>> futures = new ArrayList<>();

        // when: 동시에 포인트 조회
        for (int i = 0; i < threadCount; i++) {
            CompletableFuture<UserPoint> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return pointService.getUserPoint(userId);
                } finally {
                    latch.countDown();
                }
            }, executor);
            futures.add(future);
        }

        // 모든 스레드가 완료될 때까지 대기
        latch.await();
        executor.shutdown();

        // then: 모든 조회 결과가 일치하는지 확인
        for (CompletableFuture<UserPoint> future : futures) {
            UserPoint result = future.join();
            assertThat(result.point()).isEqualTo(initialPoint);
            assertThat(result.id()).isEqualTo(userId);
        }
    }
}
