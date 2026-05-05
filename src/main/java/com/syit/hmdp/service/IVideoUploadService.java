package com.syit.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.VideoFile;
import com.syit.hmdp.entity.VideoUpload;
import org.springframework.web.multipart.MultipartFile;

public interface IVideoUploadService extends IService<VideoUpload> {

    Result checkMd5(Long userId, String md5);

    Result initUpload(Long userId, String filename, long fileSize, long chunkSize,
                      int totalChunks, String md5, String contentType);

    Result uploadChunk(Long userId, String uploadId, int chunkIndex, MultipartFile chunk);

    Result completeUpload(Long userId, String uploadId, String partsJson);

    Result getProgress(Long userId, String uploadId);

    Result cancelUpload(Long userId, String uploadId);

    Result getPlayUrl(Long videoId);
}
