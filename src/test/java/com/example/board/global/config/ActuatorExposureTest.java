package com.example.board.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code /actuator} 에서 <b>health 만 노출된다</b> (sp-docs/security.md §5.1.1,
 * sp-docs/plan/phase1.md §6 B-09 완료 기준).
 *
 * <p><b>HTTP 로는 이 설정이 관측되지 않는다.</b> {@code SecurityConfig} 가
 * {@code /actuator/**} 를 {@code denyAll} 로 두어 필터 체인이 디스패치보다 먼저 거부하므로,
 * 노출 여부와 무관하게 무토큰 401 {@code A001} · 유효 토큰 403 {@code A004} 가 나간다
 * (§5.1.1 의 측정값. 그 응답은 {@code JwtAuthenticationTest} 가 본다). 그래서
 * {@code management.endpoints.web.exposure.include} 를 지워도 HTTP 단언은 전부 녹색이다 —
 * <b>등록된 엔드포인트 집합을 직접 본다.</b>
 *
 * <p><b>두 겹 중 하나가 빠진 것을 이 테스트가 지목한다.</b> 인가 차단만 남으면
 * {@code /actuator/**} 규칙이 좁아지는 순간(예: 경로 하나만 막는 형태로 바뀌는 것) 등록된
 * 엔드포인트가 그대로 열린다. 반대 방향은 {@code JwtAuthenticationTest} 가 본다.
 *
 * <p>{@code @DynamicPropertySource} 를 쓰지 않는다 — 토큰을 서명하지 않으므로 커밋된 실제
 * 공개키를 그대로 읽어도 되고, 그래야 {@code BoardApplicationTest} 와 컨텍스트를 공유한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ActuatorExposureTest {

	@Autowired
	private WebEndpointsSupplier webEndpointsSupplier;

	/**
	 * <b>이름으로 집합을 고정한다.</b> 개수만 세면 하나를 빼고 다른 하나를 넣은 변경이 통과한다.
	 */
	@Test
	@DisplayName("웹으로 노출되는 actuator 엔드포인트가 health 하나다")
	void actuator_는_health_만_노출한다() {
		assertThat(this.webEndpointsSupplier.getEndpoints())
				.extracting(ExposableWebEndpoint::getEndpointId)
				.extracting(Object::toString)
				.as("env·beans·configprops·mappings 가 등록되면 설정·환경변수·전체 매핑이 "
						+ "인가 설정 하나에만 의존해 가려진다 (sp-docs/security.md §5.1.1)")
				.containsExactly("health");
	}

}
