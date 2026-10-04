package top.enderherman.netdisk.task;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.enderherman.netdisk.service.impl.UploadTaskService;

@Slf4j
@Component
@ConditionalOnProperty(name = {"netdisk.cleanup-enabled", "netdisk.upload.cleanup-enabled"}, havingValue = "true", matchIfMissing = true)
public class UploadTaskCleanup {
    @Resource private UploadTaskService uploadTasks;

    @Scheduled(fixedDelayString = "${netdisk.upload.cleanup-interval-ms:300000}", initialDelayString = "${netdisk.upload.cleanup-interval-ms:300000}")
    public void cleanup() {
        UploadTaskService.CleanupResult result = uploadTasks.cleanupExpiredTasks();
        if (result.expiredTasks() + result.removedReceipts() + result.failedTasks() > 0) {
            log.info("上传任务清理：过期 {}，回执移除 {}，失败 {}", result.expiredTasks(), result.removedReceipts(), result.failedTasks());
        }
    }
}
