package com.example.kbagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 任务扫描（TaskScanner）和文件对账（StorageReconciler）依赖定时调度。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
