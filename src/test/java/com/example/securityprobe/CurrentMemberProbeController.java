package com.example.securityprobe;

import com.example.board.global.security.CurrentMember;
import com.example.board.global.security.LoginMember;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증이 통과한 뒤의 상태를 HTTP 경로로 확인할 대상이다. 업무 컨트롤러는 B-05 부터 생긴다.
 *
 * <p>{@code @CurrentMember} 리졸버가 등록되지 않으면 Spring 이 {@link LoginMember} 를 모델
 * 속성으로 보고 빈 record 를 만들어 넘긴다 — 예외 없이 {@code accountId = null} 이 되는 조용한
 * 실패이고, 그 값이 그대로 {@code post.writer_id} 가 된다. HTTP 경로로 확인해야 드러난다.
 *
 * <p><b>{@code com.example.board} 밖에 둔다.</b> 안에 두면 컴포넌트 스캔에 걸려 다른 테스트의
 * 컨텍스트에도 이 경로가 생긴다. {@code JwtAuthenticationTest} 가 {@code @TestConfiguration} 으로
 * 자기 컨텍스트에만 등록한다.
 */
@RestController
public class CurrentMemberProbeController {

	/** SecurityConfig 에 선언되지 않은 경로이므로 {@code anyRequest()} 규칙이 적용된다. */
	@GetMapping("/test/current-member")
	public LoginMember currentMember(@CurrentMember LoginMember loginMember) {
		return loginMember;
	}

	/** {@code role} claim 이 권한으로 변환됐는지 본다 (sp-docs/security.md §5.1). */
	@GetMapping("/test/authorities")
	public List<String> authorities(Authentication authentication) {
		return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
	}

}
