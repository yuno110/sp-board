package com.example.board.post.dto;

/**
 * 검색 기준. 정본: sp-docs/requirements/board.md §6 (대상 컬럼),
 * sp-docs/api-contract.md §4.1 (파라미터 값).
 *
 * <p><b>상수 이름이 곧 {@code searchType} 쿼리 파라미터의 값이다.</b> §4.1 이 값을 대문자로
 * 고정했으므로 자바 상수 표기와 그대로 맞는다 — {@code sort} 쪽은 정본이 소문자로 고정해
 * {@link PostSortType} 이 그 표기를 따른다.
 *
 * <p>값이 아니라 <b>어느 컬럼을 보는가</b>는 {@code PostQueryRepository} 가 정한다. 이 enum 에
 * QueryDSL 식을 두면 DTO 가 영속 계층의 Q 타입에 의존한다 (sp-docs/conventions.md §1.3 §3).
 *
 * <p>정렬과 마찬가지로 <b>화이트리스트</b>다. 컬럼명을 직접 받지 않는다 (§6).
 */
public enum SearchType {

	TITLE,
	CONTENT,
	TITLE_CONTENT,
	WRITER

}
