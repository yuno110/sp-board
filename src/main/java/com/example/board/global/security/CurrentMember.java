package com.example.board.global.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증된 요청의 {@link LoginMember} 를 컨트롤러 파라미터로 받는다.
 *
 * <p>요청 본문의 식별자를 신뢰하지 않는다. 작성자 식별자와 조회 키는 검증된 토큰의
 * {@code sub} 에서만 온다 (sp-docs/security.md §4, sp-docs/adr/0012 §6).
 *
 * <p><b>경로 변수로 주체를 정하지도 않는다</b> — 경로 변수는 글·댓글 자원을 가리키고, 주체는
 * 이 애노테이션이 채운다. <b>이것까지 위 인용이 뒷받침하지는 않는다</b> — {@code security.md}
 * §4 와 {@code adr/0012} §6 이 말하는 것은 <b>본문</b>까지다.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentMember { }
