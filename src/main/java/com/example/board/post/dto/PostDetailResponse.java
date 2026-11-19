package com.example.board.post.dto;

import com.example.board.attachment.dto.AttachmentResponse;
import com.example.board.post.entity.Post;
import com.example.board.post.entity.PostCategory;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 게시글 <b>상세</b>. 정본: sp-docs/api-contract.md §4.3 §9.6.
 *
 * <p>목록 항목({@link PostResponse})의 키에 {@code content}·{@code liked}·{@code attachments} 를
 * 더한 것이다 — §4.3 의 상세 줄 그대로다. {@code attachments} 는 B-24 가 더했다.
 *
 * <p>선언 순서가 곧 JSON 키 순서다. §9.6 의 예시와 같은 순서로 둔다.
 *
 * <p>작성 응답({@code POST /api/v1/posts})과 수정 응답도 이 모양을 쓴다. §4.3 이 정한 모양은
 * 둘뿐이므로 방금 만든 글을 돌려주는 데 <b>세 번째 모양을 만들지 않는다</b> — 본문까지 돌려주는
 * 상세가 작성 결과의 전부다.
 *
 * <p><b>{@code deleted}·{@code updatedAt} 을 넣지 않는다.</b> 삭제된 게시글은 404
 * {@code P001} 이므로 돌아온 글은 항상 활성이고, {@code updated_at} 은 감사 흔적으로 쓰지
 * 않으며 노출 요구가 없다 (§4.3, sp-docs/domain-model.md §1.1).
 *
 * <p><b>댓글을 함께 싣지 않는다.</b> {@code GET /posts/{postId}/comments} 가 따로 있다 (§4).
 * 여기에 댓글을 넣으면 그 엔드포인트가 두 벌이 된다 (§4.3).
 *
 * @param category {@link PostCategory} 의 이름으로 나간다 (§4.3)
 * @param viewCount 상세 조회면 조회수 판정이 커밋된 뒤에 시작된 읽기의 값이다 (§4.8)
 * @param likeCount {@code post.like_count} 그대로다 (§4.3)
 * @param liked 요청한 계정이 이 글을 눌렀는가. <b>토큰이 없는 상세 조회면 {@code null}</b> 이고 키는
 *        빠지지 않는다 — {@code false} 는 「이 계정은 누르지 않았다」, {@code null} 은 「누구인지
 *        모른다」 다 (§4.3). 작성 응답은 {@code false}, 수정 응답은 요청한 계정의 실제 상태다 (§4.4)
 * @param attachments 그 글의 활성 첨부를 올린 순서({@code id} 오름차순)로. 없으면 빈 배열이고 키는
 *        빠지지 않는다. 원소는 §4.7 의 첨부 하나의 모양 — 올리기 응답과 같은 {@link AttachmentResponse}
 *        다. 작성 응답은 빈 배열이다 — 첨부는 글을 만든 뒤 올린다 (§4.4)
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
		Boolean liked,
		List<AttachmentResponse> attachments,
		LocalDateTime createdAt
) {

	/**
	 * @param liked 요청한 계정이 눌렀는가. 그 계정을 모르면 {@code null}
	 * @param attachments 그 글의 활성 첨부, {@code id} 오름차순 — 순서는 부르는 쪽의 조회가 정한다
	 */
	public static PostDetailResponse from(Post post, Boolean liked, List<AttachmentResponse> attachments) {
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
				liked,
				List.copyOf(attachments),
				post.getCreatedAt()
		);
	}

}
