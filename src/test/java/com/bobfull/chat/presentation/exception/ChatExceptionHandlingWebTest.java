package com.bobfull.chat.presentation.exception;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ChatExceptionTestController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test-api")
class ChatExceptionHandlingWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 신고_검토중_낙관적_락_충돌시_이미_검토된_신고_응답을_반환한다() throws Exception {
        mockMvc.perform(patch("/api/test/chat-report-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.code", is("CHAT_ROOM_REPORT_ALREADY_REVIEWED")))
                .andExpect(jsonPath("$.message", is("이미 검토된 신고입니다.")));
    }
}
