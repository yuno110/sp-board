package com.example.board.comment.repository;

import com.example.board.comment.entity.Comment;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 댓글 리포지터리. */
public interface CommentRepository extends JpaRepository<Comment, Long> {

	/**
	 * 한 게시글의 활성 댓글 한 페이지. <b>등록순이다</b> — {@code created_at ASC, id ASC}
	 * (sp-docs/api-contract.md §7.1, sp-docs/requirements/board.md §2 의 C-02).
	 *
	 * <p><b>정렬을 메서드 이름에 박는다.</b> 호출 측이 {@code Sort} 를 빼먹으면 순서가 정해지지
	 * 않는데, {@code OFFSET} 페이징에서 그러면 같은 행이 두 페이지에 나오거나 어느 페이지에도
	 * 나오지 않는다 (§7.1). 이름에 있으면 빼먹을 자리가 없다.
	 *
	 * <p><b>{@code id} 가 마지막 정렬 키인 것이 정렬을 전순서로 만든다</b> (§7.1). 동률은 드문
	 * 경우가 아니다 — MySQL {@code DATETIME} 은 초 단위라 같은 초에 등록된 댓글의
	 * {@code created_at} 이 같다 (sp-docs/tech-stack.md §6).
	 *
	 * <p><b>삭제된 댓글은 목록에서 제외한다</b> (sp-docs/requirements/board.md §5 규칙 1).
	 * {@code deleted = false} 가 목록 쿼리와 건수 쿼리 양쪽에 붙도록 조건을 이름에 둔다 —
	 * 파생 쿼리의 건수는 같은 {@code where} 로 만들어진다.
	 */
	Page<Comment> findByPostIdAndDeletedFalseOrderByCreatedAtAscIdAsc(Long postId, Pageable pageable);

	/**
	 * 활성 댓글 한 건. 없으면 404 {@code CM001} 이다 (sp-docs/api-contract.md §8.3).
	 *
	 * <p><b>삭제된 댓글은 없는 것으로 본다</b> (sp-docs/requirements/board.md §5 규칙 1). 행은
	 * 남아 있지만 (§5 규칙 2) 수정·삭제의 대상이 아니다. {@code PostRepository} 의
	 * {@code findByIdAndDeletedFalse} 와 같은 모양이고, <b>조회가 권한 판정보다 먼저여야 하므로
	 * 이 조건이 쿼리에 있어야 한다</b> — 타인의 삭제된 댓글은 404 {@code CM001} 이고 403
	 * {@code CM002} 가 아니다 (sp-docs/api-contract.md §4.4).
	 */
	Optional<Comment> findByIdAndDeletedFalse(Long id);

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
