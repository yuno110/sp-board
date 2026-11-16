package com.example.board.post.dto;

import com.example.board.post.entity.Post;
import com.example.board.post.entity.PostCategory;
import java.time.LocalDateTime;

/**
 * 게시글 <b>상세</b>. 정본: sp-docs/api-contract.md §4.3 §9.6.
 *
 * <p><b>B-19(2026-11-13) 시점의 모양은 정본의 상세 줄과 같지 않다.</b> 목록 항목
 * ({@link PostResponse})의 키에 {@code content} 를 더한 것이고, §4.3 의 상세 줄에 있는
 * {@code liked}·{@code attachments} 는 싣지 않았다 — 계획이 그 둘을 B-21·B-24 에 배정했다
 * (sp-docs/plan/phase2-board.md 의 B-19 완료 기준).
 *
 * <p>선언 순서가 곧 JSON 키 순서다. §9.6 의 예시와 같은 순서로 둔다.
 *
 * <p>작성 응답({@code POST /api/v1/posts})도 이 모양을 쓴다. §4.3 이 정한 모양은 둘뿐이므로
 * 방금 만든 글을 돌려주는 데 <b>세 번째 모양을 만들지 않는다</b> — 본문까지 돌려주는 상세가
 * 작성 결과의 전부다.
 *
 * <p><b>{@code deleted}·{@code updatedAt} 을 넣지 않는다.</b> 삭제된 게시글은 404
 * {@code P001} 이므로 돌아온 글은 항상 활성이고, {@code updated_at} 은 감사 흔적으로 쓰지
 * 않으며 노출 요구가 없다 (§4.3, sp-docs/domain-model.md §1.1).
 *
 * <p><b>댓글을 함께 싣지 않는다.</b> {@code GET /posts/{postId}/comments} 가 따로 있다 (§4).
 * 여기에 댓글을 넣으면 그 엔드포인트가 두 벌이 된다 (§4.3).
 *
 * @param category {@link PostCategory} 의 이름으로 나간다 (§4.3)
 * @param likeCount {@code post.like_count} 그대로다 (§4.3)
 * @param createdAt 오프셋을 표기하지 않는다 (sp-docs/tech-stack.md §6)
 */
public record PostDetailResponse(
		Long id,
		String title,
		PostCategory category,
		String content,
		Long writerId,
		String writerNickname,
		long viewCount,
		int commentCount,
		int likeCount,
		LocalDateTime createdAt
) {

	public static PostDetailResponse from(Post post) {
		return new PostDetailResponse(
				post.getId(),
				post.getTitle(),
				post.getCategory(),
				post.getContent(),
				post.getWriterId(),
				post.getWriterNickname(),
				post.getViewCount(),
				post.getCommentCount(),
				post.getLikeCount(),
				post.getCreatedAt()
		);
	}

}
