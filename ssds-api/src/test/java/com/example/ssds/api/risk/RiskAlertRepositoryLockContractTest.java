package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ssds.infra.repository.RiskAlertRepository;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;

/**
 * 防止鎖被不小心拿掉：Writer 要改寫的那一列、與使用者確認／忽略的那一列，都必須是 PESSIMISTIC_WRITE，
 * 否則偵測排程會用舊的 OPEN 狀態把剛處理完的示警整列寫回。
 * 這只守住「有宣告鎖」；實際的鎖行為仍需 PostgreSQL 整合測試驗證。
 */
class RiskAlertRepositoryLockContractTest {

    @Test
    void dedupWindowQueryLocksTheRowItReturns() throws Exception {
        Method method = RiskAlertRepository.class.getMethod(
                "findWithinDedupWindow", Long.class, String.class, Instant.class, Pageable.class);
        assertThat(method.getAnnotation(Lock.class)).isNotNull();
        assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void handlerLookupLocksTheRow() throws Exception {
        Method method = RiskAlertRepository.class.getMethod("findForUpdate", Long.class);
        assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}
