package com.syit.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.ChatMessage;
import com.syit.hmdp.mapper.ChatMessageMapper;
import com.syit.hmdp.service.IChatMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements IChatMessageService {

    private static final long AI_UID = 0L;

    @Override
    public Result getHistory(Long userId, int page, int size) {
        Page<ChatMessage> pageParam = new Page<>(page, size);
        Page<ChatMessage> result = lambdaQuery()
                .eq(ChatMessage::getFromUser, userId).or()
                .eq(ChatMessage::getToUser, userId)
                .orderByAsc(ChatMessage::getCreateTime)
                .page(pageParam);
        return Result.ok(result.getRecords(), result.getTotal());
    }

    @Override
    public ChatMessage saveUserMessage(Long userId, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setFromUser(userId);
        msg.setToUser(AI_UID);
        msg.setContent(content);
        msg.setCreateTime(LocalDateTime.now());
        save(msg);
        return msg;
    }

    @Override
    public ChatMessage saveAiReply(Long userId, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setFromUser(AI_UID);
        msg.setToUser(userId);
        msg.setContent(content);
        msg.setCreateTime(LocalDateTime.now());
        save(msg);
        return msg;
    }
}
