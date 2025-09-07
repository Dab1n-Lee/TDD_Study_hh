package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("포인트 서비스 테스트")
class PointServiceTest {

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
    @DisplayName("사용자 포인트 조회 - 새로운 사용자는 정상적인 응답을 반환해야 한다")
    void getUserPoint_NewUser_ReturnsValidResponse() {
        // given: 새로운 사용자 ID
        long userId = 1L;

        // when: 포인트 조회
        UserPoint result = pointService.getUserPoint(userId);

        // then: 정상적인 응답인지 확인 (단순히 응답이 유효한지만 검증)
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(userId);
        assertThat(result.updateMillis()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("사용자 포인트 조회 - 기존 사용자는 저장된 포인트를 반환해야 한다")
    void getUserPoint_ExistingUser_ReturnsStoredPoint() {
        // given: 기존 사용자 포인트 설정
        long userId = 1L;
        long initialPoint = 1000L;
        userPointTable.insertOrUpdate(userId, initialPoint);

        // when: 포인트 조회
        UserPoint result = pointService.getUserPoint(userId);

        // then: 저장된 포인트 반환 확인
        assertThat(result.id()).isEqualTo(userId);
        assertThat(result.point()).isEqualTo(initialPoint);
    }

    @Test
    @DisplayName("포인트 내역 조회 - 새로운 사용자는 빈 리스트를 반환해야 한다")
    void getPointHistories_NewUser_ReturnsEmptyList() {
        // given: 새로운 사용자 ID
        long userId = 1L;

        // when: 포인트 내역 조회
        List<PointHistory> result = pointService.getPointHistories(userId);

        // then: 빈 리스트 반환 확인
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("포인트 내역 조회 - 거래 내역이 있는 사용자는 모든 내역을 반환해야 한다")
    void getPointHistories_UserWithHistory_ReturnsAllHistories() {
        // given: 사용자 포인트 충전 및 사용
        long userId = 1L;
        pointService.chargePoint(userId, 1000L);
        pointService.usePoint(userId, 300L);

        // when: 포인트 내역 조회
        List<PointHistory> result = pointService.getPointHistories(userId);

        // then: 모든 거래 내역 반환 확인
        assertThat(result).hasSize(2);
        assertThat(result.get(0).userId()).isEqualTo(userId);
        assertThat(result.get(0).amount()).isEqualTo(1000L);
        assertThat(result.get(0).type()).isEqualTo(TransactionType.CHARGE);
        assertThat(result.get(1).userId()).isEqualTo(userId);
        assertThat(result.get(1).amount()).isEqualTo(300L);
        assertThat(result.get(1).type()).isEqualTo(TransactionType.USE);
    }

    @Test
    @DisplayName("포인트 충전 - 정상적인 충전 요청은 성공해야 한다")
    void chargePoint_ValidAmount_Success() {
        // given: 충전할 금액
        long userId = 1L;
        long chargeAmount = 1000L;

        // when: 포인트 충전
        UserPoint result = pointService.chargePoint(userId, chargeAmount);

        // then: 충전 성공 확인
        assertThat(result.id()).isEqualTo(userId);
        assertThat(result.point()).isEqualTo(chargeAmount);
        assertThat(result.updateMillis()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("포인트 충전 - 기존 포인트에 추가로 충전할 수 있어야 한다")
    void chargePoint_ExistingPoint_AddsToExisting() {
        // given: 기존 포인트 설정
        long userId = 1L;
        long initialPoint = 500L;
        userPointTable.insertOrUpdate(userId, initialPoint);

        // when: 추가 충전
        long chargeAmount = 1000L;
        UserPoint result = pointService.chargePoint(userId, chargeAmount);

        // then: 기존 포인트에 추가된 금액 확인
        assertThat(result.point()).isEqualTo(initialPoint + chargeAmount);
    }



    @Test
    @DisplayName("포인트 사용 - 충분한 잔고가 있을 때 성공해야 한다")
    void usePoint_SufficientBalance_Success() {
        // given: 충분한 포인트 설정
        long userId = 1L;
        long initialPoint = 1000L;
        userPointTable.insertOrUpdate(userId, initialPoint);

        // when: 포인트 사용
        long useAmount = 300L;
        UserPoint result = pointService.usePoint(userId, useAmount);

        // then: 사용 성공 확인
        assertThat(result.id()).isEqualTo(userId);
        assertThat(result.point()).isEqualTo(initialPoint - useAmount);
    }

    @Test
    @DisplayName("포인트 사용 - 잔고가 부족할 때 예외가 발생해야 한다")
    void usePoint_InsufficientBalance_ThrowsException() {
        // given: 부족한 포인트 설정
        long userId = 1L;
        long initialPoint = 100L;
        userPointTable.insertOrUpdate(userId, initialPoint);

        // when & then: 예외 발생 확인
        long useAmount = 500L;
        assertThatThrownBy(() -> pointService.usePoint(userId, useAmount))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("포인트가 부족합니다. 현재 포인트: 100, 요청 금액: 500");
    }



    @Test
    @DisplayName("포인트 충전 및 사용 - 거래 내역이 올바르게 기록되어야 한다")
    void chargeAndUsePoint_TransactionHistory_RecordedCorrectly() {
        // given: 사용자 ID
        long userId = 1L;

        // when: 포인트 충전 및 사용
        pointService.chargePoint(userId, 1000L);
        pointService.usePoint(userId, 300L);

        // then: 거래 내역 확인
        List<PointHistory> histories = pointService.getPointHistories(userId);
        assertThat(histories).hasSize(2);
        
        // 충전 내역 확인
        PointHistory chargeHistory = histories.get(0);
        assertThat(chargeHistory.userId()).isEqualTo(userId);
        assertThat(chargeHistory.amount()).isEqualTo(1000L);
        assertThat(chargeHistory.type()).isEqualTo(TransactionType.CHARGE);
        
        // 사용 내역 확인
        PointHistory useHistory = histories.get(1);
        assertThat(useHistory.userId()).isEqualTo(userId);
        assertThat(useHistory.amount()).isEqualTo(300L);
        assertThat(useHistory.type()).isEqualTo(TransactionType.USE);
    }
}
