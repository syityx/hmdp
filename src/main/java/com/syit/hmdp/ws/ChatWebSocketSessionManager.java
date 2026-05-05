package com.syit.hmdp.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ChatWebSocketSessionManager {

    private final ConcurrentHashMap<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void addSession(Long userId, WebSocketSession session) {
        sessions.put(userId, session);
        log.info("WebSocket 上线, userId={}, 在线人数={}", userId, sessions.size());
    }

    public void removeSession(Long userId) {
        sessions.remove(userId);
        log.info("WebSocket 下线, userId={}, 在线人数={}", userId, sessions.size());
    }

    public void sendToUser(Long userId, String message) {
        WebSocketSession session = sessions.get(userId);
        if (session != null && session.isOpen()) {
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(message));
                }
            } catch (IOException e) {
                log.error("推送消息失败, userId={}", userId, e);
                removeSession(userId);
            }
        }
    }
}
