package com.example.board.support;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 검증 테스트가 쓸 토큰을 만든다.
 *
 * <p><b>이 서비스에는 개인키가 없다</b> — 발급 주체는 auth 하나다 (sp-docs/security.md §2 §3).
 * {@code src/main/resources/jwt-public.pem} 은 실제 공개키이고 짝이 되는 개인키는 auth 가
 * 가지므로, board 의 테스트는 그 키로 토큰을 서명할 수 없다. 그래서 테스트 JVM 안에서 키
 * 페어를 한 번 만들고 공개키를 임시 파일로 써서
 * {@code spring.security.oauth2.resourceserver.jwt.public-key-location} 을 덮는다. 검증 측이
 * 테스트용 키 페어로 자체 검증하는 것은 계획이 정한 방식이고, 실제 키 교환과 auth 가 발급한
 * 토큰의 수용은 I-01 이 확인한다 (sp-docs/plan/phase1.md §1).
 *
 * <p>서명에는 {@code NimbusJwtEncoder} 를 쓴다. 검증 스타터가 {@code oauth2-jose} 를 추이로
 * 끌고 오므로 클래스패스에 있다(sp-docs/tech-stack.md §2.2 의 측정값). <b>main 쪽은 이것을
 * 쓰지 않는다</b> — 서명 의존성을 직접 선언하지 않고 개인키도 두지 않는 것이 경계다
 * (sp-docs/tech-stack.md §3.2).
 */
public final class TestTokenFactory {

	/**
	 * sp-docs/api-contract.md §6 이 고정한 발급자와 권한 claim 이름.
	 *
	 * <p><b>리터럴로 적는다.</b> 운영 코드의 상수로 서명하면 서명 측과 검증 측이 같은 값을 보게
	 * 되어 "유효한 토큰"의 정의가 구현과 함께 움직인다 — 그 상수가 계약값에서 벗어나도 여기서
	 * 만든 토큰은 계속 통과한다. 리터럴이면 그 어긋남이 토큰을 쓰는 테스트에서 드러난다.
	 */
	private static final String CONTRACT_ISSUER = "auth-service";
	private static final String CONTRACT_CLAIM_ROLE = "role";

	/** sp-docs/security.md §1 의 Access Token 만료. */
	private static final Duration ACCESS_TOKEN_VALIDITY = Duration.ofMinutes(30);

	private static final KeyPair KEY_PAIR = generateKeyPair();
	private static final Path PUBLIC_KEY_FILE = writePublicKeyPem(KEY_PAIR);
	private static final JwtEncoder ENCODER = createEncoder(KEY_PAIR);

	private TestTokenFactory() {
	}

	/**
	 * {@code @SpringBootTest} 가 {@code @DynamicPropertySource} 에서 호출한다. 호출하지 않으면
	 * {@code application.yml} 의 실제 공개키가 쓰이므로 여기서 만든 토큰의 서명이 맞지 않는다.
	 */
	public static void registerPublicKeyLocation(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
				() -> "file:" + PUBLIC_KEY_FILE.toAbsolutePath());
	}

	/** sp-docs/api-contract.md §6 의 Claim 을 모두 갖춘 유효한 토큰. */
	public static String accessToken(long accountId, String role) {
		Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		return sign(String.valueOf(accountId), role, issuedAt, issuedAt.plus(ACCESS_TOKEN_VALIDITY));
	}

	/**
	 * 만료된 토큰.
	 *
	 * <p>{@code exp} 를 한 시간 전으로 둔다. Spring 의 기본 clock skew 가 60초이므로
	 * (sp-docs/security.md §5.3) 몇 초 전으로 두면 여전히 통과한다.
	 */
	public static String expiredToken(long accountId, String role) {
		Instant expiresAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofHours(1));
		return sign(String.valueOf(accountId), role,
				expiresAt.minus(ACCESS_TOKEN_VALIDITY), expiresAt);
	}

	/**
	 * {@code sub} 가 {@code account.id} 의 문자열이 아닌 토큰 (sp-docs/api-contract.md §6).
	 *
	 * <p>서명은 맞으므로 인증 단계를 통과한다 — board 에는 Claim 계약 검증기가 없다. 해석
	 * 실패를 드러내는 지점은 {@code CurrentMemberArgumentResolver} 의 가드뿐이다.
	 */
	public static String tokenWithSubject(String subject, String role) {
		Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		return sign(subject, role, issuedAt, issuedAt.plus(ACCESS_TOKEN_VALIDITY));
	}

	/** {@code role} claim 이 없는 토큰 (§6). {@code role} 에 {@code null} 을 주면 빠진다. */
	public static String tokenWithoutRole(long accountId) {
		Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		return sign(String.valueOf(accountId), null, issuedAt, issuedAt.plus(ACCESS_TOKEN_VALIDITY));
	}

	/** {@code role} claim 이 공백 문자열인 토큰 (§6). */
	public static String tokenWithBlankRole(long accountId) {
		Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		return sign(String.valueOf(accountId), "   ", issuedAt, issuedAt.plus(ACCESS_TOKEN_VALIDITY));
	}

	/**
	 * payload 의 {@code sub} 를 바꿔 서명과 어긋나게 만든 토큰.
	 *
	 * <p><b>조립한 값이다.</b> 서명을 깨뜨리는 것이 목적이므로 발급 경로로는 만들 수 없다
	 * (sp-docs/conventions.md §9.2). 유효한 토큰의 payload 만 갈아 끼우고 서명은 그대로 둔다.
	 */
	public static String tamperedToken(long accountId, String role) {
		String[] parts = accessToken(accountId, role).split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), UTF_8);
		String tampered = payload.replace(
				"\"sub\":\"" + accountId + "\"", "\"sub\":\"" + (accountId + 1) + "\"");
		if (tampered.equals(payload)) {
			// 바뀌지 않았으면 서명이 여전히 맞는 토큰이 되어 테스트가 조용히 무의미해진다
			throw new IllegalStateException("payload 를 변조하지 못했다: sub 표현이 바뀌었다");
		}
		return parts[0] + "."
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(tampered.getBytes(UTF_8))
				+ "." + parts[2];
	}

	private static String sign(String subject, String role, Instant issuedAt, Instant expiresAt) {
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.subject(subject)
				.issuer(CONTRACT_ISSUER)
				.issuedAt(issuedAt)
				.expiresAt(expiresAt);
		if (role != null) {
			claims.claim(CONTRACT_CLAIM_ROLE, role);
		}

		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
		return ENCODER.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

	private static KeyPair generateKeyPair() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			// sp-docs/tech-stack.md §4.2 의 rsa_keygen_bits:2048 과 같다
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("RSA 키 페어를 만들 수 없다", e);
		}
	}

	/**
	 * 공개키를 PEM 으로 임시 디렉터리에 쓴다.
	 *
	 * <p>{@code getEncoded()} 가 X.509 SubjectPublicKeyInfo 를 주므로 {@code openssl rsa -pubout}
	 * 의 결과와 같은 형식이고(sp-docs/tech-stack.md §4.2), 스타터의 PEM 파싱 경로가 실제와
	 * 같이 돈다.
	 */
	private static Path writePublicKeyPem(KeyPair keyPair) {
		String pem = "-----BEGIN PUBLIC KEY-----\n"
				+ Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(keyPair.getPublic().getEncoded())
				+ "\n-----END PUBLIC KEY-----\n";
		try {
			// 저장소 밖(임시 디렉터리)에 둔다. 테스트 JVM 이 끝나면 지운다
			Path file = Files.createTempFile("sp-board-test-public-key", ".pem");
			file.toFile().deleteOnExit();
			Files.writeString(file, pem);
			return file;
		} catch (IOException e) {
			throw new IllegalStateException("테스트 공개키 파일을 쓸 수 없다", e);
		}
	}

	private static JwtEncoder createEncoder(KeyPair keyPair) {
		try {
			RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
					.privateKey((RSAPrivateKey) keyPair.getPrivate())
					.keyIDFromThumbprint()
					.build();
			return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)));
		} catch (JOSEException e) {
			throw new IllegalStateException("테스트 서명 키를 만들 수 없다", e);
		}
	}

}
