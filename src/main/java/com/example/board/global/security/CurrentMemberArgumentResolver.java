package com.example.board.global.security;

import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentMember} 파라미터를 검증된 JWT 에서 채운다. 등록은
 * {@code global/config/WebConfig} 가 한다.
 *
 * <p>인증이 없거나 principal 이 JWT 가 아니면 조용히 {@code null} 을 넣지 않고 예외를 던진다.
 * 기본값으로 메우면 인증 실패가 "익명 사용자"로 위장되고, 그 값이 그대로
 * {@code post.writer_id} 가 된다 (sp-docs/security.md §7, sp-docs/adr/0012 §6).
 *
 * <p><b>{@code sub}·{@code role} 을 해석할 수 없으면 {@code A002} 다</b>
 * (sp-docs/api-contract.md §8.5 "그 밖의 claim 검증 실패"). 서명은 맞지만 Claim 계약(§6)을
 * 어긴 토큰이 여기 올 수 있으므로 — 발급은 auth 하나이고 board 는 공개키 검증만 하므로 통상
 * 일어나지 않지만 — 실패를 드러낸다. 가드가 없으면 {@code sub} 가 숫자가 아닐 때
 * {@code NumberFormatException} 이 500 으로 나가고, {@code role} 이 없으면
 * {@code LoginMember.role} 이 {@code null} 로 채워진다.
 */
@Component
public class CurrentMemberArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentMember.class)
				&& LoginMember.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(
			MethodParameter parameter,
			ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest,
			WebDataBinderFactory binderFactory) {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return new LoginMember(accountId(jwt), role(jwt));
	}

	/** {@code sub} 는 {@code account.id} 의 문자열이다 (sp-docs/api-contract.md §6). */
	private static Long accountId(Jwt jwt) {
		try {
			return Long.valueOf(jwt.getSubject());
		} catch (NumberFormatException | NullPointerException e) {
			// 받은 값을 메시지에 넣지 않는다. 응답에는 ErrorCode 의 고정 메시지만 나간다
			throw new BusinessException(ErrorCode.INVALID_TOKEN);
		}
	}

	private static String role(Jwt jwt) {
		String role = jwt.getClaimAsString(RoleClaimConverter.CLAIM_ROLE);
		if (!StringUtils.hasText(role)) {
			throw new BusinessException(ErrorCode.INVALID_TOKEN);
		}
		return role;
	}

}
