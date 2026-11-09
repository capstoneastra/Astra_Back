package kr.astra.chat;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.util.function.Consumer;
import kr.astra.chat.controller.ChatController;
import kr.astra.chat.dto.ChatResponse;
import kr.astra.chat.entity.ChatRole;
import kr.astra.chat.service.*;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.AiTaskExecutor;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class ChatControllerTest {
    final ChatPersistence persistence=mock(ChatPersistence.class);
    final ChatService service=mock(ChatService.class);
    final AiTaskExecutor executor=new AiTaskExecutor();
    final CurrentUser user=new CurrentUser(1L,UserRole.COMPANY_USER,null);
    MockMvc mvc;
    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
        mvc=MockMvcBuilders.standaloneSetup(new ChatController(persistence,service,executor))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void close() { executor.close(); SecurityContextHolder.clearContext(); }
    ChatResponse.Exchange exchange() {
        return new ChatResponse.Exchange(new ChatResponse.Message(1L,ChatRole.USER,"시험",Map.of(),null),
                new ChatResponse.Message(2L,ChatRole.ASSISTANT,"답변",Map.of(),null));
    }
    @Test void normalJsonUsesEnvelopeAndValidatesInput() throws Exception {
        when(service.send(eq(user),eq(5L),eq("시험"),isNull())).thenReturn(exchange());
        mvc.perform(post("/chat/sessions/5/messages").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"시험\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.assistantMessage.id").value(2));
        mvc.perform(post("/chat/sessions/5/messages").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"\"}")).andExpect(status().isBadRequest());
    }
    @Test void sseStreamsDeltaThenSavedExchange() throws Exception {
        when(service.send(eq(user),eq(5L),eq("시험"),any())).thenAnswer(i -> {
            Consumer<String> c=i.getArgument(3); c.accept("답"); c.accept("변"); return exchange();
        });
        MvcResult started=mvc.perform(post("/chat/sessions/5/messages").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content("{\"content\":\"시험\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(5000);
        var result=mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("event:delta","event:done","assistantMessage");
    }
    @Test void sseFailureHasErrorAndNoDone() throws Exception {
        when(service.send(eq(user),eq(5L),eq("시험"),any())).thenThrow(new BusinessException(ErrorCode.AI_UPSTREAM_ERROR));
        var started=mvc.perform(post("/chat/sessions/5/messages").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content("{\"content\":\"시험\"}")).andReturn();
        started.getAsyncResult(5000);
        var result=mvc.perform(asyncDispatch(started)).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("event:error","AI_UPSTREAM_ERROR").doesNotContain("event:done");
    }
    @Test void forbiddenResourceReturnsHttp403BeforeSseStarts() throws Exception {
        when(persistence.ownerId(user,5L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        mvc.perform(post("/chat/sessions/5/messages").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"content\":\"시험\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
