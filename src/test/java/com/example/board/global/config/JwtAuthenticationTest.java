package com.example.board.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.global.security.LoginMember;
import com.example.board.global.security.RoleClaimConverter;
import com.example.board.global.security.SecurityErrorResponder;
import com.example.board.support.TestTokenFactory;
import com.example.securityprobe.CurrentMemberProbeController;
import jakarta.servlet.Filter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * sp-docs/plan/phase1.md §6 B-04 의 "검증" 표를 테스트로 옮긴 것이다.
 *
 * <p>업무 경로의 정본은 sp-docs/api-contract.md §4, 인프라 경로는 sp-docs/security.md §5.1.1,
 * 인증 실패 코드의 경계는 sp-docs/api-contract.md §8.5 다.
 *
 * <p><b>업무 컨트롤러는 아직 없다</b>(B-05~B-09). 그래서 인가를 통과하는 경로의 기대값을
 * "200"이 아니라 <b>"401 이 아님"</b>으로 둔다 — 인가를 통과하면 그 뒤는 매핑 없음이 된다.
 * 컨트롤러가 생겨도 이 단언은 그대로 유효하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtAuthenticationTest {

	/** application-test.yml 이 주는 테스트 전용 오리진. */
	private static final String ALLOWED_ORIGIN = "http://localhost:3000";

	/**
	 * 픽스처 {@code accountId}. 다른 테스트가 쓰는 범위(201~210, 301~317)와 겹치지 않게
	 * 401 이상에서 고른다.
	 */
	private static final long ACCOUNT_ID = 401L;

	@DynamicPropertySource
	static void jwtPublicKey(DynamicPropertyRegistry registry) {
		TestTokenFactory.registerPublicKeyLocation(registry);
	}

	@TestConfiguration
	static class ProbeConfiguration {

		@Bean
		CurrentMemberProbeController currentMemberProbeController() {
			return new CurrentMemberProbeController();
		}

	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CorsConfigurationSource corsConfigurationSource;

	@Autowired
	private SecurityErrorResponder securityErrorResponder;

	@Autowired
	private FilterChainProxy filterChainProxy;

	// --- 업무 경로 (sp-docs/api-contract.md §4) ---

	@Test
	@DisplayName("유효 토큰으로 POST /api/v1/posts 는 401 이 아니다")
	void 유효_토큰으로_글_작성_경로를_통과한다() throws Exception {
		this.mockMvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(notUnauthorized());
	}

	@Test
	@DisplayName("토큰 없이 POST /api/v1/posts 는 401 A001 이다")
	void 토큰_없이_글_작성은_A001() throws Exception {
		this.mockMvc.perform(post("/api/v1/posts"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("A001"))
				.andExpect(jsonPath("$.error.message").value("인증이 필요합니다."));
	}

	/** §4 의 인증 필요 경로 전부. 하나라도 선언이 빠지면 {@code anyRequest} 가 받아 401 이 된다. */
	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({
			"POST,/api/v1/posts",
			// 내가 쓴 글 목록 (P-08, sp-docs/api-contract.md §4). 규칙을 /posts/{id} 보다
			// 뒤에 두면 permitAll 이 먼저 잡아 401 이 아니게 되므로 이 한 줄이 순서를 고정한다
			"GET,/api/v1/posts/me",
			"PUT,/api/v1/posts/1",
			"DELETE,/api/v1/posts/1",
			"POST,/api/v1/posts/1/comments",
			"PUT,/api/v1/comments/1",
			"DELETE,/api/v1/comments/1"
	})
	@DisplayName("인증이 필요한 경로는 토큰 없이 401 A001 이다")
	void 인증이_필요한_경로(String method, String path) throws Exception {
		this.mockMvc.perform(request(HttpMethod.valueOf(method), path))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A001"));
	}

	/**
	 * 비로그인 허용 경로 (sp-docs/api-contract.md §4, sp-docs/security.md §5.2).
	 *
	 * <p>같은 경로의 쓰기 메서드는 위 테스트가 401 로 단언한다 — 메서드를 가르지 않고
	 * {@code permitAll} 을 주면 그쪽이 빨개진다.
	 */
	@ParameterizedTest(name = "GET {0}")
	@ValueSource(strings = {"/api/v1/posts", "/api/v1/posts/1", "/api/v1/posts/1/comments"})
	@DisplayName("비로그인 허용 경로는 토큰 없이도 401 이 아니다")
	void 비로그인_허용_경로(String path) throws Exception {
		this.mockMvc.perform(get(path)).andExpect(notUnauthorized());
	}

	@Test
	@DisplayName("선언되지 않은 경로는 인증을 요구한다")
	void 선언되지_않은_경로는_막힌다() throws Exception {
		this.mockMvc.perform(get("/api/v1/somewhere-new"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A001"));
	}

	// --- 인프라 경로 (sp-docs/security.md §5.1.1) ---

	@Test
	@DisplayName("토큰 없이 /swagger-ui.html 은 302 로 /swagger-ui/index.html 로 간다")
	void swagger_진입점은_인증_없이_열린다() throws Exception {
		this.mockMvc.perform(get("/swagger-ui.html"))
				.andExpect(status().is3xxRedirection())
				// /login 으로 가면 폼 로그인이 살아 있거나 permitAll 이 빠진 것이다
				.andExpect(redirectedUrl("/swagger-ui/index.html"));
	}

	@Test
	@DisplayName("토큰 없이 /swagger-ui/index.html 이 200 이다")
	void swagger_ui_는_인증_없이_열린다() throws Exception {
		this.mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("토큰 없이 /v3/api-docs 가 200 과 OpenAPI 문서를 돌려준다")
	void api_docs_는_인증_없이_열린다() throws Exception {
		this.mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").exists())
				.andExpect(jsonPath("$.info.title").value("board-service API"));
	}

	@Test
	@DisplayName("/actuator/health 는 인증 없이 열린다")
	void actuator_health_는_열린다() throws Exception {
		this.mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	/**
	 * health 외의 actuator 는 <b>무토큰 401 {@code A001}, 유효 토큰 403 {@code A004}</b> 다.
	 * <b>404 가 아니다</b> — {@code exposure.include: health} 가 엔드포인트를 등록조차 하지
	 * 않지만 Security 필터 체인이 디스패치보다 먼저 거부한다 (sp-docs/security.md §5.1.1 의
	 * 측정값).
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/metrics",
			"/actuator/configprops", "/actuator/mappings"})
	@DisplayName("health 외의 actuator 경로는 무토큰 401 A001 이다")
	void actuator_는_무토큰_401(String path) throws Exception {
		this.mockMvc.perform(get(path))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A001"));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/metrics",
			"/actuator/configprops", "/actuator/mappings"})
	@DisplayName("health 외의 actuator 경로는 유효 토큰으로도 403 A004 다")
	void actuator_는_인증해도_403(String path) throws Exception {
		this.mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("A004"))
				.andExpect(jsonPath("$.error.message").value("권한이 없습니다."));
	}

	// --- 토큰 검증 (sp-docs/api-contract.md §6 §8.5) ---

	/**
	 * <b>기대값을 리터럴로 적는다.</b> 구현의 상수를 기대값 자리에 쓰면 그 상수를 자기 자신과
	 * 견주는 것이 되어 어떤 값이어도 통과한다. 발급은 auth 하나이고 검증은 member·board 둘이므로
	 * (sp-docs/api-contract.md §6) 이 값이 어긋나면 auth 가 발급한 토큰의 권한이 사라진다.
	 */
	@Test
	@DisplayName("role claim 이름이 Claim 계약의 값이다")
	void claim_계약값() {
		assertThat(RoleClaimConverter.CLAIM_ROLE)
				.as("권한 claim 의 이름이다 (sp-docs/api-contract.md §6)")
				.isEqualTo("role");
	}

	@Test
	@DisplayName("유효한 토큰이 통과하고 LoginMember 가 sub·role 로 채워진다")
	void 유효한_토큰으로_인증된다() throws Exception {
		this.mockMvc.perform(get("/test/current-member").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.nickname").doesNotExist());
	}

	@Test
	@DisplayName("LoginMember 의 필드는 accountId·role 이고 nickname 이 없다")
	void login_member_필드_목록() {
		List<String> fields = Arrays.stream(LoginMember.class.getRecordComponents())
				.map(RecordComponent::getName)
				.toList();

		assertThat(fields)
				.as("nickname 은 JWT Claim 에 없다. 작성자 닉네임은 MemberClient 로 얻는다 "
						+ "(sp-docs/api-contract.md §6)")
				.containsExactly("accountId", "role");
	}

	@Test
	@DisplayName("role = ADMIN 토큰은 ROLE_ADMIN 권한을 갖는다")
	void role_claim_이_권한이_된다() throws Exception {
		this.mockMvc.perform(get("/test/authorities").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0]").value("ROLE_ADMIN"));
	}

	@Test
	@DisplayName("Authorization 헤더가 없으면 401 A001 이다")
	void 헤더가_없으면_A001() throws Exception {
		assertAuthFailure(null, "A001", "인증이 필요합니다.");
	}

	/**
	 * RFC 6750 §2.1 의 {@code b64token} 문법을 위반한 값은 애초에 bearer 자격증명이 아니다
	 * (sp-docs/api-contract.md §8.5). Spring 의 {@code DefaultBearerTokenResolver} 가 디코더
	 * 앞에서 끊으므로 {@code A002} 가 아니라 {@code A001} 이다.
	 */
	@Test
	@DisplayName("b64token 문법을 위반한 값은 401 A001 이다")
	void 문법_위반은_A001() throws Exception {
		assertAuthFailure("Bearer not!a!token", "A001", "인증이 필요합니다.");
	}

	@Test
	@DisplayName("문법은 맞지만 JWT 가 아닌 값은 401 A002 다")
	void JWT_가_아니면_A002() throws Exception {
		assertAuthFailure("Bearer notajwt", "A002", "유효하지 않은 토큰입니다.");
	}

	@Test
	@DisplayName("서명과 어긋나게 변조된 토큰은 401 A002 다")
	void 변조된_토큰은_A002() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.tamperedToken(ACCOUNT_ID, "USER"),
				"A002", "유효하지 않은 토큰입니다.");
	}

	@Test
	@DisplayName("만료된 토큰은 401 A003 이다")
	void 만료된_토큰은_A003() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.expiredToken(ACCOUNT_ID, "USER"),
				"A003", "만료된 토큰입니다.");
	}

	/**
	 * 서명은 맞지만 Claim 계약(sp-docs/api-contract.md §6)을 어긴 토큰도 {@code A002} 다
	 * (§8.5 "그 밖의 claim 검증 실패").
	 *
	 * <p><b>board 에는 Claim 계약 검증기가 없다</b> — 계획의 B-04 검증표가 {@code iss}·
	 * {@code sub} 계약 위반을 요구하지 않고, 공개키가 auth 것 하나이므로 서명이 맞으면서 계약을
	 * 어긴 토큰은 통상 존재하지 않는다. 그래서 그 상황을 드러내는 지점은
	 * {@code CurrentMemberArgumentResolver} 의 두 가드뿐이고, <b>이 두 테스트가 그 가드를
	 * 받친다</b> — 가드를 지우면 비숫자 {@code sub} 는 500 이 되고 빠진 {@code role} 은
	 * {@code LoginMember.role = null} 로 조용히 통과한다.
	 */
	@Test
	@DisplayName("sub 가 account.id 가 아닌 토큰은 401 A002 다")
	void sub_가_숫자가_아니면_A002() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.tokenWithSubject("not-a-number", "USER"),
				"A002", "유효하지 않은 토큰입니다.");
	}

	@Test
	@DisplayName("role claim 이 없거나 공백인 토큰은 401 A002 다")
	void role_이_없으면_A002() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.tokenWithoutRole(ACCOUNT_ID),
				"A002", "유효하지 않은 토큰입니다.");
		assertAuthFailure("Bearer " + TestTokenFactory.tokenWithBlankRole(ACCOUNT_ID),
				"A002", "유효하지 않은 토큰입니다.");
	}

	/**
	 * JWT 검증 필터를 직접 만들지 않았다 (B-04 완료 기준, sp-docs/tech-stack.md §2).
	 *
	 * <p>표준 {@code BearerTokenAuthenticationFilter} 가 체인에 있고, 체인의 어느 필터도 이
	 * 서비스의 코드가 아님을 함께 본다 — 하나만 보면 표준 필터를 두고 그 옆에 자작 필터를 더해도
	 * 통과한다.
	 */
	@Test
	@DisplayName("토큰 검증은 표준 필터가 하고 체인에 자작 필터가 없다")
	void 검증_필터를_직접_만들지_않았다() {
		List<Filter> filters = this.filterChainProxy.getFilterChains().stream()
				.flatMap(chain -> chain.getFilters().stream())
				.toList();

		assertThat(filters)
				.as("표준 검증 필터가 체인에 있어야 한다 (sp-docs/tech-stack.md §2.1)")
				.hasAtLeastOneElementOfType(BearerTokenAuthenticationFilter.class);
		assertThat(filters)
				.as("JWT 필터를 직접 만들지 않는다 (B-04 완료 기준)")
				.noneMatch(filter -> filter.getClass().getName().startsWith("com.example.board"));
	}

	// --- STATELESS (sp-docs/security.md §1) ---

	@Test
	@DisplayName("세션이 생성되지 않고 세션 쿠키도 내려가지 않는다")
	void 세션이_생성되지_않는다() throws Exception {
		assertNoSession(this.mockMvc.perform(post("/api/v1/posts")).andReturn());
		assertNoSession(this.mockMvc.perform(get("/test/current-member")
				.header(HttpHeaders.AUTHORIZATION, bearer())).andReturn());
	}

	// --- CORS (sp-docs/security.md §1.1) ---

	@Test
	@DisplayName("CORS 의 메서드·헤더·allowCredentials 가 정본 값이고 * 가 없다")
	void cors_설정값이_정본과_일치한다() {
		assertThat(this.corsConfigurationSource).isInstanceOf(UrlBasedCorsConfigurationSource.class);
		CorsConfiguration configuration =
				((UrlBasedCorsConfigurationSource) this.corsConfigurationSource)
						.getCorsConfigurations().get("/**");

		assertThat(configuration).isNotNull();
		assertThat(configuration.getAllowedOrigins()).isNotEmpty().doesNotContain("*");
		assertThat(configuration.getAllowedOriginPatterns()).isNullOrEmpty();
		// sp-docs/security.md §1.1 이 고정한 다섯 값이다. 리터럴로 적는다 — 운영 코드의
		// 목록을 가져다 자기와 견주면 어떤 값이어도 통과한다.
		//
		// PUT 이 빠지면 board 의 수정 경로(sp-docs/api-contract.md §4 의
		// PUT /api/v1/posts/{id}·PUT /api/v1/comments/{id}) preflight 가 막힌다.
		// containsExactlyInAnyOrder 는 집합을 보므로 빠짐과 덧붙임을 모두 잡는다
		assertThat(configuration.getAllowedMethods())
				.containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE");
		assertThat(configuration.getAllowedHeaders())
				.containsExactlyInAnyOrder("Authorization", "Content-Type");
		assertThat(configuration.getAllowCredentials())
				.as("토큰을 Authorization 헤더로 보내므로 쿠키를 허용할 이유가 없다")
				.isFalse();
	}

	@Test
	@DisplayName("허용 오리진이 비었거나 * 가 섞이면 기동이 실패한다")
	void cors_가드가_기동을_실패시킨다() {
		SecurityConfig securityConfig = new SecurityConfig(this.securityErrorResponder);

		assertThatThrownBy(() -> securityConfig.corsConfigurationSource(List.of(ALLOWED_ORIGIN, "*")))
				.as("값이 환경에서 오므로 * 금지를 지키는 지점은 여기뿐이다")
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> securityConfig.corsConfigurationSource(List.of()))
				.as("둘을 함께 본다 — 하나만 보면 다른 쪽이 열린다")
				.isInstanceOf(IllegalStateException.class);
	}

	/** CORS 가 MVC 가 아니라 Security 체인에 있어야 preflight 가 인가 필터에 막히지 않는다. */
	@Test
	@DisplayName("화이트리스트의 오리진은 preflight 를 통과하고 그 밖의 오리진은 막힌다")
	void cors_preflight() throws Exception {
		this.mockMvc.perform(options("/api/v1/posts")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));

		this.mockMvc.perform(options("/api/v1/posts")
						.header(HttpHeaders.ORIGIN, "http://not-allowed.example")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isForbidden());
	}

	private String bearer() {
		return "Bearer " + TestTokenFactory.accessToken(ACCOUNT_ID, "ADMIN");
	}

	private void assertAuthFailure(String authorization, String code, String message) throws Exception {
		var requestBuilder = get("/test/current-member");
		if (authorization != null) {
			requestBuilder = requestBuilder.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		this.mockMvc.perform(requestBuilder)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").value(message));
	}

	private void assertNoSession(MvcResult result) {
		assertThat(result.getRequest().getSession(false))
				.as("SessionCreationPolicy.STATELESS 인데 세션이 생겼다")
				.isNull();
		assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.noneSatisfy(value -> assertThat(value).contains("JSESSIONID"));
	}

	/** 인가를 통과했는지만 본다. 업무 컨트롤러가 없으므로 그 뒤의 상태는 고정되지 않는다. */
	private static ResultMatcher notUnauthorized() {
		return result -> assertThat(result.getResponse().getStatus())
				.as("인가를 통과하지 못했다. 응답 본문: %s", result.getResponse().getContentAsString())
				.isNotEqualTo(401);
	}

}
