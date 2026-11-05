package com.example.kbagent.tool;

import java.util.Arrays;
import java.util.stream.Collectors;

import com.example.kbagent.user.domain.Region;

/** 写在业务数据工具结果里给模型看的说明。 */
final class ToolNotes {

    /** 区域代码的中文名，避免模型自己翻译（实测会把 EAST 说成“东部区域”）；业务数据不需要标注引用编号。 */
    static final String REGIONS = "区域代码：" + Arrays.stream(Region.values())
            .map(r -> r.name() + " " + r.displayName())
            .collect(Collectors.joining("，")) + "；这些数据不需要标注引用编号";

    private ToolNotes() {
    }
}
