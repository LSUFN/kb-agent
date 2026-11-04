package com.example.kbagent.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** §4.5 层次 A：引用合法性。 */
class CitationParserTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            根据制度[1]                 | 1
            见[1][2]                    | 1,2
            见[1,2]                     | 1,2
            见[1, 3]                    | 1,3
            见[1，2]                    | 1,2
            见[2、3]                    | 2,3
            见【2】                      | 2
            见【1，3】                    | 1,3
            见［3］                      | 3
            见[ 2 ]                     | 2
            """)
    void supportsCommonFormats(String answer, String expected) {
        CitationParser.Result result = CitationParser.parse(answer, 3);

        assertThat(result.cited()).map(String::valueOf).containsExactly(expected.split(","));
        assertThat(result.invalid()).isEmpty();
    }

    @Test
    void outOfRangeZeroAndNegativeIndicesAreInvalid() {
        CitationParser.Result result = CitationParser.parse("甲[1]，乙[4]，丙[0]，丁[-1]，戊[2][99]", 3);

        assertThat(result.cited()).containsExactly(1, 2);
        assertThat(result.invalid()).containsExactly(-1, 0, 4, 99);
    }

    @Test
    void mixedFormatsAreDeduplicatedAndSorted() {
        CitationParser.Result result = CitationParser.parse("[3]……[1][3]……【1】……[1,3]……[9][9]", 3);

        assertThat(result.cited()).containsExactly(1, 3);
        assertThat(result.invalid()).containsExactly(9);
    }

    @Test
    void answerWithoutCitationsYieldsNothing() {
        assertThat(CitationParser.parse("没有任何引用的回答。", 3).cited()).isEmpty();
        assertThat(CitationParser.parse("", 3).cited()).isEmpty();
        assertThat(CitationParser.parse(null, 3).cited()).isEmpty();
    }

    @Test
    void noCandidatesMakesEveryIndexInvalid() {
        CitationParser.Result result = CitationParser.parse("答案[1]", 0);

        assertThat(result.cited()).isEmpty();
        assertThat(result.invalid()).containsExactly(1);
    }

    @Test
    void codeBlocksAndInlineCodeAreIgnored() {
        String answer = """
                示例代码[1]：
                ```java
                int x = values[2];
                ```
                行内 `arr[3]` 不算，正文[2]算。
                ~~~
                list[1]
                ~~~
                """;

        CitationParser.Result result = CitationParser.parse(answer, 3);

        assertThat(result.cited()).containsExactly(1, 2);
        assertThat(result.invalid()).isEmpty();
    }

    @Test
    void unterminatedCodeFenceRunsToTheEnd() {
        CitationParser.Result result = CitationParser.parse("正文[1]\n```\ncode[2] 被中断", 3);

        assertThat(result.cited()).containsExactly(1);
    }

    @Test
    void textThatOnlyLooksLikeBracketsIsNotACitation() {
        CitationParser.Result result = CitationParser.parse("复选框 [ ] [x]，链接 [文档](http://a.b)，区间 [1-3]", 3);

        assertThat(result.cited()).isEmpty();
        assertThat(result.invalid()).isEmpty();
    }

    @Test
    void stripMarksRemovesAllFormats() {
        assertThat(CitationParser.stripMarks("报销期限为 30 天[1][2]，标准见【3】和[1,2]。"))
                .isEqualTo("报销期限为 30 天，标准见和。");
        assertThat(CitationParser.stripMarks(null)).isEmpty();
    }
}
