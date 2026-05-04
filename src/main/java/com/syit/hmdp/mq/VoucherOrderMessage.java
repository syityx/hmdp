package com.syit.hmdp.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long orderId;
    private Long voucherId;
    private Long userId;
    private Long createTimestamp;
    private String tag;
}
