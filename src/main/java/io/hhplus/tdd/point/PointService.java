package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class PointService {

    private final UserPointTable userPointTable;
    private final PointHistoryTable pointHistoryTable;
    
    // 사용자별 락을 관리하는 맵 (동시성 제어용)
    private final ConcurrentHashMap<Long, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    public PointService(UserPointTable userPointTable, PointHistoryTable pointHistoryTable) {
        this.userPointTable = userPointTable;
        this.pointHistoryTable = pointHistoryTable;
    }

    /**
     * 특정 유저의 포인트를 조회합니다.
     * @param userId 조회할 유저의 ID
     * @return 유저의 포인트 정보
     */
    public UserPoint getUserPoint(long userId) {
        return userPointTable.selectById(userId);
    }

    /**
     * 특정 유저의 포인트 충전/사용 내역을 조회합니다.
     * @param userId 조회할 유저의 ID
     * @return 포인트 거래 내역 리스트
     */
    public List<PointHistory> getPointHistories(long userId) {
        return pointHistoryTable.selectAllByUserId(userId);
    }

    /**
     * 특정 유저의 포인트를 충전합니다.
     * 동시성 제어를 위해 사용자별 락을 사용합니다.
     * @param userId 충전할 유저의 ID
     * @param amount 충전할 포인트 금액 (0보다 큰 값이어야 함)
     * @return 충전 후 유저의 포인트 정보
     */
    public UserPoint chargePoint(long userId, long amount) {
        // 사용자별 락 획득
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            // 현재 포인트 조회
            UserPoint currentPoint = userPointTable.selectById(userId);
            
            // 새로운 포인트 계산
            long newPoint = currentPoint.point() + amount;
            
            // 포인트 업데이트
            UserPoint updatedPoint = userPointTable.insertOrUpdate(userId, newPoint);
            
            // 포인트 충전 내역 기록
            pointHistoryTable.insert(userId, amount, TransactionType.CHARGE, System.currentTimeMillis());
            
            return updatedPoint;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 특정 유저의 포인트를 사용합니다.
     * 동시성 제어를 위해 사용자별 락을 사용합니다.
     * @param userId 사용할 유저의 ID
     * @param amount 사용할 포인트 금액 (0보다 큰 값이어야 함)
     * @return 사용 후 유저의 포인트 정보
     */
    public UserPoint usePoint(long userId, long amount) {
        // 사용자별 락 획득
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            // 현재 포인트 조회
            UserPoint currentPoint = userPointTable.selectById(userId);
            
            // 잔고 부족 검증 (비즈니스 로직 검증)
            if (currentPoint.point() < amount) {
                throw new IllegalStateException("포인트가 부족합니다. 현재 포인트: " + currentPoint.point() + ", 요청 금액: " + amount);
            }
            
            // 새로운 포인트 계산
            long newPoint = currentPoint.point() - amount;
            
            // 포인트 업데이트
            UserPoint updatedPoint = userPointTable.insertOrUpdate(userId, newPoint);
            
            // 포인트 사용 내역 기록
            pointHistoryTable.insert(userId, amount, TransactionType.USE, System.currentTimeMillis());
            
            return updatedPoint;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 사용자별 락을 반환합니다.
     * 동일한 사용자 ID에 대해서는 같은 락을 반환하여 동시성 제어를 수행합니다.
     */
    private ReentrantLock getUserLock(long userId) {
        return userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
    }
}
