package com.ieum.ieum.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Scheduled(정해진 시간에 실행) 기능을 켠다. 축제 데이터를 매일 새벽에 받아 오는 데 쓴다 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
