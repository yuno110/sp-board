package com.example.securityprobe;

import com.example.board.global.security.LoginMember;
import com.example.board.global.security.OptionalCurrentMember;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 선택적 리졸버({@code @OptionalCurrentMember})가 채운 값을 HTTP 경로로 확인할 대상이다.
 *
 * <p><b>{@code null} 을 그대로 돌려주지 않는다.</b> 본문이 {@code null} 이면 「리졸버가
 * {@code null} 을 냈다」 와 「키가 빠졌다」 가 JSON 경로 단언에서 구별되지 않으므로
 * {@code anonymous} 를 명시한다.
 *
 * <p><b>{@code com.example.board} 밖에 둔다</b> — {@link CurrentMemberProbeController} 와 같은
 * 이유다. 이 경로를 여는 테스트 전용 필터 체인과 함께 그 테스트의 컨텍스트에만 등록한다.
 */
@RestController
public class OptionalCurrentMemberProbeController {

	public static final String PATH = "/test/optional-member";

	@GetMapping(PATH)
	public Map<String, Object> optionalMember(@OptionalCurrentMember LoginMember loginMember) {
		if (loginMember == null) {
			return Map.of("anonymous", true);
		}
		return Map.of(
				"anonymous", false,
				"accountId", loginMember.accountId(),
				"role", loginMember.role());
	}

}
