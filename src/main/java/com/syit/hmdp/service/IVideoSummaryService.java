package com.syit.hmdp.service;

import com.syit.hmdp.dto.Result;

public interface IVideoSummaryService {

    Result startSummary(Long blogId);

    Result getSummary(Long blogId);
}
