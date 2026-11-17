package com.example.board.like.repository;

import com.example.board.like.entity.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 좋아요 리포지터리.
 *
 * <p>{@code like_count} 를 바꾸는 문장은 여기가 아니라 {@code PostRepository} 에 있다 — 그 문장이
 * 바꾸는 테이블이 {@code post} 다 (sp-docs/conventions.md §1.3).
 */
public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

	/**
	 * 이 회원이 이 글을 눌렀는가.
	 *
	 * <p>좋아요의 멱등 판정과 상세·수정 응답의 {@code liked} 가 이 확인을 쓴다
	 * (sp-docs/domain-model.md §6.1, sp-docs/plan/phase2-board.md §2.1). <b>회원당 한 번의 최종
	 * 판정은 이 확인이 아니라 {@code uk_post_like_post_account} 다</b> — 락 전의 일반 읽기는 앞서
	 * 커밋된 좋아요를 놓칠 수 있다 (§6.1).
	 */
	boolean existsByPostIdAndAccountId(Long postId, Long accountId);

	/**
	 * 이 회원이 이 글에 누른 좋아요를 지운다. 영향받은 행 수를 돌려준다 — 누르지 않았으면 0 행이다.
	 *
	 * <p>문장과 조건은 sp-docs/domain-model.md §6.1 의 {@code DELETE} 2) 그대로다. <b>영향 행 수가
	 * 취소의 최종 판정이다</b> — 0 행이면 부르는 쪽이 트랜잭션을 되돌려 앞서 줄인 {@code like_count}
	 * 도 되돌린다 (§6.1, §6 의 표). 행이 있는지 먼저 읽고 지우는 두 단계로 나누지 않는다 — 판정과
	 * 삭제가 한 문장이어야 같은 행을 노린 두 취소가 둘 다 1 행을 보지 않는다.
	 *
	 * <p><b>선언한 벌크 DELETE 다.</b> 이름이 파생 삭제와 같은 모양이지만 {@code @Query} 가 있으면
	 * 그것이 문장이다. 파생 삭제였다면 행을 읽은 뒤 엔티티마다 지우고 읽은 행의 수를 돌려준다(코드
	 * 읽기) — 판정과 삭제가 한 문장이 아니다.
	 *
	 * <p>{@code flushAutomatically}·{@code clearAutomatically} 를 켜지 않는다. 부르는 쪽이 이 문장
	 * 앞에 쓰기 대기 중인 엔티티 변경을 두지 않고, 이 문장 뒤에 {@code PostLike} 엔티티를 다시 읽지
	 * 않는다는 전제다. 다시 읽는 쪽이 생기면 {@code clearAutomatically} 를 다시 본다.
	 */
	@Modifying
	@Query("delete from PostLike pl where pl.post.id = :postId and pl.accountId = :accountId")
	int deleteByPostIdAndAccountId(@Param("postId") Long postId, @Param("accountId") Long accountId);

}
