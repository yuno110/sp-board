package com.example.board.global.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증을 <b>선택으로</b> 받는 경로에서 {@link LoginMember} 를 컨트롤러 파라미터로 받는다
 * (sp-docs/api-contract.md §4.8, sp-docs/conventions.md §1.3 의 「선택적 인증의 리졸버」).
 *
 * <p><b>{@code null} 은 「토큰이 실리지 않았다」 하나만 뜻한다.</b> 토큰이 실렸으면
 * {@link CurrentMember} 와 같은 검사를 지나고, 그 검사에 실패하면 401 이다 — 익명으로 낮추지
 * 않는다 (sp-docs/requirements/board.md §9.1). 해석은
 * {@link OptionalCurrentMemberArgumentResolver} 가 한다.
 *
 * <p>인가 규칙은 이 애노테이션이 정하지 않는다. 경로를 {@code permitAll} 로 여는 것은
 * {@code SecurityConfig} 이고, {@code authenticated()} 경로에서는 토큰 없는 요청이 여기 닿기
 * 전에 필터 체인이 401 로 막는다.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface OptionalCurrentMember { }
