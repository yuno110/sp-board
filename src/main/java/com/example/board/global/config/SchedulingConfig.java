package com.example.board.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링을 켠다. 자리는 sp-docs/conventions.md §1.3 의 {@code global/config} 다.
 *
 * <p>조회수 판정 행({@code post_view})의 정리 작업(B-21)이 이 활성화를 전제한다 — 기준 시각이
 * 24시간을 넘은 행을 {@code view-count.cleanup-interval} 주기로 지운다 (sp-docs/tech-stack.md §4.5,
 * sp-docs/requirements/board.md §9.3). 작업 자체는 여기 두지 않는다 — 조회수 판정이 있는
 * {@code post} 패키지의 몫이다 (sp-docs/conventions.md §1.3).
 *
 * <p><b>의존성을 더하지 않는다</b> — {@code @EnableScheduling} 은 이미 있는
 * {@code spring-context} 의 것이다 (sp-docs/tech-stack.md §4.5). 인스턴스가 여럿이면 각자
 * 돌지만 지우는 조건이 같아 결과가 같다 (같은 절).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
