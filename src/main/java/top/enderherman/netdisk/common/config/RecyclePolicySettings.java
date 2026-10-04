package top.enderherman.netdisk.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.enderherman.netdisk.entity.dto.RecyclePolicyDto;

@Component
public class RecyclePolicySettings {
    @Value("${netdisk.recycle.retention-days:30}") private int retentionDays;
    @Value("${netdisk.cleanup-enabled:true}") private boolean cleanupEnabled;

    public RecyclePolicyDto policy() {
        if (retentionDays < 0) throw new IllegalStateException("回收站保留天数不能为负数");
        return new RecyclePolicyDto(retentionDays, cleanupEnabled && retentionDays > 0, "original_or_root");
    }
}
