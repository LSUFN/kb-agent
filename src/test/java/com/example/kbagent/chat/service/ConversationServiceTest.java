package com.example.kbagent.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationServiceTest {

    @Test
    void likePatternEscapesWildcardsAndIgnoresCase() {
        assertThat(ConversationService.likePattern(" 差旅 ")).isEqualTo("%差旅%");
        assertThat(ConversationService.likePattern("Q4")).isEqualTo("%q4%");
        assertThat(ConversationService.likePattern("100%_a\\b")).isEqualTo("%100\\%\\_a\\\\b%");
    }
}
