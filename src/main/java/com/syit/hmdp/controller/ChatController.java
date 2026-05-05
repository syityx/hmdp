package com.syit.hmdp.controller;

import com.syit.hmdp.dto.Result;
import com.syit.hmdp.service.IChatMessageService;
import com.syit.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Autowired
    private IChatMessageService chatMessageService;

    @GetMapping("/history")
    public Result getHistory(@RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int size) {
        Long userId = UserHolder.getUser().getId();
        return chatMessageService.getHistory(userId, page, size);
    }
}
