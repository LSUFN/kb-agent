package com.example.kbagent.task.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 异步任务配置（app.task.*，§4.2）。
 *
 * @param enabled           是否在本实例上扫描和执行任务
 * @param scanInterval      定时扫描间隔；扫描是任务执行的唯一可靠来源，提交事件只用于提前唤醒
 * @param leaseDuration     租约时长；执行者每处理完一批数据续租一次
 * @param workerConcurrency 每个实例同时执行的任务数
 * @param wakeUpOnCommit    任务所在事务提交后是否立即触发一次扫描
 */
@ConfigurationProperties("app.task")
public record TaskProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5s") Duration scanInterval,
        @DefaultValue("5m") Duration leaseDuration,
        @DefaultValue("2") int workerConcurrency,
        @DefaultValue("30s") Duration retryBaseDelay,
        @DefaultValue("10m") Duration retryMaxDelay,
        @DefaultValue("true") boolean wakeUpOnCommit) {

    /** 第 attempt 次执行失败后的等待时间：min(base × 2^attempt, max)。 */
    public Duration retryDelay(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt, 0), 20);
        Duration delay = retryBaseDelay.multipliedBy(factor);
        return delay.compareTo(retryMaxDelay) > 0 ? retryMaxDelay : delay;
    }
}
