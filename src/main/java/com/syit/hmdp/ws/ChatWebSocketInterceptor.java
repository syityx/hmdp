package com.syit.hmdp.ws;

import cn.hutool.core.bean.BeanUtil;
import com.syit.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

import static com.syit.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

@Slf4j
@Component
public class ChatWebSocketInterceptor implements HandshakeInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (!StringUtils.hasText(query)) {
            log.warn("WebSocket 握手缺少 token");
            return false;
        }

        String token = null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if ("token".equals(pair[0]) && pair.length == 2) {
                token = pair[1];
                break;
            }
        }

        if (!StringUtils.hasText(token)) {
            log.warn("WebSocket 握手 token 为空");
            return false;
        }

        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(LOGIN_USER_KEY + token);
        if (userMap.isEmpty()) {
            log.warn("WebSocket 握手 token 无效或已过期");
            return false;
        }

        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        attributes.put("user", user);
        log.info("WebSocket 握手成功, userId={}", user.getId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
