package com.syit.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.ChatMessage;

public interface IChatMessageService extends IService<ChatMessage> {

    Result getHistory(Long userId, int page, int size);

    ChatMessage saveUserMessage(Long userId, String content);

    ChatMessage saveAiReply(Long userId, String content);
}
