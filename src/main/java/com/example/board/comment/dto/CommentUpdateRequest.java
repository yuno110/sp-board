package com.example.board.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 댓글 수정 요청 본문 (C-03). 정본: sp-docs/requirements/board.md §4 — 입력 규칙이 작성과 같은
 * 행이다 (content(댓글) 1~500자). 위반은 400 {@code C001} 이다 (sp-docs/api-contract.md §8.1).
 *
 * <p><b>제약 값을 {@link CommentCreateRequest} 의 상수에서 가져온다.</b> §4 의 한 행이 두
 * 경로의 규칙을 함께 정하므로, 숫자와 메시지를 여기 다시 적으면 그 행이 바뀔 때 한쪽만 고쳐질
 * 수 있다. 규칙이 갈라지면 그때 각자 값을 갖는다.
 *
 * <p><b>작성자 스냅샷을 갱신하지 않는다.</b> {@code writer_id}·{@code writer_nickname} 필드가
 * 없고 {@code Comment.update(String)} 가 그 둘을 건드리지 않는다
 * (sp-docs/requirements/board.md §3 규칙 7). 그래서 수정 경로는 member-service 를 호출하지
 * 않는다 (sp-docs/api-contract.md §4.2).
 *
 * @param content 댓글 내용
 */
public record CommentUpdateRequest(

		@NotBlank(message = CommentCreateRequest.CONTENT_MESSAGE)
		@Size(max = CommentCreateRequest.CONTENT_MAX_LENGTH,
				message = CommentCreateRequest.CONTENT_MESSAGE)
		String content) {

}
