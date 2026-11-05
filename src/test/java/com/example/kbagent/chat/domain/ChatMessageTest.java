package com.example.kbagent.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** §4.3 消息状态机：STREAMING 只能变为一个终态，终态之间不能互相转换。 */
class ChatMessageTest {

    @ParameterizedTest
    @EnumSource(MessageStatus.class)
    void onlyStreamingCanMoveToATerminalState(MessageStatus from) {
        for (MessageStatus to : MessageStatus.values()) {
            boolean expected = from == MessageStatus.STREAMING && to != MessageStatus.STREAMING;
            assertThat(from.canTransitionTo(to)).as(from + " -> " + to).isEqualTo(expected);
        }
    }

    @Test
    void questionIsCreatedAsOk() {
        ChatMessage question = ChatMessage.question(1L, "报销期限？", "trace-1");

        assertThat(question.role()).isEqualTo(MessageRole.USER);
        assertThat(question.status()).isEqualTo(MessageStatus.OK);
        assertThat(question.content()).isEqualTo("报销期限？");
    }

    @Test
    void placeholderCompletesOnce() {
        ChatMessage answer = ChatMessage.answerPlaceholder(1L, "trace-1");
        assertThat(answer.status()).isEqualTo(MessageStatus.STREAMING);
        assertThat(answer.content()).isEmpty();

        answer.complete("30 天[1]", CitationMode.NORMAL, "[]", null);

        assertThat(answer.status()).isEqualTo(MessageStatus.OK);
        assertThat(answer.citationMode()).isEqualTo(CitationMode.NORMAL);
        assertThatThrownBy(() -> answer.fail("x", "UPSTREAM_ERROR", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> answer.interrupt("x", "CLIENT_CLOSED", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedAndInterruptedKeepPartialContent() {
        assertTerminal(m -> m.fail("部分内容", "UPSTREAM_TIMEOUT", null), MessageStatus.FAILED, "UPSTREAM_TIMEOUT");
        assertTerminal(m -> m.interrupt("部分内容", "CLIENT_CLOSED", null), MessageStatus.INTERRUPTED, "CLIENT_CLOSED");
    }

    private static void assertTerminal(Consumer<ChatMessage> transition, MessageStatus status, String errorCode) {
        ChatMessage answer = ChatMessage.answerPlaceholder(1L, "trace-1");
        transition.accept(answer);

        assertThat(answer.status()).isEqualTo(status);
        assertThat(answer.content()).isEqualTo("部分内容");
        assertThat(answer.errorCode()).isEqualTo(errorCode);
        assertThatThrownBy(() -> answer.complete("x", CitationMode.NORMAL, "[]", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void conversationTakesItsTitleFromTheFirstQuestion() {
        Conversation conversation = Conversation.create(1L, ConversationMode.KB_QA, null, null);
        assertThat(conversation.title()).isEqualTo(Conversation.DEFAULT_TITLE);

        conversation.onNewQuestion("  出差\n回来以后，报销   最晚什么时候提交？还有哪些材料需要准备，审批流程是怎样的呢  ");
        assertThat(conversation.title()).isEqualTo("出差 回来以后，报销 最晚什么时候提交？还有哪些材料需要准备");
        assertThat(conversation.title().codePointCount(0, conversation.title().length())).isEqualTo(30);

        conversation.onNewQuestion("第二个问题不会改标题");
        assertThat(conversation.title()).startsWith("出差");
    }

    @Test
    void autoTitleDoesNotSplitSurrogatePairs() {
        Conversation conversation = Conversation.create(1L, ConversationMode.KB_QA, null, null);
        conversation.onNewQuestion("😀".repeat(40));

        assertThat(conversation.title()).isEqualTo("😀".repeat(30));
    }

    @Test
    void explicitTitleIsKept() {
        Conversation conversation = Conversation.create(1L, ConversationMode.KB_QA, null, "  差旅问答 ");
        conversation.onNewQuestion("报销期限？");

        assertThat(conversation.title()).isEqualTo("差旅问答");
    }
}
