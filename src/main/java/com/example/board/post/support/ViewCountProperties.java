package com.example.board.post.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 조회수 판정의 설정 {@code view-count.hmac-secret}·{@code view-count.cleanup-interval} 을 묶고,
 * 비밀키를 기동 시점에 검사한다 (sp-docs/tech-stack.md §4.5).
 *
 * <p><b>비밀키는 기본값이 없고</b>, 비었거나 설정 문자열의 <b>UTF-8 바이트</b> 길이가 32 보다
 * 짧으면 기동이 실패한다 (§4.5, sp-docs/security.md §9). <b>Base64 로 디코드하지 않는다</b> — 키는
 * 그 바이트 그대로다. 32 는 HMAC-SHA256 의 출력 길이이고 그보다 짧은 키는 강도를 낮춘다 (§4.5).
 * 키는 비로그인 조회자의 접속 주소를 HMAC 으로 가명처리하는 데만 쓴다 (sp-docs/security.md §9).
 *
 * <p><b>그 실패는 이 생성자가 만든다.</b> {@code @ConfigurationProperties} 로 묶으면 환경변수가
 * 없을 때 미해석 {@code ${VIEW_COUNT_HMAC_SECRET}} 리터럴이 그대로 들어오고, 빈 환경변수는 빈
 * 문자열로 들어온다 (sp-docs/tech-stack.md §4.3 의 측정값). 그 리터럴이 32바이트보다 짧아 길이
 * 검사에 걸리는 것은 환경변수 이름의 길이에 기댄 결과이므로 리터럴을 따로 거부한다.
 *
 * <p>정리 주기는 편의값이다 — 기본 1시간을 {@code application.yml} 이 준다 (§4.5). 판정 결과에
 * 영향이 없고 판정 행 보존 기간의 상한을 정한다 (sp-docs/security.md §9).
 *
 * <p>로컬에서는 {@code application-local.yml} 이 비밀키를 준다. 다만 환경변수
 * {@code VIEW_COUNT_HMAC_SECRET} 이 설정돼 있으면 yml 의 값보다 앞선다 — 그 이름이 속성 경로의
 * 완화 바인딩 형식(점·대시를 밑줄로 바꾼 대문자)과 같아 placeholder 를 거치지 않고 이 키에 묶이고,
 * OS 환경변수는 yml 보다 우선한다. 측정값이다 — 이 환경변수를 준 테스트 실행에서
 * {@code application-test.yml} 의 값 대신 쓰였다.
 */
@ConfigurationProperties("view-count")
public class ViewCountProperties {

	/** 비밀키의 UTF-8 바이트 하한. sp-docs/tech-stack.md §4.5 의 값이다. */
	static final int MIN_HMAC_SECRET_BYTES = 32;

	private final byte[] hmacSecret;

	private final Duration cleanupInterval;

	public ViewCountProperties(String hmacSecret, Duration cleanupInterval) {
		this.hmacSecret = requireHmacSecret(hmacSecret);
		this.cleanupInterval = cleanupInterval;
	}

	/**
	 * HMAC 키 — 설정 문자열의 UTF-8 바이트다. 호출마다 복사본을 준다. 받은 쪽이 배열을 바꿔도
	 * 이후의 판정 키가 바뀌지 않게 하려는 것이다.
	 */
	public byte[] hmacSecret() {
		return this.hmacSecret.clone();
	}

	/** 기준 시각이 24시간을 넘은 판정 행을 지우는 주기 (sp-docs/requirements/board.md §9.3). */
	public Duration cleanupInterval() {
		return this.cleanupInterval;
	}

	/** 받은 값을 메시지에 넣지 않는다 — 비밀값이다. */
	private static byte[] requireHmacSecret(String value) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalStateException(
					"view-count.hmac-secret 이 비어 있다. 비밀키를 외부에서 주입한다 "
							+ "(sp-docs/tech-stack.md §4.5)");
		}
		if (value.startsWith("${") && value.endsWith("}")) {
			throw new IllegalStateException(
					"view-count.hmac-secret 이 해석되지 않은 placeholder 다 — 값을 줄 환경변수가 없다 "
							+ "(sp-docs/tech-stack.md §4.3 §4.5)");
		}
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < MIN_HMAC_SECRET_BYTES) {
			throw new IllegalStateException(
					"view-count.hmac-secret 의 UTF-8 바이트 길이가 " + MIN_HMAC_SECRET_BYTES
							+ " 보다 짧다 (sp-docs/tech-stack.md §4.5)");
		}
		return bytes;
	}

}
