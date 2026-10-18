package com.example.board.global.security;

import java.util.Set;

/**
 * sp-docs/api-contract.md §6 이 고정한 {@code role} claim 의 허용값.
 *
 * <p>열거 검사({@link CurrentMemberArgumentResolver})와 ADMIN 판정({@code PostService}·
 * {@code CommentService} 의 삭제 권한, sp-docs/security.md §5.2)이 이 상수를 본다 — 값을 자리마다
 * 박으면 읽는 쪽들이 갈린다.
 *
 * <p>이 집합 밖의 값은 <b>서명이 유효해도 계약 위반 토큰</b>이다. §6 이 소비자에게 값 검증을
 * 요구하고 401 {@code A002} 로 거부하게 한다 (sp-docs/plan/phase1.md §6 D-02 가 그렇게 정했다).
 *
 * <p><b>열거 타입이 아니다.</b> {@link LoginMember} 가 claim 값을 {@code String} 으로 그대로
 * 옮기고, 타입 전환은 B-12 가 범위 밖에 둔 별 항목이다 — 전환하면 이 파일과 이것을 읽는 쪽이
 * 함께 움직인다.
 *
 * <p>{@code ROLE_} 접두어는 여기 없다. 그것은 {@code GrantedAuthority} 의 이름 규칙이고
 * {@link RoleClaimConverter} 가 붙인다 (sp-docs/security.md §5.1).
 */
public final class ContractRole {

	/** 관리자. 타인 글·댓글 삭제가 이 값에서만 허용된다 (sp-docs/security.md §5.2). */
	public static final String ADMIN = "ADMIN";

	/** 일반 사용자. 판정에 쓰이지 않고 허용값 집합을 이룬다. */
	private static final String USER = "USER";

	private static final Set<String> VALUES = Set.of(USER, ADMIN);

	private ContractRole() {
	}

	/**
	 * {@code role} claim 값이 §6 의 허용값인지 본다.
	 *
	 * <p><b>{@code null}·공백·대소문자가 다른 값은 허용값이 아니다.</b> 누락과 열거 밖이 한
	 * 판정으로 떨어지므로 호출 쪽이 둘을 가르지 않는다 — 거부 사유를 응답으로 구별하지 않는 것이
	 * §6 의 요구다.
	 */
	public static boolean contains(String role) {
		return role != null && VALUES.contains(role);
	}

}
