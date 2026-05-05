package com.syit.hmdp.ws;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.syit.hmdp.dto.UserDTO;
import com.syit.hmdp.entity.ChatMessage;
import com.syit.hmdp.service.IAiService;
import com.syit.hmdp.service.IChatMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ChatWebSocketSessionManager sessionManager;
    @Autowired
    private IChatMessageService chatMessageService;
    @Autowired
    private IAiService aiService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UserDTO user = (UserDTO) session.getAttributes().get("user");
        if (user == null) {
            try { session.close(); } catch (Exception ignored) {}
            return;
        }
        sessionManager.addSession(user.getId(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        UserDTO user = (UserDTO) session.getAttributes().get("user");
        if (user == null) {
            return;
        }
        Long userId = user.getId();

        JSONObject payload;
        try {
            payload = JSONUtil.parseObj(message.getPayload());
        } catch (Exception e) {
            log.warn("消息 JSON 解析失败, userId={}", userId);
            return;
        }

        String type = payload.getStr("type");
        if (!"send".equals(type)) {
            return;
        }
        String content = payload.getStr("content");
        if (content == null || content.isBlank()) {
            return;
        }

        // 1. 保存用户消息
        chatMessageService.saveUserMessage(userId, content);

        // 2. 查历史（作为 AI 上下文）
        List<ChatMessage> history = chatMessageService.lambdaQuery()
                .and(w -> w.eq(ChatMessage::getFromUser, userId).or().eq(ChatMessage::getToUser, userId))
                .orderByAsc(ChatMessage::getCreateTime)
                .list();

        // 3. 调用 AI（设置 UserContext 供 Tool 使用）
        UserContext.set(userId);
        String aiReply;
        try {
            aiReply = aiService.chat(userId, content, history);
        } catch (Exception e) {
            log.error("AI 调用失败, userId={}", userId, e);
            aiReply = "抱歉，AI 服务暂时不可用，请稍后重试。";
        }

        // 4. 保存 AI 回复
        ChatMessage aiMsg = chatMessageService.saveAiReply(userId, aiReply);

        // 5. 构造推送 JSON
        JSONObject replyJson = new JSONObject();
        replyJson.set("type", "message");
        replyJson.set("from", "0");
        replyJson.set("fromName", "AI助手");
        replyJson.set("fromIcon", "/imgs/icons/ai-icon.png");
        replyJson.set("content", aiReply);
        replyJson.set("time", aiMsg.getCreateTime().format(TIME_FMT));

        sessionManager.sendToUser(userId, replyJson.toString());
        UserContext.clear();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UserDTO user = (UserDTO) session.getAttributes().get("user");
        if (user != null) {
            sessionManager.removeSession(user.getId());
        }
    }
}
