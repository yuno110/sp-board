package com.example.board.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 댓글 작성 요청 본문 (C-01). 정본: sp-docs/requirements/board.md §4 — content(댓글)는
 * 1~500자다. 위반은 400 {@code C001} 이다 (sp-docs/api-contract.md §8.1).
 *
 * <p><b>{@code postId} 필드를 두지 않는다.</b> 대상 게시글은 경로
 * ({@code /api/v1/posts/{postId}/comments})가 담는다 (sp-docs/api-contract.md §4). 본문에도
 * 두면 두 값이 어긋났을 때 어느 쪽이 대상인지 정해야 한다.
 *
 * <p><b>{@code writerId}·{@code nickname} 필드를 두지 않는다.</b> {@code writer_id} 는 검증된
 * JWT 의 {@code sub} 에서, {@code writer_nickname} 은 member 의 내부 API 응답에서만 온다
 * (sp-docs/requirements/board.md §3 규칙 1·2). 본문에 그 키를 실어 보내도 바인딩될 자리가 없다.
 *
 * <h2>{@code @NotBlank} 와 {@code @Size} 가 둘 다 필요하다</h2>
 *
 * <p>하나만으로는 §4 의 "1~500자" 를 표현할 수 없다. {@code @NotBlank} 만 걸면 501자가 상한
 * 없이 통과하고, {@code @Size} 만 걸면 공백 500자와 빈 문자열이 길이가 맞아 통과한다.
 * {@code null} 도 {@code @Size} 는 통과시키고 {@code @NotBlank} 가 막는다.
 *
 * <p>컬럼 폭도 500 이라 여유가 없다 (sp-docs/domain-model.md §4.2) — 상한이 새면
 * {@code DataIntegrityViolationException} 으로 터진다.
 *
 * @param content 댓글 내용
 */
public record CommentCreateRequest(

		@NotBlank(message = CONTENT_MESSAGE)
		@Size(max = CONTENT_MAX_LENGTH, message = CONTENT_MESSAGE)
		String content) {

	/** sp-docs/requirements/board.md §4. 컬럼 폭도 500 이다 (sp-docs/domain-model.md §4.2). */
	public static final int CONTENT_MAX_LENGTH = 500;

	public static final String CONTENT_MESSAGE = "댓글은 1~500자여야 합니다.";

}
