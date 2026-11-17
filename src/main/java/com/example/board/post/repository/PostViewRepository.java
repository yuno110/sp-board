package com.example.board.post.repository;

import com.example.board.post.entity.PostView;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 조회수 판정 행 리포지터리 (sp-docs/domain-model.md §4.5).
 *
 * <p>판정의 문장(조건부 UPDATE·존재 확인)과 정리 작업의 삭제가 여기 있다 — 부르는 쪽은
 * {@code post/support} 의 {@code ViewCountJudge}·{@code PostViewCleanup} 이다
 * (sp-docs/requirements/board.md §9.3). 판정 행의 INSERT 는 상속한 {@code saveAndFlush} 다.
 * {@code view_count} 를 바꾸는 문장은 여기가 아니라 {@code PostRepository} 에 있다.
 *
 * <p><b>시각은 모두 인자다</b> — 앱 시계의 값이고 DB 의 {@code NOW()} 를 쓰지 않는다 (§9.2).
 *
 * <p>{@code @Modifying} 문장은 부르는 쪽의 트랜잭션 안에서만 돈다 — 판정과 정리가 각자의 템플릿으로
 * 연다 (sp-docs/conventions.md §5 가 리포지터리에 {@code @Transactional} 을 붙이지 않게 한다).
 */
public interface PostViewRepository extends JpaRepository<PostView, Long> {

	/**
	 * 이 글·이 판정 키의 행이 있고 기준 시각이 {@code cutoff} 이하이면 기준 시각을 {@code now} 로
	 * 옮긴다. 영향받은 행 수를 돌려준다 — 1 이면 집계할 조회다.
	 *
	 * <p>sp-docs/requirements/board.md §9.3 의 판정 첫 문장이다. 0 행은 행이 없거나 24시간 안이라는
	 * 뜻이고 둘을 가르는 것은 {@link #existsByPostIdAndViewerKey} 다 — §9.3 이 그 둘을 한 문장으로
	 * 가를 수 없다고 적었다. 24시간 안의 행은 이 문장이 건드리지 않으므로 창이 밀리지 않는다 (§9.2).
	 *
	 * <p>{@code flushAutomatically}·{@code clearAutomatically} 를 켜지 않는다. 판정 트랜잭션의 첫
	 * 문장이라 내보낼 변경이 없고, 판정은 이 문장 뒤에 {@link PostView} 엔티티를 읽지 않는다.
	 */
	@Modifying
	@Query("update PostView v set v.viewedAt = :now "
			+ "where v.postId = :postId and v.viewerKey = :viewerKey and v.viewedAt <= :cutoff")
	int renewViewedAt(
			@Param("postId") Long postId,
			@Param("viewerKey") String viewerKey,
			@Param("now") LocalDateTime now,
			@Param("cutoff") LocalDateTime cutoff);

	/**
	 * 이 글·이 판정 키의 행이 있는가. 조건부 UPDATE({@link #renewViewedAt})가 0 행일 때 판정이 부른다
	 * — 있으면 24시간 안이므로 집계하지 않고 INSERT 하지도 않는다 (sp-docs/requirements/board.md §9.3).
	 *
	 * <p><b>같은 키의 동시 첫 조회를 가르는 최종 판정은 이 확인이 아니라
	 * {@code uk_post_view_post_viewer} 다</b> — 이 확인과 INSERT 사이에 다른 트랜잭션이 같은 행을
	 * 커밋할 수 있다 (§9.3).
	 */
	boolean existsByPostIdAndViewerKey(Long postId, String viewerKey);

	/**
	 * 기준 시각이 {@code cutoff} 보다 이른 행을 지운다. 영향받은 행 수를 돌려준다.
	 *
	 * <p>정리 작업의 문장이다 (sp-docs/requirements/board.md §9.3, sp-docs/tech-stack.md §4.5). 조건이
	 * {@code viewed_at} 하나라서 §4.5 가 적은 대로 {@code idx_post_view_viewed_at} 를 쓸 자리가 된다.
	 *
	 * <p><b>선언한 벌크 DELETE 다.</b> 파생 삭제({@code deleteBy…})였다면 행을 읽은 뒤 엔티티마다
	 * 지운다(코드 읽기) — 지울 행을 전부 영속성 컨텍스트로 올린다.
	 *
	 * <p>{@code flushAutomatically}·{@code clearAutomatically} 를 켜지 않는다. 정리 작업의 트랜잭션에서
	 * 이 문장 앞에 바꾼 엔티티가 없어 내보낼 변경이 없고, 이 문장 뒤에 {@link PostView} 엔티티를 읽지
	 * 않는다.
	 */
	@Modifying
	@Query("delete from PostView v where v.viewedAt < :cutoff")
	int deleteViewedBefore(@Param("cutoff") LocalDateTime cutoff);

}
