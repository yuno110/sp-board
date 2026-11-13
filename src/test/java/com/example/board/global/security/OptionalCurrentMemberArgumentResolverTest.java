package com.example.board.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.support.TestTokenFactory;
import com.example.securityprobe.CurrentMemberProbeController;
import com.example.securityprobe.OptionalCurrentMemberProbeController;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 선택적 리졸버 — sp-docs/plan/phase2-board.md B-16 검증표의 「선택적 리졸버」 줄들이다.
 * 규칙의 정본은 sp-docs/api-contract.md §4.8, sp-docs/requirements/board.md §9.1,
 * sp-docs/tech-stack.md §2.1 의 2026-11-11 {@code D-18} 블록인용이다.
 *
 * <p><b>테스트 전용 필터 체인으로 프로브 경로 하나만 연다.</b> 운영 {@code SecurityConfig} 는
 * 선언하지 않은 경로를 {@code anyRequest().authenticated()} 로 막으므로 토큰 없는 요청이 리졸버에
 * 닿기 전에 401 이 된다 — 계획이 2026-11-13 코드 읽기로 그렇게 적었고, 운영 설정에 테스트 경로를
 * 열지 않는다. 이 체인의 인증 부분은 운영 체인이 상세 조회({@code GET /api/v1/posts/{id}})에 주는
 * 것과 같다 — {@code permitAll} 에 운영과 같은 리소스 서버 빈({@code JwtAuthenticationConverter}·
 * {@code SecurityErrorResponder})이다. 그래야 리졸버에 닿는 요청이 운영과 같다.
 *
 * <p><b>같은 claim 검사인지는 응답을 대조해 본다.</b> 같은 토큰을 {@code @CurrentMember} 프로브에
 * 보내 받은 401 본문과 바이트로 견준다 — 검사가 한쪽에서만 바뀌면 그 대조가 갈린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OptionalCurrentMemberArgumentResolverTest {

	/** 픽스처 {@code accountId}. 이 테스트는 DB 에 쓰지 않으므로 값은 claim 으로만 쓰인다. */
	private static final long ACCOUNT_ID = 501L;

	@DynamicPropertySource
	static void jwtPublicKey(DynamicPropertyRegistry registry) {
		TestTokenFactory.registerPublicKeyLocation(registry);
	}

	@TestConfiguration
	static class ProbeConfiguration {

		@Bean
		OptionalCurrentMemberProbeController optionalCurrentMemberProbeController() {
			return new OptionalCurrentMemberProbeController();
		}

		/** 대조군. 운영 체인의 {@code anyRequest().authenticated()} 아래에 있다. */
		@Bean
		CurrentMemberProbeController currentMemberProbeController() {
			return new CurrentMemberProbeController();
		}

		/**
		 * 프로브 경로만 잡는 체인. 운영 체인은 모든 요청을 잡으므로 이 체인이 먼저 평가되도록
		 * 가장 높은 우선순위를 준다.
		 */
		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		SecurityFilterChain optionalMemberProbeChain(
				HttpSecurity http,
				JwtAuthenticationConverter jwtAuthenticationConverter,
				SecurityErrorResponder securityErrorResponder) throws Exception {

			http
					.securityMatcher(OptionalCurrentMemberProbeController.PATH)
					.csrf(AbstractHttpConfigurer::disable)
					.sessionManagement(session -> session
							.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
					.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
					.oauth2ResourceServer(oauth2 -> oauth2
							.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
							.authenticationEntryPoint(securityErrorResponder)
							.accessDeniedHandler(securityErrorResponder));
			return http.build();
		}

	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OptionalCurrentMemberArgumentResolver optionalCurrentMemberArgumentResolver;

	@Test
	@DisplayName("토큰이 없으면 null 이다")
	void 토큰이_없으면_null() throws Exception {
		this.mockMvc.perform(get(OptionalCurrentMemberProbeController.PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.anonymous").value(true))
				.andExpect(jsonPath("$.accountId").doesNotExist());
	}

	@ParameterizedTest(name = "role = {0}")
	@ValueSource(strings = {"USER", "ADMIN"})
	@DisplayName("유효한 토큰이면 그 계정과 역할이다")
	void 유효한_토큰이면_그_계정과_역할(String role) throws Exception {
		this.mockMvc.perform(get(OptionalCurrentMemberProbeController.PATH)
						.header(HttpHeaders.AUTHORIZATION,
								"Bearer " + TestTokenFactory.accessToken(ACCOUNT_ID, role)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.anonymous").value(false))
				.andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
				.andExpect(jsonPath("$.role").value(role));
	}

	/**
	 * 서명은 맞고 Claim 계약(sp-docs/api-contract.md §6)을 어긴 토큰은 필터 체인을 지나 리졸버에
	 * 닿는다. <b>{@code null} 로 낮추지 않고 401 {@code A002}</b> 다 — 낮추면 계약 밖 토큰이
	 * 익명으로 통과한다 (sp-docs/tech-stack.md §2.1).
	 *
	 * <p><b>거부한 계층을 함께 본다.</b> 해석된 예외가 {@link BusinessException} 이면 리졸버가
	 * 막은 것이다 — 필터 체인이 막았다면 디스패치가 없어 비어 있다.
	 *
	 * <p><b>본문을 {@code @CurrentMember} 의 응답과 바이트로 대조한다.</b> 비교 앞에 기준이 비지
	 * 않았는지 먼저 본다 — 빈 본문끼리는 어떤 바이트 비교도 통과한다.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("contractViolations")
	@DisplayName("claim 이 계약 밖인 토큰은 401 A002 이고 @CurrentMember 와 같은 응답이다")
	void claim_계약_위반은_A002(String violation, String token) throws Exception {
		MvcResult optional = this.mockMvc.perform(get(OptionalCurrentMemberProbeController.PATH)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A002"))
				.andExpect(jsonPath("$.error.message").value("유효하지 않은 토큰입니다."))
				.andReturn();

		assertThat(optional.getResolvedException())
				.as("리졸버가 막아야 한다 — 필터 체인이 막았다면 이 토큰은 서명 검증에서 떨어진 것이다")
				.isInstanceOf(BusinessException.class);

		byte[] required = this.mockMvc.perform(get("/test/current-member")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		assertThat(required)
				.as("빈 본문끼리는 어떤 바이트 비교도 통과한다 — 비교의 기준이 비어 있으면 안 된다")
				.isNotEmpty();
		assertThat(optional.getResponse().getContentAsByteArray())
				.as("@CurrentMember 리졸버와 같은 claim 검사여야 한다 (sp-docs/tech-stack.md §2.1)")
				.isEqualTo(required);
	}

	/**
	 * 인증이 아예 없는 상태는 「토큰이 없다」 가 아니다 — 익명 인증을 세우는 필터를 거치지 않아
	 * 토큰이 있었는지 알 수 없다. {@code null} 로 메우지 않고 {@code A001} 로 드러낸다.
	 *
	 * <p>필터 체인을 지난 요청은 익명 인증 필터 때문에 익명이든 JWT 든 인증을 가지므로 HTTP 로는
	 * 이 상태가 오지 않는다. 그래서 리졸버를 직접 부른다.
	 */
	@Test
	@DisplayName("인증이 없는 상태는 null 로 메우지 않고 A001 이다")
	void 인증이_없는_상태는_null_로_메우지_않는다() {
		SecurityContextHolder.clearContext();
		try {
			assertThatThrownBy(() -> this.optionalCurrentMemberArgumentResolver
					.resolveArgument(null, null, null, null))
					.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
							.isEqualTo(ErrorCode.UNAUTHORIZED));
		} finally {
			SecurityContextHolder.clearContext();
		}
	}

	/** 검증표의 {@code sub} 누락·숫자 아님, {@code role} 누락·열거 밖. 공백 {@code role} 은 누락과 같은 판정이다. */
	static Stream<Arguments> contractViolations() {
		return Stream.of(
				Arguments.of("sub 누락", TestTokenFactory.tokenWithoutSubject("USER")),
				Arguments.of("sub 가 숫자 아님", TestTokenFactory.tokenWithSubject("not-a-number", "USER")),
				Arguments.of("role 누락", TestTokenFactory.tokenWithoutRole(ACCOUNT_ID)),
				Arguments.of("role 공백", TestTokenFactory.tokenWithBlankRole(ACCOUNT_ID)),
				Arguments.of("role 이 열거 밖", TestTokenFactory.accessToken(ACCOUNT_ID, "BOGUS")));
	}

}
