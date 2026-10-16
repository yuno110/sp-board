package com.example.board.comment.dto;

import com.example.board.comment.entity.Comment;
import java.time.LocalDateTime;

/**
 * 댓글 응답. 정본: sp-docs/api-contract.md §4.5 — <b>다섯 키다.</b>
 *
 * <p><b>세 엔드포인트가 이 한 모양을 쓴다</b> (§4.5): 작성({@code POST
 * /posts/{postId}/comments}, 201), 목록({@code GET /posts/{postId}/comments})의 각 항목,
 * 수정({@code PUT /comments/{id}}, 200). 게시글처럼 목록과 상세로 가르지 않는다 — 게시글이
 * 가른 이유는 {@code content} 의 크기였고 (§4.3), 댓글 {@code content} 는 500자이므로 한
 * 페이지 20건이 10,000자다.
 *
 * <p><b>{@code deleted} 를 넣지 않는다.</b> 삭제된 댓글은 목록·상세에서 제외되므로
 * (sp-docs/requirements/board.md §5 규칙 1) 돌아온 댓글은 항상 활성이다. 같은 절의 규칙
 * 2("삭제된 댓글은 "삭제된 댓글입니다" 로 표시할 수 있도록 행을 남긴다")는 <b>행을 남기는
 * 이유</b>이고 1차의 응답 요구가 아니다 — 표시가 필요해지는 것은 대댓글이 생기는 2차다
 * (§4.5, sp-docs/requirements/board.md §2 의 C-05).
 *
 * <p><b>{@code postId} 를 넣지 않는다.</b> 작성·목록은 {@code /posts/{postId}/comments} 경로가
 * 그것을 이미 담고, 수정은 클라이언트가 목록에서 받은 댓글을 고치는 것이다 (§4.5).
 *
 * <p><b>{@code updatedAt} 을 넣지 않는다.</b> {@code updated_at} 을 감사 흔적으로 쓰지 않고
 * 노출 요구가 없다 (§4.5, §4.3, sp-docs/domain-model.md §1.1).
 *
 * <p>선언 순서가 곧 JSON 키 순서다. §4.5 의 표와 같은 순서로 둔다.
 *
 * @param createdAt 오프셋을 표기하지 않는다 (sp-docs/tech-stack.md §6)
 */
public record CommentResponse(
		Long id,
		Long writerId,
		String writerNickname,
		String content,
		LocalDateTime createdAt
) {

	public static CommentResponse from(Comment comment) {
		return new CommentResponse(
				comment.getId(),
				comment.getWriterId(),
				comment.getWriterNickname(),
				comment.getContent(),
				comment.getCreatedAt()
		);
	}

}
