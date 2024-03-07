package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;//定义成泛型
    private Long minTime;
    private Integer offset;
}
