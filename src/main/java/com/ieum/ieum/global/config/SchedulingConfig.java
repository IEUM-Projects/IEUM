package com.ieum.ieum.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Scheduled(정해진 시간에 실행) 기능을 켠다. 축제 데이터 받기(04:30), 만료된 로그인 토큰 정리(04:20)에 쓴다 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
