package com.example.board.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code JwtDecoder} 가 쓰는 <b>claim 검증기 집합을 고정한다</b>
 * (sp-docs/plan/phase1.md §6 B-09. B-04 에서 넘어온 것이다).
 *
 * <p><b>왜 집합을 고정해야 하나</b> — {@link SecurityErrorResponder} 는 예외 <i>타입</i>만 보고
 * {@code A002}(유효하지 않음)와 {@code A003}(만료)를 가른다
 * (sp-docs/api-contract.md §8.1 §8.5). {@code NimbusJwtDecoder} 는 서명을 먼저 보고 claim 을
 * 나중에 보므로, 서명을 통과한 뒤의 {@link JwtValidationException} 은 <b>검증기 결과</b>로
 * 보고된 claim 실패 하나뿐이다. 그 검증기가 {@code JwtTimestampValidator} 와
 * {@code X509CertificateThumbprintValidator} 둘이고 후자가 {@code cnf} 없는 토큰을
 * 통과시키므로, {@code JwtValidationException} = 만료가 성립한다. 그 전제는
 * {@code SecurityErrorResponder.failureCode} 의 javadoc 이 적어 두었다.
 *
 * <p><b>검증기가 하나 더 붙으면 그 실패는 결과로 보고되어 {@code JwtValidationException} 이
 * 되고, 만료가 아닌 사유가 조용히 {@code A003}(만료)으로 나간다.</b> 예컨대
 * {@code aud}·{@code iss} 검증기를 빈으로 하나 추가하는 것만으로 그렇게 된다 — 스타터가
 * 컨텍스트의 {@code OAuth2TokenValidator<Jwt>} 빈을 모아 기본 검증기 뒤에 붙이기 때문이다.
 *
 * <p><b>이 테스트는 주석이 아니라 단언으로 수량을 말한다.</b> sp-docs/conventions.md §9.5 가
 * 금지하는 것은 주석의 수량 주장이고, 이유는 다음 항목이 늘렸을 때 걸러 줄 기계장치가 없다는
 * 것이다. 여기서는 수량이 곧 검증 대상이고 늘어나면 단언이 깨진다 — 기계장치가 이것이다.
 * <b>깨지는 것이 의도다.</b> 깨졌을 때 할 일은 아래 목록을 늘리는 것이 아니라
 * {@code SecurityErrorResponder.failureCode} 를 함께 고치는 것이다.
 *
 * <p>AU-11 이 auth({@code JwtConfigTest}), M-11 이 member({@code JwtDecoderValidatorsTest})에서
 * 같은 것을 못박았다. <b>board 는 member 와 한 가지가 다르다</b> — member 에는 claim 계약
 * 검증기({@code ClaimContractValidator})가 빈으로 붙지만 board 에는 그런 빈이 없다. 그래서
 * 기동된 디코더의 집합이 <b>기본 둘과 정확히 같아야 한다.</b> board 가 Claim 계약 위반을
 * 드러내는 자리는 {@link CurrentMemberArgumentResolver} 의 가드이고 그것은 디코더 밖이다.
 *
 * <p>{@code @DynamicPropertySource} 로 공개키를 덮지 않는다 — 토큰을 서명하지 않으므로 커밋된
 * 실제 공개키를 그대로 읽어도 되고, 그래야 컨텍스트를 하나 더 띄우지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class JwtDecoderValidatorsTest {

	/**
	 * {@code JwtValidators.createDefault()} 가 거는 검증기들. {@code JwtDecoder} 를
	 * {@code NimbusJwtDecoder.withPublicKey(...)} 로 만들면 이것이 기본값이 되고, 스타터의
	 * 자동구성이 그 경로를 쓴다 (sp-docs/tech-stack.md §2.1).
	 *
	 * <p><b>클래스 리터럴이 아니라 이름으로 적는다</b> — {@code X509CertificateThumbprintValidator}
	 * 는 Spring Security 안에서 package-private 이라 테스트가 참조할 수 없다.
	 */
	private static final List<String> DEFAULT_CLAIM_VALIDATORS = List.of(
			"org.springframework.security.oauth2.jwt.JwtTimestampValidator",
			"org.springframework.security.oauth2.jwt.X509CertificateThumbprintValidator");

	@Autowired
	private JwtDecoder jwtDecoder;

	/**
	 * 기본 집합 자체를 고정한다. Spring Security 버전업으로 {@code createDefault()} 가 검증기를
	 * 하나 더 걸면 여기서 먼저 깨진다 — 우리 코드를 고치지 않아도 등식이 깨질 수 있는 경로다.
	 */
	@Test
	@DisplayName("JwtValidators.createDefault() 가 만료만 보는 검증기 둘을 건다")
	void 기본_검증기_집합이_고정되어_있다() {
		assertThat(flatten(JwtValidators.createDefault()).stream()
				.map(each -> each.getClass().getName()).toList())
				.as("이 집합이 곧 'JwtValidationException = 만료'의 근거다 "
						+ "(sp-docs/api-contract.md §8.5)")
				.containsExactlyInAnyOrderElementsOf(DEFAULT_CLAIM_VALIDATORS);
	}

	/**
	 * 실제로 기동된 디코더의 집합. <b>기본 둘뿐이어야 한다.</b>
	 *
	 * <p><b>크기를 세지 않는다.</b> 하나를 빼고 다른 하나를 넣으면 크기 단언은 통과한다 —
	 * 이름으로 집합을 고정한다.
	 */
	@Test
	@DisplayName("기동된 JwtDecoder 의 claim 검증기가 기본 둘뿐이다")
	void 기동된_디코더의_검증기_집합이_고정되어_있다() {
		assertThat(this.jwtDecoder)
				.as("검증기 집합을 읽으려면 NimbusJwtDecoder 여야 한다")
				.isInstanceOf(NimbusJwtDecoder.class);

		assertThat(claimValidatorsOf(this.jwtDecoder))
				.as("집합이 바뀌면 SecurityErrorResponder.failureCode 의 "
						+ "'JwtValidationException = 만료' 전제가 깨져 만료가 아닌 사유가 A003 으로 나간다. "
						+ "이 목록을 늘리지 말고 failureCode 를 함께 고친다")
				.containsExactlyInAnyOrderElementsOf(DEFAULT_CLAIM_VALIDATORS);
	}

	/**
	 * 디코더가 실제로 거는 claim 검증기들의 클래스 이름.
	 *
	 * <p>{@code jwtValidator} 는 공개 접근자가 없어 리플렉션으로 읽는다. 중첩된
	 * {@code DelegatingOAuth2TokenValidator} 를 펼치는 것은 스타터가 "기본값 + 컨텍스트의
	 * 검증기 빈들"을 새 {@code Delegating} 으로 감쌀 수 있기 때문이다 — 펼치지 않으면 집합이
	 * 늘었는데도 한 겹만 보인다.
	 */
	private List<String> claimValidatorsOf(JwtDecoder decoder) {
		Object validator = ReflectionTestUtils.getField(decoder, "jwtValidator");
		return flatten(validator).stream().map(each -> each.getClass().getName()).toList();
	}

	@SuppressWarnings("unchecked")
	private List<Object> flatten(Object validator) {
		if (!(validator instanceof DelegatingOAuth2TokenValidator<?>)) {
			return List.of(validator);
		}
		Collection<OAuth2TokenValidator<?>> delegates =
				(Collection<OAuth2TokenValidator<?>>) ReflectionTestUtils.getField(validator, "tokenValidators");
		return delegates.stream().flatMap(each -> flatten(each).stream()).toList();
	}

}
