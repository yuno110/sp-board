package com.example.board.post.support;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 조회수 판정 비밀키의 기동 가드 — sp-docs/plan/phase2-board.md B-16 검증표의 「비밀키」 줄이다.
 * 요건의 정본은 sp-docs/tech-stack.md §4.5 다: 비었거나 설정 문자열의 UTF-8 바이트 길이가 32 보다
 * 짧으면 기동이 실패하고, Base64 로 디코드하지 않는다.
 *
 * <p><b>경계를 양쪽에서 본다</b> (sp-docs/conventions.md §9.2) — 32바이트는 기동하고 31바이트는
 * 실패한다. 다중 바이트 문자로도 같은 경계를 본다. 바이트가 아니라 문자를 세면 한글 11자(33바이트)가
 * 실패하고, Base64 로 디코드하면 32자의 Base64 문자열(24바이트로 풀린다)이 실패한다.
 *
 * <p><b>실제 바인딩으로 컨텍스트를 띄워 본다</b> — 키가 없을 때의 값과, 환경변수가 없을 때
 * {@code @ConfigurationProperties} 가 넘기는 미해석 {@code ${…}} 리터럴
 * (sp-docs/tech-stack.md §4.3 의 측정값)이 그 경로로 가드에 닿는다.
 */
class ViewCountPropertiesTest {

	/** 32자이고 Base64 로 풀면 24바이트다 — 디코드하는 구현이면 하한에 걸린다. */
	private static final String BASE64_LOOKING_32 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3";

	/**
	 * <b>시스템 환경 소스를 뺀다.</b> 환경변수 {@code VIEW_COUNT_HMAC_SECRET} 은 이름이 속성 경로의
	 * 완화 바인딩 형식과 같아 설정돼 있으면 이 키에 묶인다 — 그러면 실행하는 PC·CI 의 환경이 「키가
	 * 없다」 의 전제를 깬다.
	 */
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(context -> context.getEnvironment().getPropertySources()
					.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
			.withUserConfiguration(PropertiesConfiguration.class);

	@EnableConfigurationProperties(ViewCountProperties.class)
	static class PropertiesConfiguration {
	}

	/**
	 * <b>키는 설정 문자열의 UTF-8 바이트 그대로다</b> (sp-docs/tech-stack.md §4.5). 기동만 보면
	 * 디코드하거나 다른 문자셋으로 바꾼 키도 길이만 맞으면 통과하므로 바이트를 대조한다.
	 */
	@ParameterizedTest(name = "{0} ({1}바이트)")
	@MethodSource("acceptedSecrets")
	@DisplayName("UTF-8 로 32바이트 이상이면 기동하고 그 바이트가 키다")
	void 바이트_하한_이상이면_기동한다(String description, int bytes, String secret) {
		assertThat(secret.getBytes(UTF_8)).as("픽스처의 바이트 길이").hasSize(bytes);

		this.contextRunner.withPropertyValues("view-count.hmac-secret=" + secret)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(ViewCountProperties.class).hmacSecret())
							.as("Base64 로 디코드하지 않고 UTF-8 바이트를 그대로 쓴다")
							.isEqualTo(secret.getBytes(UTF_8));
				});
	}

	/** 받은 비밀값이 메시지에 실리지 않는 것도 본다 — 기동 실패의 메시지는 로그에 남는다. */
	@ParameterizedTest(name = "{0} ({1}바이트)")
	@MethodSource("shortSecrets")
	@DisplayName("UTF-8 로 31바이트면 기동이 실패하고 메시지에 값이 실리지 않는다")
	void 바이트_하한_미만이면_기동이_실패한다(String description, int bytes, String secret) {
		assertThat(secret.getBytes(UTF_8)).as("픽스처의 바이트 길이").hasSize(bytes);

		this.contextRunner.withPropertyValues("view-count.hmac-secret=" + secret)
				.run(context -> {
					assertStartupFailed(context, "보다 짧다");
					assertThat(context.getStartupFailure()).rootCause()
							.hasMessageNotContaining(secret);
				});
	}

	@Test
	@DisplayName("Base64 로 풀리는 32자 픽스처가 실제로 32바이트보다 짧게 풀린다")
	void Base64_픽스처는_디코드하면_짧다() {
		assertThat(Base64.getDecoder().decode(BASE64_LOOKING_32))
				.as("이 픽스처가 디코드 여부를 가르려면 풀었을 때 하한보다 짧아야 한다")
				.hasSizeLessThan(ViewCountProperties.MIN_HMAC_SECRET_BYTES);
	}

	@Test
	@DisplayName("키가 없으면 기동이 실패한다")
	void 키가_없으면_기동이_실패한다() {
		this.contextRunner.run(context -> assertStartupFailed(context, "비어 있다"));
	}

	@Test
	@DisplayName("빈 값이면 기동이 실패한다")
	void 빈_값이면_기동이_실패한다() {
		this.contextRunner.withPropertyValues("view-count.hmac-secret=")
				.run(context -> assertStartupFailed(context, "비어 있다"));
	}

	/**
	 * 공백뿐인 값은 빈 값으로 본다. <b>하한 길이로 둔다</b> — 그래야 길이 검사가 아니라 빈 값 검사가
	 * 막는 것을 본다. {@code withPropertyValues} 는 값의 앞뒤 공백을 지워 위의 빈 값 테스트와
	 * 같아지므로 속성 소스로 직접 넣는다.
	 */
	@Test
	@DisplayName("공백뿐인 값이면 길이와 무관하게 기동이 실패한다")
	void 공백뿐인_값이면_기동이_실패한다() {
		String blank = " ".repeat(ViewCountProperties.MIN_HMAC_SECRET_BYTES);

		this.contextRunner
				.withInitializer(context -> context.getEnvironment().getPropertySources()
						.addFirst(new MapPropertySource("blank", Map.of("view-count.hmac-secret", blank))))
				.run(context -> assertStartupFailed(context, "비어 있다"));
	}

	/**
	 * 환경변수가 없을 때 {@code ${…}} 리터럴이 그대로 들어온다. <b>이름을 32바이트보다 길게 둔다</b>
	 * — 그래야 길이 검사가 아니라 리터럴 검사가 막는 것을 본다. 운영의
	 * {@code ${VIEW_COUNT_HMAC_SECRET}} 는 25바이트라 길이 검사에도 걸리지만 그것은 이름 길이에
	 * 기댄 결과다.
	 */
	@Test
	@DisplayName("환경변수가 없어 placeholder 가 리터럴로 남으면 길이와 무관하게 기동이 실패한다")
	void 미해석_placeholder_면_기동이_실패한다() {
		String literal = "${B16_UNSET_VIEW_COUNT_HMAC_SECRET_FOR_THIS_TEST}";
		assertThat(literal.getBytes(UTF_8))
				.as("길이 검사로는 걸리지 않는 리터럴이어야 한다")
				.hasSizeGreaterThanOrEqualTo(ViewCountProperties.MIN_HMAC_SECRET_BYTES);

		this.contextRunner.withPropertyValues("view-count.hmac-secret=" + literal)
				.run(context -> assertStartupFailed(context, "placeholder"));
	}

	static Stream<Arguments> acceptedSecrets() {
		return Stream.of(
				Arguments.of("ASCII 32자 — Base64 로도 읽힌다", 32, BASE64_LOOKING_32),
				Arguments.of("한글 10자 + ASCII 2자", 32, "가".repeat(10) + "ab"),
				Arguments.of("한글 11자", 33, "가".repeat(11)));
	}

	static Stream<Arguments> shortSecrets() {
		return Stream.of(
				Arguments.of("ASCII 31자", 31, BASE64_LOOKING_32.substring(0, 31)),
				Arguments.of("한글 10자 + ASCII 1자", 31, "가".repeat(10) + "a"));
	}

	private static void assertStartupFailed(AssertableApplicationContext context, String reason) {
		assertThat(context).hasFailed();
		assertThat(context.getStartupFailure())
				.rootCause()
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("view-count.hmac-secret")
				.hasMessageContaining(reason);
	}

}
