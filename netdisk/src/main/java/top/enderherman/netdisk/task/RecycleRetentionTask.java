package top.enderherman.netdisk.task;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.enderherman.netdisk.common.config.RecyclePolicySettings;
import top.enderherman.netdisk.entity.enums.FileDeleteFlagEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.service.impl.RecycleStorageService;

import java.util.Date;
import java.util.TreeSet;

/** 只做逻辑永久删除和配额回收；实体始终交给 ScheduledTask 的引用/路径保护清理。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "netdisk.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class RecycleRetentionTask {
    @Resource private RecyclePolicySettings policy;
    @Resource private FileMapper<FileInfo, FileQuery> fileMapper;
    @Resource private RecycleStorageService recycleStorage;

    @Scheduled(cron = "${netdisk.recycle.cleanup-cron:0 0 3 * * ?}", zone = "${netdisk.time-zone:Asia/Shanghai}")
    public void expire() {
        int count = expireAt(new Date());
        if (count > 0) log.info("回收站保留期限清理：永久删除 {} 项", count);
    }

    public int expireAt(Date now) {
        var settings = policy.policy();
        if (!settings.autoCleanupEnabled()) return 0;
        Date cutoff = new Date(now.getTime() - settings.retentionDays() * 86_400_000L);
        FileQuery query = new FileQuery();
        query.setDelFlag(FileDeleteFlagEnum.RECYCLE.getFlag());
        TreeSet<String> users = new TreeSet<>();
        for (FileInfo file : fileMapper.selectList(query)) {
            if (file.getRecoveryTime() != null && !file.getRecoveryTime().after(cutoff)) users.add(file.getUserId());
        }
        int affected = 0;
        for (String user : users) {
            try { affected += recycleStorage.expireUserTrash(user, cutoff); }
            catch (RuntimeException failure) { log.warn("用户回收站过期清理失败，userId={}", user, failure); }
        }
        return affected;
    }
}
