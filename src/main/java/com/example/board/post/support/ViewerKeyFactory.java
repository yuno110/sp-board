package com.example.board.post.support;

import com.example.board.global.security.LoginMember;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 조회수 판정 키를 만든다 (sp-docs/requirements/board.md §9.1).
 *
 * <table>
 * <caption>요청과 판정 키</caption>
 * <tr><th>요청</th><th>판정 키</th></tr>
 * <tr><td>유효한 토큰</td><td>{@code a:} + {@code accountId}</td></tr>
 * <tr><td>토큰 없음</td><td>{@code i:} + {@code HMAC-SHA256(비밀키, remoteAddr)} 의 소문자 hex</td></tr>
 * </table>
 *
 * <p>유효하지 않은 토큰은 여기 오지 않는다 — 검증에 실패한 토큰은 필터 체인이, claim 이 계약 밖인
 * 토큰은 선택적 리졸버가 401 로 막는다 (§9.1, {@code OptionalCurrentMemberArgumentResolver}).
 *
 * <p><b>원 IP 를 저장하지도 로그에 남기지도 않는다</b> (sp-docs/security.md §9). 이 클래스는 주소를
 * HMAC 의 입력으로만 쓰고 로그를 남기지 않는다. 키 없는 해시를 쓰지 않는 이유는 그 절에 있다 — IPv4
 * 는 전수 대입으로 되돌릴 수 있다.
 *
 * <p><b>주소는 부르는 쪽이 준 {@code remoteAddr} 다.</b> 이 클래스는 요청을 받지 않으므로 헤더를 볼
 * 수 없다 — {@code X-Forwarded-For} 를 읽지 않는 자리는 {@code PostController} 다 (§9.1).
 *
 * <p>비밀키는 {@link ViewCountProperties} 가 기동 시점에 검사한 값, 곧 설정 문자열의 UTF-8 바이트다
 * (sp-docs/tech-stack.md §4.5). {@link Mac} 은 상태를 가지므로 호출마다 만든다.
 */
@Component
public class ViewerKeyFactory {

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private static final String ACCOUNT_PREFIX = "a:";

	private static final String ADDRESS_PREFIX = "i:";

	private final SecretKeySpec hmacKey;

	public ViewerKeyFactory(ViewCountProperties properties) {
		this.hmacKey = new SecretKeySpec(properties.hmacSecret(), HMAC_ALGORITHM);
	}

	/**
	 * 판정 키. {@code loginMember} 가 있으면 그 계정의 키이고 {@code remoteAddr} 를 쓰지 않는다 — 같은
	 * 사람의 로그인 전·후는 두 조회자다 (sp-docs/requirements/board.md §9.2).
	 *
	 * @param loginMember 유효한 토큰의 계정. 토큰이 없으면 {@code null}
	 * @param remoteAddr TCP 연결의 상대 주소
	 */
	public String keyOf(@Nullable LoginMember loginMember, String remoteAddr) {
		if (loginMember != null) {
			return ACCOUNT_PREFIX + loginMember.accountId();
		}
		// 받은 값을 메시지에 넣지 않는다 — 주소를 로그에 남기지 않는다 (security.md §9)
		Objects.requireNonNull(remoteAddr, "비로그인 조회의 remoteAddr 가 없다");
		return ADDRESS_PREFIX + HexFormat.of().formatHex(
				newMac().doFinal(remoteAddr.getBytes(StandardCharsets.UTF_8)));
	}

	private Mac newMac() {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(this.hmacKey);
			return mac;
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 을 준비하지 못했다", ex);
		}
	}

}
