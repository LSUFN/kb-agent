package com.example.kbagent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import com.example.kbagent.user.domain.Region;
import org.junit.jupiter.api.Test;

/** §4.6：工具参数按字段分级脱敏。 */
class ArgumentMaskerTest {

    record Args(LocalDate from, Region region, Integer limit, @Sensitive String query,
                @Sensitive(Sensitive.Strategy.HASH) String customer, @Sensitive(Sensitive.Strategy.DROP) String secret,
                String note, Boolean flag) {
    }

    @Test
    void appliesTheStrategyOfEachField() {
        Map<String, Object> masked = ArgumentMasker.mask(new Args(LocalDate.of(2026, 10, 1), Region.EAST, 20,
                "我的手机 13812345678 报销进度", "张三有限公司", "do-not-log", "联系 zs@corp.cn", true));

        assertThat(masked).containsExactly(
                Map.entry("from", "2026-10-01"),
                Map.entry("region", "EAST"),
                Map.entry("limit", 20),
                Map.entry("query", "我的手机 138****5678 报销进度"),
                Map.entry("customer", masked.get("customer")),
                Map.entry("note", "联系 z***@corp.cn"),
                Map.entry("flag", true));
        assertThat((String) masked.get("customer")).startsWith("sha256:").hasSize("sha256:".length() + 12)
                .doesNotContain("张三");
        assertThat(masked).doesNotContainKey("secret");
    }

    @Test
    void nullFieldsAreOmitted() {
        assertThat(ArgumentMasker.mask(new Args(null, null, null, "q", null, null, null, null)))
                .containsOnlyKeys("query");
        assertThat(ArgumentMasker.mask(null)).isEmpty();
    }
}
