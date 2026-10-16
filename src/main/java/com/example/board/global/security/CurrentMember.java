package com.example.board.global.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증된 요청의 {@link LoginMember} 를 컨트롤러 파라미터로 받는다.
 *
 * <p>요청 본문·경로 변수의 식별자를 신뢰하지 않는다. 작성자 식별자와 조회 키는 검증된 토큰의
 * {@code sub} 에서만 온다 (sp-docs/security.md §4, sp-docs/adr/0012 §6).
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentMember { }
