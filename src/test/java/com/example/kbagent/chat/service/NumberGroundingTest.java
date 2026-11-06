package com.example.kbagent.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 数字核对：回答中的数字必须能在本轮的查询结果中找到出处。 */
class NumberGroundingTest {

    private static final String ORDER_RESULT = """
            {"from":"2026-10-07","to":"2026-11-05","regions":["EAST"],"groupBy":"CATEGORY",
             "rows":[{"key":"ELECTRONICS","orderCount":71,"paidOrderCount":60,"paidAmount":91783.14,"cancelledCount":6}],
             "total":{"orderCount":299,"paidOrderCount":237,"paidAmount":350594.19,"cancelledCount":30}}""";

    @Test
    void numbersInventedWithoutAnyToolCallAreFlagged() {
        NumberGrounding.Result result = NumberGrounding.check(
                "根据查询结果，华北地区上个月（2026-10-01至2026-10-31）共发生异常事件**127起**。", "那华北上个月有多少异常事件？",
                List.of());

        assertThat(result.unverified()).containsExactly("127");
        assertThat(result.noEvidence()).isTrue();
        assertThat(NumberGrounding.note(result)).contains("本轮没有调用任何查询工具").contains("127");
    }

    @Test
    void numbersFromToolResultsAreVerifiedInAnyCommonFormat() {
        String answer = """
                华东最近 30 天共 299 单，成交 237 单，成交金额 350,594.19 元（约 35.06 万元，约 35 万元），
                其中数码类 71 单、91,783.14 元，取消 6 单。""";

        NumberGrounding.Result result = NumberGrounding.check(answer, "华东最近 30 天各品类的订单情况",
                List.of(ORDER_RESULT, "共 1 组，订单 299 单，成交金额 350594.19 元"));

        assertThat(result.unverified()).isEmpty();
        assertThat(NumberGrounding.note(result)).isNull();
    }

    @Test
    void sharesDerivableFromTheResultsPassButOtherInventedNumbersAreFlagged() {
        // 26.2% = 91783.14 / 350594.19；12.5% 的“增长”和 88 单都无从得出
        NumberGrounding.Result result = NumberGrounding.check(
                "成交金额 350,594.19 元，其中数码类占 26.2%，比上月增长 12.5%，共 299 单，另有 88 单待发货。", "订单情况",
                List.of(ORDER_RESULT));

        assertThat(result.unverified()).containsExactly("12.5", "88");
        assertThat(result.noEvidence()).isFalse();
        assertThat(NumberGrounding.note(result)).contains("可能是模型计算或推测得出的").contains("12.5、88");
    }

    /** 实测：工具返回平均处理 378.06 分钟，模型写成“约 6.3 小时”“约 6 小时 18 分钟”，曾被误标为未核实。 */
    @ParameterizedTest
    @ValueSource(strings = {"平均处理时长 378.06 分钟（约 6.3 小时）。", "平均约 6 小时 18 分钟。", "平均约 6 小时。",
            "平均 6.30 小时。"})
    void hoursConvertedFromMinutesInTheResultsAreVerified(String answer) {
        String anomalyResult = """
                {"rows":[{"key":"EAST","eventCount":76,"unresolvedCount":11,"avgResolveMinutes":378.06}]}""";

        NumberGrounding.Result result = NumberGrounding.check(answer, "异常事件处理多久", List.of(anomalyResult));

        assertThat(result.unverified()).isEmpty();
    }

    @Test
    void hoursAreOnlyComparedWithMinuteValues() {
        // 9 小时 = 540 分钟，与 378.06 分钟对不上；334 单换算成分钟（约 5.6 小时）也不算出处
        NumberGrounding.Result result = NumberGrounding.check("平均约 9 小时，另有约 5.6 小时。", "",
                List.of("{\"orderCount\":334,\"avgResolveMinutes\":378.06}"));

        assertThat(result.unverified()).containsExactly("9", "5.6");
    }

    @Test
    void sharesAboveOneHundredPercentOrWithoutPercentSignAreNotDerived() {
        NumberGrounding.Result result = NumberGrounding.check("占比 123.4%，另一个值 26.2。", "", List.of(ORDER_RESULT));

        assertThat(result.unverified()).containsExactly("123.4", "26.2");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            日期和月份不算数据：2026-10-01、2026年10月、10月5日、2026/11/05、10月份
            引用标记不算数据：报销须在规定期限内提交[1][2]，详见【3】
            序号不算数据：第 3 名，第十三条
            标识符不算数据：调用 c1、c2，订单号 ORD0000123
            """)
    void nonDataNumbersAreIgnored(String answer) {
        assertThat(NumberGrounding.check(answer, "问题", List.of()).unverified()).isEmpty();
    }

    @Test
    void numberedListMarkersAreIgnored() {
        String answer = """
                1. 报销须在出差结束后提交
                2、住宿按城市等级执行""";

        assertThat(NumberGrounding.check(answer, "问题", List.of()).unverified()).isEmpty();
    }

    @Test
    void knowledgeBaseTextWithChineseNumeralsAndBrokenLinesCountsAsEvidence() {
        String chunk = "第八条 单程高铁时间超过六小时的，经审批可乘坐飞机经济舱。第九条 一线城市每人每晚不超过600元，省会城市不超过4\n50元。";

        NumberGrounding.Result result = NumberGrounding.check(
                "单程高铁超过 6 小时可以坐飞机[1]；一线城市不超过 600 元，省会城市不超过 450 元[1]。", "差旅标准",
                List.of(chunk));

        assertThat(result.unverified()).isEmpty();
    }

    @Test
    void numbersFromTheQuestionAreAllowed() {
        NumberGrounding.Result result = NumberGrounding.check("最近 7 天没有查到数据。", "最近 7 天的异常事件", List.of("{}"));

        assertThat(result.unverified()).isEmpty();
    }

    @Test
    void eachNumberIsReportedOnceAndAtMostTen() {
        StringBuilder answer = new StringBuilder("127 起，127 起");
        for (int i = 0; i < 15; i++) {
            answer.append("，").append(1000 + i);
        }

        NumberGrounding.Result result = NumberGrounding.check(answer.toString(), "", List.of());

        assertThat(result.unverified()).hasSize(10).startsWith("127", "1000");
    }

    @ParameterizedTest
    @CsvSource({"六, 6", "十, 10", "十二, 12", "二十, 20", "一百二十, 120", "三千五百, 3500", "两万, 20000", "十万, 100000"})
    void parsesChineseNumerals(String text, long expected) {
        assertThat(NumberGrounding.parseChinese(text)).isEqualTo(expected);
    }
}
