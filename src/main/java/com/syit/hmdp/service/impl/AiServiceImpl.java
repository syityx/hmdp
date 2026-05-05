package com.syit.hmdp.service.impl;

import com.syit.hmdp.entity.ChatMessage;
import com.syit.hmdp.service.IAiService;
import com.syit.hmdp.ws.BlogTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AiServiceImpl implements IAiService {

    private static final long AI_UID = 0L;

    private final ChatClient chatClient;
    private final ChatClient chatClientWithoutTools;
    private final String systemPrompt;

    public AiServiceImpl(ChatClient.Builder chatClientBuilder,
                         @Value("${chat.system-prompt}") String systemPrompt,
                         BlogTools blogTools) {
        this.chatClient = chatClientBuilder
                .defaultTools(blogTools)
                .build();
        this.chatClientWithoutTools = chatClientBuilder.build();
        this.systemPrompt = systemPrompt;
    }

    @Override
    public String chat(Long userId, String userMessage, List<ChatMessage> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        for (ChatMessage msg : history) {
            if (msg.getFromUser() != null && msg.getFromUser() == AI_UID) {
                messages.add(new AssistantMessage(msg.getContent()));
            } else {
                messages.add(new UserMessage(msg.getContent()));
            }
        }
        messages.add(new UserMessage(userMessage));

        log.debug("调用 AI, userId={}, historySize={}", userId, history.size());
        String reply = chatClient.prompt()
                .messages(messages)
                .call()
                .content();
        log.debug("AI 回复, userId={}, replyLength={}", userId,
                reply != null ? reply.length() : 0);
        return reply;
    }

    @Override
    public String summarize(String prompt) {
        return chatClientWithoutTools.prompt().user(prompt).call().content();
    }
}
