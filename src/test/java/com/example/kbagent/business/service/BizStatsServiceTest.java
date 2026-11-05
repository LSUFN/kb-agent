package com.example.kbagent.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.time.ZoneId;

import com.example.kbagent.business.BusinessProperties;
import com.example.kbagent.business.domain.AnomalyGroupBy;
import com.example.kbagent.business.domain.OrderGroupBy;
import com.example.kbagent.business.repository.BizStatsRepository;
import com.example.kbagent.business.service.BizStats.AnomalyQuery;
import com.example.kbagent.business.service.BizStats.OrderQuery;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.support.TestActors;
import com.example.kbagent.user.domain.Region;
import org.junit.jupiter.api.Test;

/** 业务 Service 的二次校验（ADR-07）：即使绕过工具层直接调用，也拿不到范围之外的数据。 */
class BizStatsServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);

    private final BizStatsRepository repository = mock(BizStatsRepository.class);
    private final BizStatsService service = new BizStatsService(repository,
            new BusinessProperties(ZoneId.of("Asia/Shanghai"), 366, 100));

    @Test
    void rangeRules() {
        assertThat(service.rangeViolation(FROM, FROM)).isNull();
        assertThat(service.rangeViolation(FROM, FROM.plusDays(365))).isNull();
        assertThat(service.rangeViolation(FROM, FROM.plusDays(366))).isEqualTo("时间跨度不能超过 366 天");
        assertThat(service.rangeViolation(FROM, FROM.minusDays(1))).isEqualTo("from 不能晚于 to");
        assertThat(service.rangeViolation(null, FROM)).contains("必须提供");
    }

    @Test
    void scopeIsCheckedAgainInsideTheService() {
        OrderQuery north = new OrderQuery(FROM, FROM.plusDays(6), Region.NORTH, OrderGroupBy.REGION);
        OrderQuery any = new OrderQuery(FROM, FROM.plusDays(6), null, OrderGroupBy.REGION);

        assertThatThrownBy(() -> service.orderStats(TestActors.ANALYST, north))
                .isInstanceOf(DataScope.ScopeDeniedException.class);
        assertThatThrownBy(() -> service.orderStats(TestActors.USER, any))
                .isInstanceOf(DataScope.ScopeDeniedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void invalidRangeAndSeverityAreRejectedBeforeQuerying() {
        assertThatThrownBy(() -> service.anomalyStats(TestActors.ADMIN,
                new AnomalyQuery(FROM, FROM.plusDays(400), null, null, null, AnomalyGroupBy.EVENT_TYPE)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("366");
        assertThatThrownBy(() -> service.anomalyStats(TestActors.ADMIN,
                new AnomalyQuery(FROM, FROM, null, 5, null, AnomalyGroupBy.EVENT_TYPE)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("minSeverity");
        verifyNoInteractions(repository);
    }
}
