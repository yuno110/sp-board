package com.example.board.post.dto;

import com.example.board.post.entity.Post;
import java.time.LocalDateTime;

/**
 * 게시글 <b>목록 항목</b>. 정본: sp-docs/api-contract.md §4.3 §9.3 — 일곱 키다.
 *
 * <p>선언 순서가 곧 JSON 키 순서다. §9.3 의 예시와 같은 순서로 둔다.
 *
 * <p><b>{@code content} 를 넣지 않는다.</b> 본문은 10,000자까지 허용되므로
 * (sp-docs/requirements/board.md §4) 한 페이지 50건이면 500,000자가 된다 (§4.1, §4.3). 본문이
 * 필요한 상세는 {@link PostDetailResponse} 다.
 *
 * <p><b>{@code deleted}·{@code updatedAt} 을 넣지 않는다.</b> 삭제된 게시글은 목록에서
 * 제외되고 상세는 404 {@code P001} 이므로 돌아온 글은 항상 활성이고, {@code updated_at} 은
 * 감사 흔적으로 쓰지 않으며 노출 요구가 없다 (§4.3, sp-docs/domain-model.md §1.1).
 *
 * <p>목록(B-06)과 내가 쓴 글(B-09)이 같은 모양을 쓴다 (§4.3).
 *
 * @param createdAt 오프셋을 표기하지 않는다 (sp-docs/tech-stack.md §6)
 */
public record PostResponse(
		Long id,
		String title,
		Long writerId,
		String writerNickname,
		long viewCount,
		int commentCount,
		LocalDateTime createdAt
) {

	public static PostResponse from(Post post) {
		return new PostResponse(
				post.getId(),
				post.getTitle(),
				post.getWriterId(),
				post.getWriterNickname(),
				post.getViewCount(),
				post.getCommentCount(),
				post.getCreatedAt()
		);
	}

}
