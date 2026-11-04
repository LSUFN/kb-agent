package com.example.kbagent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** §4.6：每条识别规则的正例和反例。 */
class SensitiveDataMaskerTest {

    /** 校验码正确的 18 位身份证号（GB 11643 的标准示例）。 */
    private static final String ID_CARD = "11010519491231002X";
    /** 通过 Luhn 校验的 19 位银行卡号。 */
    private static final String BANK_CARD = "6222021234567890128";

    @Test
    void masksPhoneNumbers() {
        assertThat(SensitiveDataMasker.mask("联系电话13812345678，备用 15900001111"))
                .isEqualTo("联系电话138****5678，备用 159****1111");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12812345678", "138123456789", "1381234567", "订单号 2138123456789"})
    void leavesNumbersThatAreNotPhoneNumbers(String text) {
        assertThat(SensitiveDataMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void masksValidIdCardNumbersKeepingRegionAndLastFourDigits() {
        assertThat(SensitiveDataMasker.mask("身份证 " + ID_CARD + " 已登记"))
                .isEqualTo("身份证 110105********002X 已登记");
        assertThat(SensitiveDataMasker.mask("小写 11010519491231002x")).isEqualTo("小写 110105********002x");
    }

    @Test
    void leavesEighteenDigitNumbersWithAWrongIdCheckCode() {
        // 校验码错误、也不能通过 Luhn 校验的 18 位数字（例如订单号）保持原样
        String orderNo = "110105194912310021";
        assertThat(SensitiveDataMasker.isValidIdCard(orderNo)).isFalse();
        assertThat(SensitiveDataMasker.passesLuhn(orderNo)).isFalse();
        assertThat(SensitiveDataMasker.mask("订单 " + orderNo)).isEqualTo("订单 " + orderNo);
    }

    @Test
    void masksBankCardNumbersThatPassLuhn() {
        assertThat(SensitiveDataMasker.mask("卡号 " + BANK_CARD)).isEqualTo("卡号 ***************0128");
        assertThat(SensitiveDataMasker.mask("Visa 4111111111111111")).isEqualTo("Visa ************1111");
    }

    @Test
    void leavesLongNumbersThatFailLuhn() {
        // Luhn 校验用来减少误判：普通的长数字（流水号、时间戳）不脱敏
        assertThat(SensitiveDataMasker.passesLuhn("6222021234567890123")).isFalse();
        assertThat(SensitiveDataMasker.mask("流水号 6222021234567890123")).isEqualTo("流水号 6222021234567890123");
    }

    @Test
    void masksEmailAddresses() {
        assertThat(SensitiveDataMasker.mask("发送到 zhang.san@example.com.cn 即可"))
                .isEqualTo("发送到 z***@example.com.cn 即可");
    }

    @Test
    void masksApiKeysBearerTokensAndJwts() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJlLXNpZ25hdHVyZQ";
        assertThat(SensitiveDataMasker.mask("key=sk-abcdefghijklmnop1234")).isEqualTo("key=sk-****");
        assertThat(SensitiveDataMasker.mask("Authorization: Bearer abc.def-123_456"))
                .isEqualTo("Authorization: Bearer ****");
        assertThat(SensitiveDataMasker.mask("token " + jwt)).isEqualTo("token eyJ****");
    }

    @Test
    void leavesShortOrEmbeddedKeyLikeTextAlone() {
        assertThat(SensitiveDataMasker.mask("task-sk-12")).isEqualTo("task-sk-12");
        assertThat(SensitiveDataMasker.mask("desk-abcdefghijklmnopqrst")).isEqualTo("desk-abcdefghijklmnopqrst");
    }

    @Test
    void masksEverythingInOneText() {
        String masked = SensitiveDataMasker.mask(
                "我是张三，手机 13812345678，身份证 " + ID_CARD + "，卡号 " + BANK_CARD + "，邮箱 zs@corp.cn");

        assertThat(masked)
                .doesNotContain("13812345678", ID_CARD, BANK_CARD, "zs@corp.cn")
                .contains("138****5678", "110105********002X", "0128", "z***@corp.cn");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(SensitiveDataMasker.mask(null)).isNull();
        assertThat(SensitiveDataMasker.mask("")).isEmpty();
    }
}
