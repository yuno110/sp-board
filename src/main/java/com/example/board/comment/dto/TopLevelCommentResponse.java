package com.example.board.comment.dto;

import com.example.board.comment.entity.Comment;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 댓글 목록의 최상위 항목. 정본: sp-docs/api-contract.md §4.5 의 표 둘째 줄, §9.7.
 *
 * <p><b>{@link CommentResponse} 의 키에 {@code replies} 를 더한 것이다</b> (§4.5). 레코드는
 * 상속할 수 없어 키를 다시 선언하지만, 값은 {@link CommentResponse#from} 이 만든 것을 옮겨 싣는다
 * — 그래서 삭제된 최상위의 세 필드를 가리는 규칙을 여기서 다시 적지 않는다.
 *
 * <p>목록에 오는 최상위는 활성 최상위 댓글과 <b>활성 답글이 있는 삭제된 최상위 댓글</b>이다
 * (sp-docs/requirements/board.md §12). 뒤의 것은 {@code deleted: true} 이고
 * {@code writerId}·{@code writerNickname}·{@code content} 가 {@code null} 인 자리다 —
 * 「삭제된 댓글입니다」 문구는 화면이 그린다 (§4.5).
 *
 * <p>선언 순서가 곧 JSON 키 순서다. §9.7 의 예시와 같은 순서로 둔다.
 *
 * @param parentId 최상위이므로 언제나 {@code null} 이다 (§4.5)
 * @param replies 그 댓글의 활성 답글, 등록순. 원소는 {@link CommentResponse} 라 {@code replies}
 *        를 갖지 않는다. <b>답글이 없으면 빈 배열이고 {@code null} 이 아니다</b> (§4.5)
 * @param createdAt 오프셋을 표기하지 않는다 (sp-docs/tech-stack.md §6)
 */
public record TopLevelCommentResponse(
		Long id,
		Long parentId,
		Long writerId,
		String writerNickname,
		String content,
		boolean deleted,
		LocalDateTime createdAt,
		List<CommentResponse> replies
) {

	/**
	 * @param replies 그 댓글의 활성 답글을 등록순으로. 없으면 빈 목록
	 */
	public static TopLevelCommentResponse of(Comment topLevel, List<CommentResponse> replies) {
		CommentResponse comment = CommentResponse.from(topLevel);
		return new TopLevelCommentResponse(
				comment.id(),
				comment.parentId(),
				comment.writerId(),
				comment.writerNickname(),
				comment.content(),
				comment.deleted(),
				comment.createdAt(),
				List.copyOf(replies)
		);
	}

}
