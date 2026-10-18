package com.example.board.global.security;

import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * {@code role} claim 을 {@link GrantedAuthority} 로 바꾼다 (sp-docs/security.md §5.1).
 *
 * <p><b>검증 스타터가 하는 것은 서명 검증·만료 확인·{@code SecurityContext} 주입이다</b>
 * (sp-docs/tech-stack.md §2.1). 이 변환과 Claim 계약 검증은 그 목록에 없어 서비스가 쓴다 —
 * 등록은 {@code global/config/SecurityConfig} 가 {@code JwtAuthenticationConverter} 를 통해
 * 하고, board 의 계약 검증은 {@link CurrentMemberArgumentResolver} 에 있다 (같은 절의 집행
 * 지점 표).
 *
 * <p>접두어 {@code ROLE_} 은 Spring Security 의 {@code hasRole} 이 보는 것이고,
 * sp-docs/plan/phase1.md §6 B-04 의 검증표가 {@code role = ADMIN} 토큰에
 * {@code ROLE_ADMIN} 을 요구한다 — <b>member 도 같은 이름을 쓰고 auth 는 그 이름을 만들지
 * 않는다</b>. auth 는 변환을 두지 않고 권한을 {@code LoginAccount.role} 로만 전달한다
 * (sp-docs/security.md §5.1).
 */
@Component
public class RoleClaimConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

	/** sp-docs/api-contract.md §6 의 권한 claim 이름. */
	public static final String CLAIM_ROLE = "role";

	private static final String AUTHORITY_PREFIX = "ROLE_";

	@Override
	public Collection<GrantedAuthority> convert(Jwt jwt) {
		return List.of(new SimpleGrantedAuthority(AUTHORITY_PREFIX + jwt.getClaimAsString(CLAIM_ROLE)));
	}

}
