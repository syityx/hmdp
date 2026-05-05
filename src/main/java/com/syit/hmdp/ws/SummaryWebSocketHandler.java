package com.syit.hmdp.ws;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.dto.UserDTO;
import com.syit.hmdp.service.IVideoSummaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
public class SummaryWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private ChatWebSocketSessionManager sessionManager;
    @Autowired
    private IVideoSummaryService videoSummaryService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UserDTO user = (UserDTO) session.getAttributes().get("user");
        if (user == null) {
            try { session.close(); } catch (Exception ignored) {}
            return;
        }
        sessionManager.addSession(user.getId(), session);
        log.info("总结 WS 连接建立, userId={}", user.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        UserDTO user = (UserDTO) session.getAttributes().get("user");
        if (user == null) return;
        Long userId = user.getId();

        JSONObject payload;
        try {
            payload = JSONUtil.parseObj(message.getPayload());
        } catch (Exception e) {
            return;
        }

        if (!"summarize".equals(payload.getStr("type"))) return;

        Long blogId = payload.getLong("blogId");
        if (blogId == null) {
            sessionManager.sendToUser(userId, errorJson(blogId, "缺少 blogId"));
            return;
        }

        log.info("开始视频总结, userId={}, blogId={}", userId, blogId);
        Result result = videoSummaryService.summarizeBlog(blogId);

        JSONObject reply = new JSONObject();
        reply.set("type", "summaryResult");
        reply.set("blogId", blogId);
        reply.set("success", result.getSuccess());
        if (result.getSuccess()) {
            reply.set("data", result.getData());
        } else {
            reply.set("message", result.getErrorMsg());
        }

        sessionManager.sendToUser(userId, reply.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UserDTO user = (UserDTO) session.getAttributes().get("user");
        if (user != null) {
            sessionManager.removeSession(user.getId());
        }
    }

    private String errorJson(Long blogId, String message) {
        JSONObject reply = new JSONObject();
        reply.set("type", "summaryResult");
        reply.set("blogId", blogId);
        reply.set("success", false);
        reply.set("message", message);
        return reply.toString();
    }
}
