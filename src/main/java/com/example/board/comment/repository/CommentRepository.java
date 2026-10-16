package com.example.board.comment.repository;

import com.example.board.comment.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 댓글 리포지터리. */
public interface CommentRepository extends JpaRepository<Comment, Long> {

	/**
	 * 한 게시글의 댓글을 모두 논리 삭제한다. 영향받은 행 수를 돌려준다.
	 *
	 * <p>게시글 삭제 시 하위 댓글을 함께 논리 삭제한다 (sp-docs/requirements/board.md §5 규칙 3).
	 * 그때 {@code comment_count} 는 그대로 둔다 (sp-docs/domain-model.md §5).
	 *
	 * <p><b>B-07(게시글 삭제)이 쓴다.</b> 여기서 미리 만들어 B-07 이 B-08 의 산출물을 기다리지
	 * 않게 한다 (sp-docs/plan/phase1.md §2.5).
	 *
	 * <p>벌크 UPDATE 다. 1차의 게시글당 댓글 수에서 엔티티를 하나씩 올려 {@code softDelete()} 를
	 * 부르는 것보다 단순하고, 감사 컬럼({@code updated_at})을 건드리지 않는다 — 그 값을 감사
	 * 흔적으로 쓰지 않으므로 무해하다 (sp-docs/domain-model.md §1.1). 1차 영속성 컨텍스트와
	 * 어긋나는 것은 {@code clearAutomatically}·{@code flushAutomatically} 가 막는다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Comment c set c.deleted = true where c.post.id = :postId and c.deleted = false")
	int softDeleteByPostId(@Param("postId") Long postId);

}
