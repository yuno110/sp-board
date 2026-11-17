package com.example.board.comment.repository;

import com.example.board.comment.entity.Comment;
import java.util.Collection;
import java.util.List;
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
	 * 한 게시글의 목록에 오는 최상위 댓글 한 페이지 (sp-docs/requirements/board.md §12,
	 * sp-docs/api-contract.md §4.5). <b>등록순이다</b> — {@code created_at ASC, id ASC} (§4.5, §7.1).
	 *
	 * <p><b>목록에 오는 최상위는 활성 최상위 댓글과 활성 답글이 있는 삭제된 최상위 댓글이다</b>
	 * (§4.5, sp-docs/requirements/board.md §5 규칙 1 의 예외). 뒤의 것은 응답에서 내용·작성자를
	 * 가린 자리가 된다 ({@code CommentResponse.from}). 활성 답글이 없어지면 이 조건에서 빠지므로
	 * 자리도 사라진다.
	 *
	 * <p><b>건수 쿼리를 따로 적고 목록 쿼리와 같은 {@code where} 를 둔다.</b> 건수가
	 * {@code totalElements} 이고 그 값은 최상위 항목의 수다 (§4.5) — {@code comment_count}(답글을
	 * 포함한 활성 댓글 수)와 다르다. 두 쿼리의 조건이 갈리면 페이지가 꽉 찰 때 그 값이 어긋난다 —
	 * 꽉 차지 않은 페이지의 {@code totalElements} 는 건수 쿼리가 아니라 콘텐츠 크기에서 나온다
	 * (sp-docs/process/review-policy.md §4.1 의 {@code PageImpl} 블록인용).
	 *
	 * <p><b>정렬을 쿼리에 박는다.</b> 호출 측이 {@code Sort} 를 넘기지 않는다 — 넘기지 않으면 순서가
	 * 정해지지 않는 쪽이 아니라 이 쿼리의 순서가 된다. {@code OFFSET} 페이징에서 순서가 정해지지
	 * 않으면 같은 행이 두 페이지에 나오거나 어느 페이지에도 나오지 않는다 (§7.1). <b>{@code id} 가
	 * 마지막 정렬 키인 것이 정렬을 전순서로 만든다</b> — MySQL {@code DATETIME} 은 초 단위라 같은
	 * 초에 등록된 댓글의 {@code created_at} 이 같다 (sp-docs/tech-stack.md §6).
	 */
	@Query(value = """
			select c from Comment c
			where c.post.id = :postId and c.parent is null
			  and (c.deleted = false
			       or exists (select r.id from Comment r where r.parent = c and r.deleted = false))
			order by c.createdAt asc, c.id asc""",
			countQuery = """
			select count(c) from Comment c
			where c.post.id = :postId and c.parent is null
			  and (c.deleted = false
			       or exists (select r.id from Comment r where r.parent = c and r.deleted = false))""")
	Page<Comment> findListedTopLevels(@Param("postId") Long postId, Pageable pageable);

	/**
	 * 주어진 최상위 댓글들의 활성 답글. <b>등록순이다</b> — {@code created_at ASC, id ASC}
	 * (sp-docs/api-contract.md §4.5). 부르는 쪽이 부모별로 나눠도 각 부모 안의 순서가 이 순서다.
	 *
	 * <p><b>한 페이지의 답글을 한 번에 읽는다</b> — 최상위마다 읽지 않는다. 그래서 목록의 쿼리 수가
	 * 최상위 항목 수·답글 수와 무관하다 (sp-docs/plan/phase2-board.md B-22).
	 * {@code idx_comment_parent_id} 가 이 조회를 위한 인덱스다 (sp-docs/domain-model.md §4.3).
	 *
	 * <p><b>지운 답글은 싣지 않는다</b> ({@code deleted = false}, §4.5).
	 */
	@Query("""
			select r from Comment r
			where r.parent.id in :parentIds and r.deleted = false
			order by r.createdAt asc, r.id asc""")
	List<Comment> findActiveRepliesOf(@Param("parentIds") Collection<Long> parentIds);

	/**
	 * 답글의 부모가 될 수 있는 댓글 — <b>같은 글의 활성 댓글</b> 한 건
	 * (sp-docs/requirements/board.md §12 의 「부모가 될 수 없는 것」). 없으면 404 {@code CM001} 이다.
	 *
	 * <p><b>없는 댓글, 다른 글의 댓글, 삭제된 댓글이 이 한 조회에서 함께 빠진다</b> — §12 가 그것들을
	 * 모두 {@code CM001} 로 정했다. 부모가 최상위인가(400 {@code CM003})는 이 조회 뒤에 부르는 쪽이 본다.
	 * 그래서 다른 글의 <b>답글</b>을 부모로 주면 {@code CM003} 이 아니라 {@code CM001} 이다 — §12 의
	 * 판정 순서다.
	 *
	 * <p><b>잠그지 않는 읽기다.</b> 확인 뒤 커밋 전에 부모가 지워지면 삭제된 부모 아래에 답글이 생길
	 * 수 있고, §12 가 그 창을 닫지 않기로 했다 ({@code CommentService.create}).
	 */
	Optional<Comment> findByIdAndPostIdAndDeletedFalse(Long id, Long postId);

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
	 * 활성 댓글 한 건을 논리 삭제한다. 영향받은 행 수를 돌려준다 — 이미 지워졌으면 0 행이다.
	 *
	 * <p>문장과 조건은 sp-docs/plan/phase2-board.md B-17 의 2) 그대로다 — sp-docs/domain-model.md
	 * §6.2 의 첨부 삭제 2) 와 같은 모양이다. <b>활성인지의 판정과 전환이 한 문장이다</b>
	 * ({@code deleted = false} 조건, sp-docs/domain-model.md §6 의 표). 삭제의 404 {@code CM001}
	 * 판정({@link #findByIdAndDeletedFalse})은 락 전의 읽기라 같은 댓글의 동시 삭제를 거르지
	 * 못한다 — 모두 활성으로 읽을 수 있다. 그래서 이 문장의 영향 행 수가 최종 판정이고, 0 행이면
	 * 부르는 쪽이 트랜잭션을 되돌린다.
	 *
	 * <p><b>엔티티의 {@code softDelete()} 로 바꿔 더티 체킹에 맡기지 않는다.</b> 그 UPDATE 는
	 * {@code where} 에 {@code id} 만 건다 — 1차의 삭제가 그 모양이었고, B-17 이 고치기 전의 코드에서
	 * 잰 값으로 다른 트랜잭션이 먼저 지운 댓글의 삭제가 예외 없이 끝났고, 같은 댓글의 동시 삭제
	 * 3건이 모두 성공해 {@code comment_count} 를 3 줄였다 ({@code CommentServiceTest} 의 B-17 중첩
	 * 클래스).
	 *
	 * <p>{@code updated_at} 을 바꾸지 않는다 — 벌크 UPDATE 는 감사 리스너를 지나지 않는다.
	 * {@link #softDeleteByPostId} 와 같고, 그 값을 감사 흔적으로 쓰지 않으며
	 * (sp-docs/domain-model.md §1.1) 댓글 응답에도 없다 (sp-docs/api-contract.md §4.5).
	 *
	 * <p>{@code flushAutomatically}·{@code clearAutomatically} 를 켜지 않는다. 부르는 쪽이 이
	 * 문장 앞에 쓰기 대기 중인 엔티티 변경을 두지 않고, 이 문장 뒤에 그 댓글 엔티티를 다시 읽지
	 * 않는다는 전제다 — 벌크 UPDATE 는 영속성 컨텍스트를 지나지 않아 거기 남은 사본은
	 * {@code deleted = false} 를 그대로 들고 있으므로, 다시 읽는 쪽이 생기면
	 * {@code clearAutomatically} 를 다시 본다.
	 */
	@Modifying
	@Query("update Comment c set c.deleted = true where c.id = :id and c.deleted = false")
	int softDeleteIfActive(@Param("id") Long id);

	/**
	 * 한 게시글의 댓글을 모두 논리 삭제한다. 영향받은 행 수를 돌려준다.
	 *
	 * <p>게시글 삭제 시 하위 댓글을 함께 논리 삭제한다 (sp-docs/requirements/board.md §5 규칙 3).
	 * 그때 {@code comment_count} 는 그대로 둔다 (sp-docs/domain-model.md §5). 규칙 3 의 「하위
	 * 댓글(답글 포함)」 은 이 조건이 지킨다 — 답글도 그 글의 {@code post_id} 를 가진다.
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
