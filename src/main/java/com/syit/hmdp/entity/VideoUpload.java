package com.syit.hmdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("video_upload")
public class VideoUpload implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId("upload_id")
    private String uploadId;

    private Long userId;

    private String objectKey;

    private String md5;

    private String filename;

    private Long fileSize;

    private Integer totalChunks;

    private String contentType;

    private Integer status;

    private LocalDateTime createdAt;
}
