package com.syit.hmdp.controller;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.service.IVideoUploadService;

import com.syit.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequiredArgsConstructor
public class VideoController {

    private final IVideoUploadService videoUploadService;

    @GetMapping("/upload/video/check")
    public Result checkMd5(@RequestParam("md5") String md5) {
        Long userId = UserHolder.getUser().getId();
        return videoUploadService.checkMd5(userId, md5);
    }

    @PostMapping("/upload/video/init")
    public Result initUpload(@RequestBody String body) {
        Long userId = UserHolder.getUser().getId();
        JSONObject json = JSONUtil.parseObj(body);
        String filename = json.getStr("filename");
        long fileSize = json.getLong("fileSize", 0L);
        int totalChunks = json.getInt("totalChunks", 0);
        String md5 = json.getStr("md5");
        String contentType = json.getStr("contentType", "video/mp4");
        return videoUploadService.initUpload(userId, filename, fileSize, 0,
                totalChunks, md5, contentType);
    }

    @PostMapping("/upload/video/chunk")
    public Result uploadChunk(@RequestParam("uploadId") String uploadId,
                              @RequestParam("chunkIndex") int chunkIndex,
                              @RequestParam("chunk") MultipartFile chunk) {
        Long userId = UserHolder.getUser().getId();
        return videoUploadService.uploadChunk(userId, uploadId, chunkIndex, chunk);
    }

    @PostMapping("/upload/video/complete")
    public Result completeUpload(@RequestBody String body) {
        Long userId = UserHolder.getUser().getId();
        JSONObject json = JSONUtil.parseObj(body);
        String uploadId = json.getStr("uploadId");
        String partsJson = json.getJSONArray("parts").toString();
        return videoUploadService.completeUpload(userId, uploadId, partsJson);
    }

    @GetMapping("/upload/video/progress")
    public Result getProgress(@RequestParam("uploadId") String uploadId) {
        Long userId = UserHolder.getUser().getId();
        return videoUploadService.getProgress(userId, uploadId);
    }

    @GetMapping("/upload/video/cancel")
    public Result cancelUpload(@RequestParam("uploadId") String uploadId) {
        Long userId = UserHolder.getUser().getId();
        return videoUploadService.cancelUpload(userId, uploadId);
    }

    @RequestMapping(value = {"/video/play", "/api/video/play"}, method = RequestMethod.GET)
    public Result getPlayUrl(@RequestParam("videoId") Long videoId) {
        return videoUploadService.getPlayUrl(videoId);
    }
}
