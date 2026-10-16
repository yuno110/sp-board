package com.example.board.global.config;

import com.example.board.global.security.RoleClaimConverter;
import com.example.board.global.security.SecurityErrorResponder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 경로별 인가. 업무 API 는 sp-docs/api-contract.md §4, 인프라 경로는 sp-docs/security.md §5.1.1
 * 이 정본이다.
 *
 * <p><b>이 서비스는 토큰을 검증만 한다.</b> 개인키도 서명 기능도 없다 — 발급 주체는 auth 하나다
 * (sp-docs/security.md §2, sp-docs/adr/0012). 공개키 검증기({@code JwtDecoder})는
 * {@code spring.security.oauth2.resourceserver.jwt.public-key-location} 으로 스타터가
 * 자동구성하므로 여기서 만들지 않고, <b>JWT 필터를 직접 만들지 않는다</b>
 * (sp-docs/tech-stack.md §2 §2.1).
 *
 * <p><b>선언하지 않은 경로는 401 이 된다.</b> {@code spring-boot-starter-security} 가 있으면 전
 * 경로에 인증이 요구되므로, 공개 경로를 빠뜨리면 조용히 막힌다 (sp-docs/security.md §5.1.1).
 * 그래서 {@code anyRequest()} 를 {@code authenticated()} 로 둔다 — 새 경로가 생겼을 때 열린
 * 상태가 아니라 막힌 상태가 기본값이 되도록.
 *
 * <p><b>{@code /internal/**} 체인이 없다.</b> board 는 내부 API 를 제공하지 않고 호출만 한다
 * ({@link com.example.board.client.MemberClient}, sp-docs/security.md §6). 제공 측의
 * {@code X-Internal-Api-Key} 검증 필터는 member 에 있다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final SecurityErrorResponder securityErrorResponder;

	@Bean
	public SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			CorsConfigurationSource corsConfigurationSource,
			JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

		http
				// Stateless REST API 이므로 CSRF 토큰을 쓰지 않는다 (sp-docs/security.md §1)
				.csrf(AbstractHttpConfigurer::disable)
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				// 토큰 인증만 쓴다. 폼 로그인을 남겨두면 인가 실패가 /login 리다이렉트로 보인다
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				// 로그인·로그아웃은 auth-service 의 경로다 (sp-docs/api-contract.md §2.1).
				// Security 기본 /logout 엔드포인트를 열지 않는다
				.logout(AbstractHttpConfigurer::disable)
				// 정본이 이 값을 이름으로 지정한다 (sp-docs/security.md §1)
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// 인가 실패 시 원 요청을 세션에 저장하지 않는다. 저장하면 STATELESS 인데도
				// 세션이 생긴다
				.requestCache(RequestCacheConfigurer::disable)

				.authorizeHttpRequests(auth -> auth
						// --- 인프라 경로 (sp-docs/security.md §5.1.1) ---
						// prod 프로파일에서는 springdoc 자체가 꺼져 경로가 사라진다 — 경로별
						// 인가는 프로파일을 모르므로 끄는 지점은 application-prod.yml 이고,
						// 두 경로가 그 프로파일에서 404 인 것은 ProdProfileTest 가 본다
						.requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll()
						.requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
						.requestMatchers("/actuator/health").permitAll()
						// health 외의 actuator 는 노출 자체를 막는다. 무토큰은 401 A001,
						// 유효 토큰은 403 A004 다 — 404 가 아니다 (§5.1.1 의 측정값)
						.requestMatchers("/actuator/**").denyAll()

						// --- 게시글·댓글 (sp-docs/api-contract.md §4) ---
						// 조회는 비로그인 허용이고 생성·수정·삭제는 인증이 필요하다. 소유자
						// 검증(본인 글인가)은 여기가 아니라 Service 계층이다 — 리소스를
						// 조회해야 판정할 수 있다 (sp-docs/security.md §5.1 §5.2)
						.requestMatchers(HttpMethod.GET, "/api/v1/posts").permitAll()
						// ★ /posts/me 를 /posts/{id} 보다 먼저 선언한다. authorizeHttpRequests
						// 는 선언 순서대로 평가하므로, {id} 가 먼저면 permitAll 이 /posts/me 를
						// 먼저 잡아 내가 쓴 글 목록이 인증 없이 디스패치된다
						// (sp-docs/api-contract.md §4). 이 경로의 핸들러는
						// PostController.getMyPosts 다.
						//
						// 이 순서를 잡는 것은 PostControllerTest.내_글_목록은_필터_체인이_막는다
						// 다. 상태 코드로는 관측되지 않는다 — 뒤집어도 핸들러의
						// @CurrentMember 가 익명 principal 을 보고 던져 응답이 똑같이 401
						// A001 이 되므로, 그 테스트는 거부한 계층(핸들러가 선택됐는지)을 본다.
						// 뒤집은 변이에서 그 하나만 빨개지는 것을 측정했다
						.requestMatchers(HttpMethod.GET, "/api/v1/posts/me").authenticated()
						.requestMatchers(HttpMethod.GET, "/api/v1/posts/{id}").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/posts/{postId}/comments").permitAll()

						.requestMatchers(HttpMethod.POST, "/api/v1/posts").authenticated()
						.requestMatchers(HttpMethod.PUT, "/api/v1/posts/{id}").authenticated()
						.requestMatchers(HttpMethod.DELETE, "/api/v1/posts/{id}").authenticated()
						.requestMatchers(HttpMethod.POST, "/api/v1/posts/{postId}/comments").authenticated()
						.requestMatchers(HttpMethod.PUT, "/api/v1/comments/{id}").authenticated()
						.requestMatchers(HttpMethod.DELETE, "/api/v1/comments/{id}").authenticated()

						.anyRequest().authenticated())

				// auth 가 발급한 토큰을 공개키로 검증한다. JwtDecoder 는 스타터가 자동구성한다
				// (sp-docs/tech-stack.md §2.1)
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
						.authenticationEntryPoint(this.securityErrorResponder)
						.accessDeniedHandler(this.securityErrorResponder));

		return http.build();
	}

	/**
	 * {@code role} claim 을 권한으로 바꿔 {@code Authentication} 에 싣는다
	 * (sp-docs/security.md §5.1). 변환 규칙은 {@link RoleClaimConverter} 에 있다.
	 */
	@Bean
	public JwtAuthenticationConverter jwtAuthenticationConverter(RoleClaimConverter roleClaimConverter) {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(roleClaimConverter);
		return converter;
	}

	/**
	 * CORS 화이트리스트. <b>{@code *} 를 쓰지 않는다</b> (sp-docs/security.md §1 §1.1).
	 *
	 * <p><b>허용 오리진은 환경이 정한다</b> (§1.1). 그래서 값을 코드나 커밋되는 설정에 박지 않고
	 * {@code cors.allowed-origins} 로 주입받는다. <b>기본값을 두지 않으므로 값이 없으면 기동이
	 * 실패한다</b> — 정책값에 임의 기본값을 두면 그 값이 정해진 정책인지 임시값인지 구분할 수
	 * 없고 운영에 그대로 나간다 (sp-docs/tech-stack.md §4.3).
	 *
	 * <p>비었거나 {@code *} 가 섞여 들어오면 기동을 실패시킨다. <b>값이 환경에서 오므로 §1.1 의
	 * 금지를 지키는 지점은 이 가드뿐이다.</b>
	 *
	 * <p>허용 메서드·헤더·{@code allowCredentials} 는 §1.1 이 정본으로 고정한 값이다.
	 *
	 * <p><b>CORS 는 {@code WebConfig} 가 아니라 여기에 둔다.</b> preflight(OPTIONS)는 인가
	 * 필터보다 앞에서 처리돼야 한다 — MVC 쪽에만 두면 preflight 가 401 로 막힌다 (§1.1).
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource(
			@Value("${cors.allowed-origins}") List<String> allowedOrigins) {

		if (allowedOrigins.isEmpty()) {
			throw new IllegalStateException(
					"cors.allowed-origins 가 비어 있다. 허용 오리진을 외부에서 주입한다 "
							+ "(sp-docs/security.md §1.1).");
		}
		allowedOrigins.stream()
				.filter(origin -> origin.contains("*"))
				.findFirst()
				.ifPresent(origin -> {
					throw new IllegalStateException(
							"CORS 허용 오리진에 와일드카드를 쓸 수 없다: " + origin
									+ " (sp-docs/security.md §1.1)");
				});

		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		// sp-docs/security.md §1.1 이 고정한 목록이다. board 의 수정 경로가 PUT 이므로
		// (sp-docs/api-contract.md §4) PUT 이 들어 있어야 수정 preflight 가 통과한다.
		// OPTIONS 는 preflight 처리에 쓰여 목록에 넣지 않는다
		configuration.setAllowedMethods(List.of(
				HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
				HttpMethod.PATCH.name(), HttpMethod.DELETE.name()));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		// 토큰을 Authorization 헤더로 보내므로 쿠키가 필요 없다 (sp-docs/security.md §1.1)
		configuration.setAllowCredentials(false);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

}
