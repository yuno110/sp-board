package com.example.board.post.repository;

import com.example.board.post.entity.Post;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 게시글 리포지터리.
 *
 * <p>동적 검색(QueryDSL)은 {@code PostQueryRepository} 가 맡는다 — B-06 의 산출물이다
 * (sp-docs/plan/phase1.md §2.2).
 *
 * <p><b>{@code comment_count} 갱신이 여기 있다</b> (sp-docs/domain-model.md §5). 갱신하는 쪽은
 * 댓글 작성·삭제이지만 문장이 바꾸는 테이블은 {@code post} 다 — "{@code comment_count} 는
 * 어디서 바뀌는가" 를 이 인터페이스에서 찾을 수 있어야 한다.
 */
public interface PostRepository extends JpaRepository<Post, Long> {

	/**
	 * 삭제되지 않은 게시글 한 건.
	 *
	 * <p>삭제된 게시글은 목록·상세에서 제외한다 (sp-docs/requirements/board.md §5 규칙 1).
	 * 그 판정을 {@link com.example.board.post.support.PostReader} 한 곳에 모으기 위해
	 * {@code deleted = false} 를 쿼리에 둔다.
	 */
	Optional<Post> findByIdAndDeletedFalse(Long id);

	/**
	 * {@code comment_count} 를 1 올린다. 영향받은 행 수를 돌려준다.
	 *
	 * <p><b>좁은 UPDATE 다 — {@code comment_count} 한 컬럼만 싣는다</b>
	 * (sp-docs/plan/phase1.md §6 B-10). 엔티티를 읽어 메모리에서 올리면 더티 체킹이 변경되지
	 * 않은 컬럼까지 전부 UPDATE 에 싣고, <b>동시 커밋에서 낡은 스냅샷째 덮어써 증가가
	 * 소실된다</b> — 아무 오류 없이 틀린 수가 남는다 (sp-docs/nfr.md §1 의 I-03 실측,
	 * sp-docs/AGENTS.md 불변식 9). 소실은 DB 가 아니라 애플리케이션에서 일어나므로 이 문장이
	 * 고치는 지점이다: 더할 대상을 트랜잭션이 읽은 값이 아니라 <b>DB 의 현재 값</b>으로 둔다.
	 *
	 * <p>{@code clearAutomatically} 를 켜지 않는다. 부르는 쪽이 이 문장 다음에 {@code post}
	 * 엔티티의 필드를 다시 읽지 않으므로 <b>켜든 끄든 무해하고</b>, 그래서 <b>이 설정을
	 * 관측하는 단언이 없다.</b> 끄는 쪽을 택한 이유는 그쪽이 엔티티를 영속성 컨텍스트에
	 * <b>깨끗한 상태로 남기는 것</b>뿐이다 — 끄고 남는 것은 그 트랜잭션의 {@code post} 가
	 * 낡은 {@code commentCount} 를 들고 있는 것이고, 그 값을 다시 읽지 않으므로 틀린 수가
	 * 밖으로 나가지 않는다.
	 *
	 * <p><b>발행된 {@code update post} 가 정확히 하나인지를 보는 단언이 막는 것은 이 설정이
	 * 아니라 더티 엔티티다</b> (sp-docs/plan/phase1.md §6 B-10). 이 문장을 그대로 두고
	 * 엔티티의 집계 메서드를 함께 부르면 전체 컬럼 UPDATE 가 한 문장 더 붙어 이 문장을
	 * 덮어쓴다 — 그 단언이 보는 것은 그것이다.
	 *
	 * <p>{@code flushAutomatically} 는 켠다. 호출 시점에 쓰기 대기 중인 변경이 있으면
	 * ({@code comment.deleted}) 그것을 먼저 내보내 SQL 순서를 프로그램 순서와 맞춘다 —
	 * {@code softDeleteByPostId} 와 같은 이유다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update Post p set p.commentCount = p.commentCount + 1 where p.id = :id")
	int increaseCommentCount(@Param("id") Long id);

	/**
	 * {@code comment_count} 를 1 내린다. 영향받은 행 수를 돌려준다 — 이미 0 이면 0 행이다.
	 *
	 * <p><b>음수 방지가 {@code where} 절에 있다</b> ({@code p.commentCount > 0},
	 * sp-docs/requirements/board.md §5 규칙 4, sp-docs/domain-model.md §5). 좁은 UPDATE 는
	 * 엔티티를 지나지 않으므로 가드를 엔티티 메서드에 두면 지나가지 않는다. 조건을 문장에 두면
	 * <b>판정과 차감이 한 문장이어서</b> 그 사이에 다른 트랜잭션이 끼어들 자리도 없다.
	 *
	 * <p>나머지는 {@link #increaseCommentCount} 와 같다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update Post p set p.commentCount = p.commentCount - 1 "
			+ "where p.id = :id and p.commentCount > 0")
	int decreaseCommentCount(@Param("id") Long id);

}
