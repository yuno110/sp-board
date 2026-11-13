package com.example.board.post.entity;

/**
 * 게시글 카테고리. 정본: sp-docs/requirements/board.md §8, sp-docs/domain-model.md §4.1.
 *
 * <p>컬럼은 {@code VARCHAR(20)} 이고 이름 그대로 저장한다 ({@code EnumType.STRING}). 순서로
 * 저장하면 값을 더하거나 옮길 때 기존 행의 뜻이 바뀐다.
 *
 * <p>카테고리가 생기기 전의 글은 {@link #FREE} 다 — 컬럼을 더한 마이그레이션의 기본값이 채운다
 * ({@code V3__add_post_category_and_counts.sql}).
 */
public enum PostCategory {

	/** 공지. ADMIN 만 쓴다 — 그 판정(403 {@code P003})은 서비스가 한다. */
	NOTICE,

	/** 자유. 작성 때 고르지 않으면 이것이다. */
	FREE,

	/** 질문. */
	QNA

}
