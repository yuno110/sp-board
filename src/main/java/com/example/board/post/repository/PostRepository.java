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
 *
 * <p><b>{@code view_count} 증가도 여기 있다</b> (sp-docs/plan/phase1.md §6 B-11). 집계와 같은
 * 이유다 — 관리 엔티티를 메모리에서 올리면 더티 체킹이 변경되지 않은 컬럼까지 UPDATE 에 실어
 * 그 트랜잭션이 읽은 값으로 다른 컬럼을 덮어쓴다.
 *
 * <p><b>{@code like_count}·{@code attachment_count} 의 좁은 UPDATE 도 여기 있다</b>
 * (sp-docs/domain-model.md §6, sp-docs/conventions.md §1.3). 근거 행은 {@code post_like}·
 * {@code attachment} 지만 문장이 바꾸는 테이블은 {@code post} 다 — {@code comment_count} 와 같은
 * 이유다.
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
	 * <p>{@code flushAutomatically} 는 켠다. 호출 시점에 쓰기 대기 중인 변경이 있으면 그것을 먼저
	 * 내보내 SQL 순서를 프로그램 순서와 맞춘다 — {@code softDeleteByPostId} 와 같은 이유다.
	 * <b>B-17 시점에 이 문장과 {@link #decreaseCommentCount} 를 부르는 쪽(댓글 작성·삭제)은 그것을
	 * 트랜잭션의 첫 쓰기로 부른다</b> — 작성은 INSERT 보다, 삭제는 댓글의 조건부 전환보다 먼저다
	 * (sp-docs/plan/phase2-board.md B-17). 그래서 그 호출들에는 내보낼 것이 없다 — B-17 이 두
	 * 문장에서 이 설정을 빼고 전체 테스트를 돌려 모두 통과했고, 그 시점의 스위트에는 이 설정을
	 * 관측하는 단언이 없다. 1차의 삭제는 엔티티의 {@code deleted} 를 바꾼 뒤 감소를 불러 이 설정이
	 * 그 변경을 먼저 내보냈다 — 발행 순서가 댓글 행 → {@code post} 였던 이유이고, B-17 이 순서를
	 * {@code post} 먼저로 뒤집으며 그 대기 중인 변경이 없어졌다. 켜 두는 것은 부르는 쪽이 이 문장
	 * 앞에 엔티티 쓰기를 두게 될 때 그 쓰기가 이 문장 뒤로 밀려 발행 순서가 프로그램 순서와
	 * 어긋나지 않게 하려는 것이다.
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

	/**
	 * {@code view_count} 를 1 올린다. 영향받은 행 수를 돌려준다.
	 *
	 * <p><b>좁은 UPDATE 다 — {@code view_count} 한 컬럼만 싣는다</b>
	 * (sp-docs/plan/phase1.md §6 B-11). 상세 조회가 읽어 둔 엔티티의 조회수를 메모리에서
	 * 올리면 더티 체킹이 변경되지 않은 컬럼까지 전부 UPDATE 에 싣고, <b>그 트랜잭션이 읽은
	 * {@code comment_count} 를 그대로 다시 쓴다</b> — SELECT 와 flush 사이에 커밋된 댓글
	 * 작성의 증가가 조회 하나로 소실된다. 조회수 자체도 같은 성질이어서 동시 조회 둘이 같은
	 * 값을 읽으면 하나가 소실된다. 더할 대상을 트랜잭션이 읽은 값이 아니라 <b>DB 의 현재
	 * 값</b>으로 두는 것이 이 문장이 고치는 지점이다.
	 *
	 * <p><b>{@code clearAutomatically} 를 켠다.</b> 부르는 쪽
	 * ({@code PostService.getDetail})이 이 문장 다음에 같은 게시글을 다시 읽어 응답을 만들고,
	 * 이 문장은 엔티티를 지나지 않으므로 영속성 컨텍스트에 남아 있는 사본은 증가 전 조회수를
	 * 들고 있다. 비우지 않으면 그 사본이 그대로 돌아와 <b>응답의 {@code viewCount} 가 증가 전
	 * 값이 된다</b> — 끈 상태로 돌려 측정한 것이다. 비우면 다음 읽기가 새 SELECT 가 되어 DB 가
	 * 올린 값을 본다.
	 *
	 * <p>{@code flushAutomatically} 는 켜지 않는다. 상세 조회는 이 문장 앞에 쓰기가 없어
	 * 내보낼 것이 없다 — 켤 이유가 있는 것은 호출 시점에 쓰기 대기 중인 변경을 들고 있는
	 * 경로다 (B-17 전의 댓글 삭제 경로가 그랬다).
	 *
	 * <p>{@code deleted} 조건을 이 문장에 두지 않는다. 삭제된 게시글을 없는 것으로 보는 판정은
	 * {@link com.example.board.post.support.PostReader} 가 하고, 부르는 쪽이 그 판정을 먼저
	 * 지난 뒤 이 문장을 발행한다 — 조건을 여기 복제하면 같은 규칙이 두 군데에서 각각 늙는다.
	 *
	 * <p><b>발행된 {@code update post} 가 정확히 하나인지를 보는 단언이 막는 것은 더티
	 * 엔티티다.</b> 이 문장을 그대로 두고 엔티티의 {@code increaseViewCount()} 를 함께 부르면
	 * 전체 컬럼 UPDATE 가 한 문장 더 붙는다 — 그 문장이 그 트랜잭션이 읽은 값을 다시 쓰므로
	 * 집계가 덮어써지고 조회수도 어긋난다. <b>엔티티를 올리는 자리가 이 문장 앞이어도
	 * 그렇다</b> — 이 문장은 {@code post} 를 보는 쿼리이므로 발행 전에 대기 중인
	 * {@code post} 변경이 자동으로 내보내진다 (측정값: 두 문장이 순서만 바뀌어 발행된다).
	 */
	@Modifying(clearAutomatically = true)
	@Query("update Post p set p.viewCount = p.viewCount + 1 where p.id = :id")
	int increaseViewCount(@Param("id") Long id);

	/**
	 * {@code like_count} 를 1 올린다. 영향받은 행 수를 돌려준다 — 글이 없거나 지워졌으면 0 행이다.
	 *
	 * <p>문장과 조건은 sp-docs/domain-model.md §6.1 의 {@code PUT} 1) 그대로다. <b>좁은 UPDATE 다
	 * — {@code like_count} 한 컬럼만 싣는다</b> (§6). 1 행이면 이 문장이 {@code post} 행의 배타
	 * 락을 쥔다. 좋아요의 락 순서가 이 문장을 {@code post_like} 쓰기보다 먼저 두게 하는 이유다
	 * (§6.1).
	 *
	 * <p><b>{@code clearAutomatically} 를 켠다.</b> 부르는 쪽이 이 문장 다음에 {@code post} 를
	 * 다시 읽는다 — 응답의 {@code likeCount} 는 쓴 뒤(되돌렸으면 되돌린 뒤) 읽은 값이다 (§6.1).
	 * 이 문장은 엔티티를 지나지 않으므로 영속성 컨텍스트에 남은 사본은 증가 전 값을 들고 있고,
	 * 비우지 않으면 다시 읽기가 그 사본을 돌려줄 수 있다 — {@link #increaseViewCount} 를 끈
	 * 상태로 측정한 결함과 같은 모양이다 (sp-docs/plan/phase1.md §6 B-11). 응답 값으로 이 설정을
	 * 관측하는 것은 그 응답을 만드는 항목이다 (sp-docs/plan/phase2-board.md B-14).
	 *
	 * <p><b>{@code flushAutomatically} 도 켠다.</b> 비우기는 내보내지 않은 변경을 버린다. 먼저
	 * 내보내야 부르는 쪽이 이 문장 앞에 남긴 쓰기가 비우기에 조용히 사라지지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Post p set p.likeCount = p.likeCount + 1 where p.id = :id and p.deleted = false")
	int increaseLikeCount(@Param("id") Long id);

	/**
	 * {@code like_count} 를 1 내린다. 영향받은 행 수를 돌려준다 — 글이 없거나 지워졌거나 집계가
	 * 이미 0 이면 0 행이다.
	 *
	 * <p>문장과 조건은 sp-docs/domain-model.md §6.1 의 {@code DELETE} 1) 그대로다. <b>음수 방지가
	 * {@code where} 절에 있다</b> ({@code p.likeCount > 0}, §6). 0 행의 두 경우를 가르는 것은
	 * 부르는 쪽이다 (§6.1).
	 *
	 * <p>{@code @Modifying} 설정과 이유는 {@link #increaseLikeCount} 와 같다. 0 행일 때 글이
	 * 있는지 다시 읽어 가르는 것도 이 문장 다음의 {@code post} 읽기다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Post p set p.likeCount = p.likeCount - 1 "
			+ "where p.id = :id and p.deleted = false and p.likeCount > 0")
	int decreaseLikeCount(@Param("id") Long id);

	/**
	 * {@code attachment_count} 를 1 올린다. 영향받은 행 수를 돌려준다 — 글이 없거나 지워졌거나 활성
	 * 첨부가 이미 {@code limit} 개면 0 행이다.
	 *
	 * <p>문장과 조건은 sp-docs/domain-model.md §6.2 의 1) 그대로다. <b>개수 상한의 판정은 이 문장
	 * 하나다</b> — 세어 보고 넣는 두 단계({@code SELECT count} → INSERT)는 동시 업로드에서 상한을
	 * 넘는다 (§6.2). 1 행이면 이 문장이 {@code post} 행의 배타 락을 먼저 쥐므로 이어지는
	 * {@code attachment} INSERT 의 FK 공유 락이 문제가 되지 않는다 (§6.2).
	 *
	 * <p><b>상한은 인자다.</b> 값(10)은 sp-docs/requirements/board.md §11 의 규칙이고, 이 문장은
	 * 판정의 모양({@code attachment_count < 상한})만 갖는다.
	 *
	 * <p><b>{@code clearAutomatically} 를 켠다.</b> 0 행이면 부르는 쪽이 글을 다시 읽어 404
	 * {@code P001} 과 409 {@code AT003} 을 가른다 (§6.2). 그 글의 사본이 영속성 컨텍스트에 남아
	 * 있으면 다시 읽기가 그 사본을 돌려줄 수 있다 — 비우면 다음 읽기가 새 SELECT 다.
	 * {@code flushAutomatically} 를 함께 켜는 이유는 {@link #increaseLikeCount} 와 같다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Post p set p.attachmentCount = p.attachmentCount + 1 "
			+ "where p.id = :id and p.deleted = false and p.attachmentCount < :limit")
	int increaseAttachmentCount(@Param("id") Long id, @Param("limit") int limit);

	/**
	 * {@code attachment_count} 를 1 내린다. 영향받은 행 수를 돌려준다 — 집계가 이미 0 이면 0 행이다.
	 *
	 * <p>문장과 조건은 sp-docs/domain-model.md §6.2 의 첨부 삭제 1) 그대로다. <b>음수 방지가
	 * {@code where} 절에 있다</b> ({@code p.attachmentCount > 0}). {@code deleted} 조건이 없는 것도
	 * 그 문장의 모양이다. 0 행은 활성 첨부가 있는데 집계가 이미 0 인, 근거 행과 어긋난 상태이고 그
	 * 처리는 부르는 쪽이 한다 (§6.2).
	 *
	 * <p>{@code clearAutomatically} 를 켜지 않는다. 부르는 쪽이 이 문장 다음에 {@code post} 를 다시
	 * 읽지 않는다 — 0 행이어도 글을 읽지 않고 다음 단계로 가며 (§6.2), 첨부 삭제의 응답에는
	 * {@code attachment_count} 가 없다 (sp-docs/plan/phase2-board.md B-14). 그래서 켜든 끄든 응답이
	 * 같고, 끄는 쪽은 부르는 쪽이 쥔 엔티티를 영속성 컨텍스트에 남긴다.
	 *
	 * <p>{@code flushAutomatically} 는 켠다. 호출 시점에 쓰기 대기 중인 변경이 있으면 그것을 먼저
	 * 내보내 SQL 순서를 프로그램 순서와 맞춘다 — {@link #increaseCommentCount} 와 같은 이유다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update Post p set p.attachmentCount = p.attachmentCount - 1 "
			+ "where p.id = :id and p.attachmentCount > 0")
	int decreaseAttachmentCount(@Param("id") Long id);

}
