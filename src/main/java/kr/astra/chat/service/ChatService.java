package kr.astra.chat.service;

import java.util.*;
import java.util.function.Consumer;
import kr.astra.chat.dto.ChatResponse;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.LlmClient;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.llm.service.*;
import kr.astra.requirement.dto.RequirementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ChatService {
    private final ChatPersistence persistence;
    private final ConditionExtractionService extraction;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StructuredJson json;

    public ChatResponse.Exchange send(CurrentUser user, Long id, String content, Consumer<String> onText) {
        var turn = persistence.begin(user,id,content);
        try {
            var extracted = extraction.extract(turn.transcript().userText(),null);
            if (Thread.currentThread().isInterrupted()) throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
            String prompt = prompts.load("consultation");
            String payload = json.write(Map.of("messages",turn.transcript().messages().stream()
                    .map(m -> Map.of("role",m.role(),"content",m.content())).toList(),"conditions",extracted.conditions()));
            var request = new LlmClient.Request(prompt,payload,null,"low");
            var reply = onText == null ? llm.complete(request) : llm.stream(request,onText);
            if (Thread.currentThread().isInterrupted()) throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
            Map<String,Object> metadata = new LinkedHashMap<>(extracted.metadata());
            metadata.put("extractedConditions",extracted.conditions());
            metadata.put("suggestedTestType",extracted.testTypeCode());
            metadata.put("conversation",Map.of("prompt",prompt,"promptInput",payload,"rawResponse",reply.text(),
                    "model",reply.model(),"requestId",reply.requestId(),"inputTokens",reply.inputTokens(),"outputTokens",reply.outputTokens()));
            return persistence.complete(user,turn,reply.text(),metadata);
        } catch (RuntimeException e) {
            boolean interrupted = Thread.interrupted();
            try { persistence.fail(user,turn); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw e;
        }
    }

    public RequirementResponse requirement(CurrentUser user, Long id) {
        var transcript = persistence.transcript(user,id);
        var extracted = extraction.extract(transcript.userText(),null);
        if (extracted.testTypeCode() == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"시험유형을 명확히 입력해 주세요.",null);
        return persistence.saveRequirement(user,id,transcript,extracted);
    }
}
