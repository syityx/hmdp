package com.syit.hmdp.service;

import com.syit.hmdp.entity.ChatMessage;

import java.util.List;

public interface IAiService {

    String chat(Long userId, String userMessage, List<ChatMessage> history);

    String summarize(String prompt);
}
