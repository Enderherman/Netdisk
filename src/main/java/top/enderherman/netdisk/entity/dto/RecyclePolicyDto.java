package top.enderherman.netdisk.entity.dto;

public record RecyclePolicyDto(int retentionDays, boolean autoCleanupEnabled, String restoreStrategy) { }
