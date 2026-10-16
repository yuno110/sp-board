package com.example.board.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code application-prod.yml} 이 운영에서 꺼야 하는 것을 실제로 끄는지 본다
 * (sp-docs/plan/phase1.md §6 B-09 의 완료 기준 "prod 프로파일에서 Swagger·SQL 로그가 꺼진다").
 *
 * <p><b>프로파일 순서는 지금 결과를 바꾸지 않는다.</b> {@code application-prod.yml} 과
 * {@code application-test.yml} 이 적는 키가 겹치지 않아 어느 순서로 선언해도 같은 설정으로
 * 해석된다. 그래서 DB 접속은 {@code application-test.yml} 의 H2 가 쓰이고
 * (sp-docs/tech-stack.md §5.3), {@code application-test.yml} 이 건드리지 않는 springdoc·로그
 * 설정은 {@code application-prod.yml} 의 값이 그대로 보인다.
 *
 * <p><b>{@code test} 를 나중에 적는 것은 안전장치다.</b> 뒤에 온 프로파일의 설정이 이기므로,
 * {@code application-prod.yml} 이 뒤에 환경값을 받는 datasource 설정을 갖게 되어 키가 겹치면
 * 그때 {@code test} 의 H2 가 이겨 이 테스트가 실제 DB 로 끌려가지 않는다.
 *
 * <p>운영 MySQL 에 의존하지 않고 공개키도 커밋된 실제 키를 그대로 읽으므로(서명이 필요한
 * 테스트가 아니다) 다른 PC·CI 에서도 돈다.
 *
 * <p><b>열린 쪽의 대조는 {@code test} 프로파일 테스트들이 갖고 있다.</b> 같은 세 경로가
 * {@link SwaggerConfigTest} 와 {@link JwtAuthenticationTest} 에서 200(또는 리다이렉트)이다 —
 * 그 대조가 아래 404 의 출처를 가린다. 대조가 없으면 "경로를 잘못 적어서 404" 와 구별되지
 * 않는다.
 *
 * <p>AU-11·M-11 이 같은 모양으로 고정했다 — 세 서비스가 같은 운영 설정을 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"prod", "test"})
class ProdProfileTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private Environment environment;

	/**
	 * {@code /swagger-ui.html} 과 {@code /v3/api-docs} 를 함께 본다. UI 만 꺼도 후자가 열려
	 * 있으면 전체 엔드포인트 목록을 얻을 수 있다.
	 *
	 * <p>{@code SecurityConfig} 는 두 경로를 {@code permitAll} 로 두므로 — 경로별 인가는
	 * 프로파일을 모른다 — 404 를 만드는 것은 springdoc 쪽 설정이다.
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs"})
	@DisplayName("prod 프로파일에서 API 문서 경로가 404 다")
	void prod_에서_api_문서가_닫힌다(String path) throws Exception {
		this.mockMvc.perform(get(path))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("C004"));
	}

	/**
	 * SQL 로그는 로컬 프로파일만 ON 이다 (sp-docs/nfr.md §5).
	 *
	 * <p><b>효과가 아니라 설정값을 단언한다.</b> 루트 수준 기본이 {@code INFO} 라
	 * {@code logging.level} 블록이 없어도 두 로거는 이미 조용하다 — 로거의 활성 여부를 보는
	 * 단언은 블록이 사라진 것을 잡지 못한다(실패할 수 없는 단언이다,
	 * sp-docs/process/review-policy.md §4.1). 블록이 일하는 순간은 루트 수준이 올라갈 때
	 * ({@code LOGGING_LEVEL_ROOT=DEBUG}) 다.
	 *
	 * <p><b>board 에는 비밀번호가 없다.</b> 정본이 드는 보안 근거("바인드 파라미터 로그에
	 * 비밀번호가 평문으로 실린다")는 auth 의 사실이고 board 에 직접 적용되지 않는다 — 계정은
	 * auth 소유다 (sp-docs/adr/0012). board 의 바인드 파라미터에 실리는 것은 {@code writer_id}·
	 * 닉네임과 사용자가 쓴 제목·본문이다. 그래도 같은 모양으로 고정한다: 세 서비스가 같은 운영
	 * 설정을 쓴다 (sp-docs/plan/phase1.md §6 B-09).
	 */
	@Test
	@DisplayName("prod 프로파일에서 SQL 로그와 바인드 파라미터 로그가 꺼진다")
	void prod_에서_sql_로그가_꺼진다() {
		assertThat(this.environment.getProperty("spring.jpa.show-sql", Boolean.class))
				.isFalse();
		assertThat(this.environment.getProperty("logging.level.org.hibernate.SQL"))
				.as("Hibernate 는 SQL 을 DEBUG 로 남긴다")
				.isEqualTo("warn");
		assertThat(this.environment.getProperty("logging.level.org.hibernate.orm.jdbc.bind"))
				.as("바인드 파라미터는 TRACE 로 남는다. board 에서는 작성자와 제목·본문이 실린다")
				.isEqualTo("warn");
	}

	/**
	 * {@code springdoc.api-docs.enabled} 를 <b>따로</b> 단언한다. 위의 404 는 두 설정 중 하나만
	 * 켜져 있어도 깨지지만, 어느 쪽이 빠졌는지는 말해 주지 않는다 — UI 만 끈 상태를 지목하는
	 * 것이 이 단언이다 (sp-docs/plan/phase1.md §6 B-09 완료 기준).
	 */
	@Test
	@DisplayName("prod 프로파일에서 springdoc 의 api-docs 와 swagger-ui 가 둘 다 꺼진다")
	void prod_에서_springdoc_가_둘_다_꺼진다() {
		assertThat(this.environment.getProperty("springdoc.api-docs.enabled", Boolean.class))
				.as("UI 만 끄면 /v3/api-docs 가 전체 엔드포인트 목록을 그대로 낸다")
				.isFalse();
		assertThat(this.environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class))
				.isFalse();
	}

}
