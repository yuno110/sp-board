package com.example.board.global.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link OptionalCurrentMember} 파라미터를 채운다 — 토큰이 없으면 {@code null}, 있으면
 * {@link CurrentMemberArgumentResolver} 와 같은 검사를 지난 {@link LoginMember} 다. 등록은
 * {@code global/config/WebConfig} 가 한다.
 *
 * <table>
 * <caption>요청과 결과 (sp-docs/api-contract.md §4.8, sp-docs/requirements/board.md §9.1)</caption>
 * <tr><th>요청</th><th>결과</th><th>거부하는 쪽</th></tr>
 * <tr><td>토큰 없음</td><td>{@code null}</td><td>-</td></tr>
 * <tr><td>유효한 토큰</td><td>그 계정과 역할</td><td>-</td></tr>
 * <tr><td>서명 불일치·만료·JWT 아님 등 검증에 실패한 토큰</td><td>401 (코드는
 * sp-docs/api-contract.md §8.5 의 경계)</td><td>필터 체인 — 이 리졸버에 닿지 않는다</td></tr>
 * <tr><td>{@code sub} 누락·숫자 아님, {@code role} 누락·열거 밖</td><td>401 {@code A002}</td>
 * <td>이 리졸버 — {@link CurrentMemberArgumentResolver#loginMember}</td></tr>
 * </table>
 *
 * <p><b>검사에 실패한 토큰을 {@code null} 로 낮추지 않는다.</b> 낮추면 계약 밖 토큰이 익명으로
 * 통과하는 통로가 된다 (sp-docs/tech-stack.md §2.1 의 2026-11-11 {@code D-18} 블록인용). 그래서
 * {@code null} 을 내는 분기는 「토큰이 없다」 하나이고, 그 밖은 전부 {@code @CurrentMember} 와
 * 같은 메서드로 보낸다.
 *
 * <p><b>「토큰이 없다」 는 {@link AnonymousAuthenticationToken} 으로 판정한다.</b> 필터 체인에서
 * Bearer 토큰이 실리지 않은 요청만 그 인증을 받는다 — 실린 토큰이 검증에 실패하면
 * {@code BearerTokenAuthenticationFilter} 가 그 자리에서 401 을 내 요청이 여기 오지 않고,
 * 통과하면 principal 이 {@code Jwt} 다. 인증이 아예 없는 상태({@code null})는 익명 인증을
 * 세우는 필터를 거치지 않은 요청이라 토큰이 있었는지 알 수 없다 — {@code null} 로 메우지 않고
 * {@code A001} 로 드러낸다.
 */
@Component
public class OptionalCurrentMemberArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(OptionalCurrentMember.class)
				&& LoginMember.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(
			MethodParameter parameter,
			ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest,
			WebDataBinderFactory binderFactory) {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof AnonymousAuthenticationToken) {
			return null;
		}
		return CurrentMemberArgumentResolver.loginMember(authentication);
	}

}
