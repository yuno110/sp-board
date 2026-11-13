package com.example.board.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code server.forward-headers-strategy} 가 {@code none} 이다 (sp-docs/tech-stack.md §4.5,
 * sp-docs/plan/phase2-board.md B-16 완료 기준).
 *
 * <p><b>효과가 아니라 설정값을 단언한다.</b> 그 효과({@code X-Forwarded-For} 로 {@code remoteAddr}
 * 가 바뀌는가)는 컨테이너에서만 보인다 — {@code MockMvc} 에는 Tomcat 의 원격 주소 처리가 없다
 * (sp-docs/plan/phase2-board.md §4.1 이 기동한 board 의 측정으로 넘겼다). 줄이 빠지면 값이
 * {@code null} 이 되고, Boot 는 클라우드 플랫폼을 감지하면 {@code NATIVE} 로 동작한다 (§4.5) —
 * 그러면 비로그인 조회자의 판정 키가 클라이언트가 쓴 헤더를 따른다 (sp-docs/security.md §9).
 *
 * <p>{@code @DynamicPropertySource} 를 쓰지 않는다 — {@code BoardApplicationTest} 와 컨텍스트를
 * 공유한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ForwardHeadersStrategyTest {

	@Autowired
	private ServerProperties serverProperties;

	@Test
	@DisplayName("X-Forwarded-For 로 remoteAddr 를 바꾸지 않도록 none 이다")
	void forward_headers_strategy_가_none_이다() {
		assertThat(this.serverProperties.getForwardHeadersStrategy())
				.as("클라이언트가 임의로 쓰는 헤더를 믿지 않는다 (sp-docs/security.md §9)")
				.isEqualTo(ForwardHeadersStrategy.NONE);
	}

}
