package com.example.board.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

/**
 * 스케줄링이 켜져 있다 — sp-docs/plan/phase2-board.md B-16 의 완료 기준이다.
 * sp-docs/tech-stack.md §4.5 의 판정 행 정리 작업(B-21)이 이 활성화를 전제한다.
 *
 * <p><b>{@code @EnableScheduling} 이 등록하는 후처리기를 본다.</b> 그 후처리기가 없으면
 * {@code @Scheduled} 메서드가 예외 없이 무시되므로, 정리 작업을 더한 뒤에도 기동은 성공하고 판정
 * 행만 쌓인다 — 켜져 있는지는 이 단언으로 드러난다.
 *
 * <p>{@code @DynamicPropertySource} 를 쓰지 않는다 — {@code BoardApplicationTest} 와 컨텍스트를
 * 공유한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchedulingConfigTest {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	@DisplayName("@Scheduled 를 처리하는 후처리기가 컨텍스트에 있다")
	void 스케줄링이_켜져_있다() {
		assertThat(this.applicationContext.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
				.as("global/config 의 스케줄링 활성화가 빠졌다 (sp-docs/conventions.md §1.3)")
				.isNotEmpty();
	}

}
