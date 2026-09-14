package com.bobfull.chat.presentation.controller;
import static org.mockito.Mockito.*;
import com.bobfull.chat.presentation.dto.*; import com.bobfull.chat.infrastructure.websocket.StompPrincipal; import com.bobfull.chat.application.service.ChatMessageCommandService; import com.bobfull.common.exception.*; import com.bobfull.auth.application.model.AuthMember; import com.bobfull.member.domain.entity.MemberRole; import org.junit.jupiter.api.Test;
class ChatMessageControllerTest {
 @Test void Controller는_저장_Service만_호출하고_STOMP를_직접_발행하지_않는다(){ChatMessageCommandService service=mock(ChatMessageCommandService.class);ChatMessageController c=new ChatMessageController(service);c.send(3L,new ChatMessageSendRequest("안녕"),new StompPrincipal(new AuthMember(7L,MemberRole.MEMBER)));verify(service).send(3L,new AuthMember(7L,MemberRole.MEMBER),"안녕");}
 @Test void 저장_Service가_실패하면_예외를_전파한다(){ChatMessageCommandService service=mock(ChatMessageCommandService.class);ChatMessageController c=new ChatMessageController(service);when(service.send(anyLong(),any(),any())).thenThrow(new CustomException(CommonErrorCode.INVALID_INPUT_VALUE));org.assertj.core.api.Assertions.assertThatThrownBy(()->c.send(3L,new ChatMessageSendRequest(""),new StompPrincipal(new AuthMember(7L,MemberRole.MEMBER)))).isInstanceOf(CustomException.class);}
}
