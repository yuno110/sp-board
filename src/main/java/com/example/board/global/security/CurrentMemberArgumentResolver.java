package com.example.board.global.security;

import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
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
 *
 * <p><b>{@code role} 은 값까지 본다</b> — §6 이 허용값을 고정하므로 그 밖의 값은 서명이
 * 유효해도 계약 위반 토큰이다 ({@link ContractRole}). <b>board 의 Claim 계약 집행 지점이
 * 여기다</b> — member 는 디코더에서 한다 (sp-docs/tech-stack.md §2.1). 그래서 이 집행은
 * {@link CurrentMember} 를 받는 핸들러에서만 성립한다.
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

	/**
	 * {@code role} 이 {@link ContractRole} 의 허용값인지 본다 (sp-docs/api-contract.md §6).
	 *
	 * <p><b>누락·공백과 열거 밖 값이 같은 예외로 나간다</b> — 거부 사유를 응답으로 구별하지
	 * 않는다 (§6, §8.5 의 "그 밖의 claim 검증 실패"). {@link ContractRole#contains} 가 셋을 한
	 * 판정으로 받으므로 여기서 가르지 않는다.
	 *
	 * <p><b>디코더에 claim 검증기를 더해 집행하지 않는다.</b> 그 길로 가면 실패가
	 * {@code JwtValidationException} 으로 보고되어 {@code SecurityErrorResponder} 가 만료
	 * ({@code A003})로 읽는다 — board 의 집행 지점이 리졸버인 이유다
	 * (sp-docs/tech-stack.md §2.1, {@code JwtDecoderValidatorsTest} 가 그 집합을 고정한다).
	 */
	private static String role(Jwt jwt) {
		String role = jwt.getClaimAsString(RoleClaimConverter.CLAIM_ROLE);
		if (!ContractRole.contains(role)) {
			// 받은 값을 메시지에 넣지 않는다. 응답에는 ErrorCode 의 고정 메시지만 나간다
			throw new BusinessException(ErrorCode.INVALID_TOKEN);
		}
		return role;
	}

}
