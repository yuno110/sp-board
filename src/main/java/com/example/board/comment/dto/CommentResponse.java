package com.example.board.comment.dto;

import com.example.board.comment.entity.Comment;
import java.time.LocalDateTime;

/**
 * 댓글 하나의 모양. 정본: sp-docs/api-contract.md §4.5 의 표 첫 줄.
 *
 * <p><b>작성({@code POST /posts/{postId}/comments}, 201)·수정({@code PUT /comments/{id}}, 200)
 * 응답과 목록의 답글 원소가 이 모양이다</b> (§4.5). 목록의 최상위 항목은 이 키에
 * {@code replies} 를 더한 {@link TopLevelCommentResponse} 다. 게시글처럼 본문 크기로 모양을
 * 가르지 않는다 — 게시글이 가른 이유는 {@code content} 의 크기였고 (§4.3), 댓글
 * {@code content} 는 500자다.
 *
 * <p><b>1차의 이 레코드에는 {@code parentId}·{@code deleted} 가 없었다.</b> 2026-11-11
 * {@code D-18} 이 §4.5 에 둘을 더했고 B-22 가 그것을 따랐다 — 1차 항목(B-08)이 단언한 키 집합은
 * 그 시점의 계약이다 (§4.5 의 블록인용).
 *
 * <p><b>삭제된 댓글이면 {@code writerId}·{@code writerNickname}·{@code content} 를
 * {@code null} 로 가린다</b> (§4.5). {@code id}·{@code parentId}·{@code createdAt} 은 그대로다.
 * 목록이 자리로 싣는 삭제된 최상위 댓글(sp-docs/requirements/board.md §12)이 그 모양이고, 가리는
 * 규칙이 {@link #from} 에 있으므로 그 메서드를 지나는 경로는 지운 내용을 싣지 않는다. 작성 응답은
 * 방금 만든 댓글이고, 수정은 활성 댓글만 읽고 ({@code CommentRepository.findByIdAndDeletedFalse}),
 * 목록의 답글은 활성 답글만 읽는다 — 그래서 그 응답들은 {@code deleted: false} 다 (§4.5).
 *
 * <p><b>{@code postId} 를 넣지 않는다.</b> 작성·목록은 {@code /posts/{postId}/comments} 경로가
 * 그것을 이미 담고, 수정은 클라이언트가 목록에서 받은 댓글을 고치는 것이다 (§4.5).
 *
 * <p><b>{@code updatedAt} 을 넣지 않는다.</b> {@code updated_at} 을 감사 흔적으로 쓰지 않고
 * 노출 요구가 없다 (§4.5, §4.3, sp-docs/domain-model.md §1.1).
 *
 * <p>선언 순서가 곧 JSON 키 순서다. §4.5 의 표와 같은 순서로 둔다.
 *
 * @param parentId 답글이면 부모 댓글의 {@code id}, 최상위면 {@code null} (§4.5)
 * @param createdAt 오프셋을 표기하지 않는다 (sp-docs/tech-stack.md §6)
 */
public record CommentResponse(
		Long id,
		Long parentId,
		Long writerId,
		String writerNickname,
		String content,
		boolean deleted,
		LocalDateTime createdAt
) {

	public static CommentResponse from(Comment comment) {
		Long parentId = comment.getParent() == null ? null : comment.getParent().getId();
		if (comment.isDeleted()) {
			return new CommentResponse(
					comment.getId(), parentId, null, null, null, true, comment.getCreatedAt());
		}
		return new CommentResponse(
				comment.getId(),
				parentId,
				comment.getWriterId(),
				comment.getWriterNickname(),
				comment.getContent(),
				false,
				comment.getCreatedAt()
		);
	}

}
