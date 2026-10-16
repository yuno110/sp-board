package com.example.board.post.dto;

/**
 * 정렬 기준. 정본: sp-docs/api-contract.md §4.1 — {@code latest}(최신순) ·
 * {@code views}(조회순)이고 기본값은 {@code latest} 다.
 *
 * <p><b>컬럼명을 직접 받지 않는다.</b> 정렬은 이 화이트리스트로만 받으므로 요청이 임의 컬럼명을
 * 넣을 자리가 없다 (sp-docs/requirements/board.md §6). 화이트리스트 밖의 값은 바인딩 단계에서
 * 걸려 400 이 된다 — 변환 실패가 {@code MethodArgumentTypeMismatchException} 으로 올라오고
 * {@code GlobalExceptionHandler} 가 {@code C002} 로 바꾼다
 * (sp-docs/api-contract.md §8.1, sp-docs/conventions.md §6.1).
 *
 * <p><b>상수 이름이 곧 {@code sort} 쿼리 파라미터의 값이다.</b> 그래서 자바 상수 표기와 달리
 * 소문자다 — 정본이 값을 {@code latest}·{@code views} 로 고정했고 Spring 의 String → Enum
 * 변환은 대소문자를 구분한다(측정값: 이 상수들로 {@code sort=LATEST} 를 보내면 400
 * {@code C002} 다). 이름을 대문자로 바꾸면 정본이 고정한 {@code sort=latest} 가 그렇게 된다.
 * 이름을 값과 같게 두면 변환기를 따로 등록하지 않아도 정본의 값이 그대로 통한다.
 *
 * <p>어느 컬럼으로 정렬하는가는 {@code PostQueryRepository} 가 정한다. 이 enum 에 QueryDSL
 * 식을 두면 DTO 가 영속 계층의 Q 타입에 의존한다 (sp-docs/conventions.md §1.3 §3).
 */
public enum PostSortType {

	latest,
	views

}
