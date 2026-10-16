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
 * <p><b>검증 스타터가 직접 작성할 것으로 남겨 둔 것이 이 컨버터뿐이다</b> — 서명 검증·만료
 * 확인·{@code SecurityContext} 주입은 스타터가 한다 (sp-docs/tech-stack.md §2.1). 등록은
 * {@code global/config/SecurityConfig} 가 {@code JwtAuthenticationConverter} 를 통해 한다.
 *
 * <p>접두어 {@code ROLE_} 은 Spring Security 의 {@code hasRole} 이 보는 것이고,
 * sp-docs/plan/phase1.md §6 B-04 의 검증표가 {@code role = ADMIN} 토큰에
 * {@code ROLE_ADMIN} 을 요구한다 — 세 서비스가 같은 이름을 쓴다.
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
