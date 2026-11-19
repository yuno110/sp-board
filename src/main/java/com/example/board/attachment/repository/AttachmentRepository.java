package com.example.board.attachment.repository;

import com.example.board.attachment.entity.Attachment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 첨부 리포지터리 (sp-docs/domain-model.md §4.6).
 *
 * <p>첨부의 문장은 그 경로를 만드는 기능 항목이 더한다 (sp-docs/plan/phase2-board.md §2.1).
 * {@code attachment_count} 를 바꾸는 문장은 여기가 아니라 {@code PostRepository} 에 있다 — 그
 * 문장이 바꾸는 테이블이 {@code post} 다 (sp-docs/conventions.md §1.3).
 */
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

	/**
	 * 지워지지 않은 첨부 한 건과 그 글. 내려받기와 지우기의 404 {@code AT001} 판정이다.
	 *
	 * <p><b>글의 {@code deleted} 는 조건에 없다.</b> 지우기는 그 글이 지워졌어도 첨부가 활성이면 판정을
	 * 이어 가고 (sp-docs/requirements/board.md §11 의 판정 순서는 없는 첨부 → 작성자인가 뿐이다),
	 * 내려받기는 지워진 글을 404 로 거른다 — 그 판정은 부르는 쪽이 함께 읽은 글로 한다.
	 *
	 * <p>글을 함께 읽는다({@code @EntityGraph}). 지우기는 글의 {@code writer_id} 를, 내려받기는 글의
	 * {@code deleted} 를 본다.
	 *
	 * <p><b>락 전의 읽기다.</b> 같은 첨부의 동시 삭제를 거르지 못한다 — 지우기의 최종 판정은
	 * {@link #softDeleteIfActive} 의 영향 행 수다 (sp-docs/domain-model.md §6.2).
	 */
	@EntityGraph(attributePaths = "post")
	Optional<Attachment> findByIdAndDeletedFalse(Long id);

	/**
	 * 그 글의 활성 첨부를 올린 순서({@code id} 오름차순)로. 상세·수정 응답의 {@code attachments}
	 * 다 (sp-docs/requirements/board.md §11 의 「상세 응답」 줄, sp-docs/api-contract.md §4.3).
	 *
	 * <p>글은 함께 읽지 않는다 — 첨부 하나의 모양(§4.7)에 글의 값이 없다.
	 */
	List<Attachment> findByPostIdAndDeletedFalseOrderByIdAsc(Long postId);

	/**
	 * 첨부를 지운 것으로 바꾼다 — <b>활성일 때만</b>. 영향받은 행 수를 돌려준다.
	 *
	 * <p>문장과 조건은 sp-docs/domain-model.md §6.2 의 첨부 삭제 2) 그대로다. <b>활성인지의 판정과
	 * 전환이 한 문장이다</b>({@code deleted = false} 조건). 지우기의 404 {@code AT001} 판정
	 * ({@link #findByIdAndDeletedFalse})은 락 전의 읽기라 같은 첨부의 동시 삭제를 거르지 못한다 —
	 * 모두 활성으로 읽을 수 있다. 그래서 이 문장의 영향 행 수가 최종 판정이고, 0 행이면 부르는 쪽이
	 * 트랜잭션을 되돌린다(앞의 {@code attachment_count} 감소도 되돌아간다).
	 *
	 * <p>{@code updated_at} 을 바꾸지 않는다 — 벌크 UPDATE 는 감사 리스너를 지나지 않는다. 그 값은 첨부
	 * 하나의 모양(sp-docs/api-contract.md §4.7)에 없다.
	 *
	 * <p><b>{@code flushAutomatically}·{@code clearAutomatically} 를 켜지 않는다.</b> 부르는 쪽(첨부
	 * 지우기)이 이 문장 앞에 쓰기 대기 중인 엔티티 변경을 두지 않고 — 앞의 문장도 엔티티를 지나지 않는
	 * 좁은 UPDATE 다 — 이 문장 뒤에 그 첨부도 그 글도 다시 읽지 않으며, 지우기의 응답은 본문이 없는
	 * 204 다 (§4.7). 벌크 UPDATE 는 영속성 컨텍스트를 지나지 않아 거기 남은 첨부의 사본은
	 * {@code deleted = false} 를 그대로 들고 있으므로, 같은 영속성 컨텍스트에서 이 문장 뒤에 그 첨부를
	 * 다시 읽는 쪽이 생기면 {@code clearAutomatically} 를 다시 본다 —
	 * sp-docs/plan/phase2-board.md B-14 의 {@code @Modifying} 문단이 적은 조건이다.
	 */
	@Modifying
	@Query("update Attachment a set a.deleted = true where a.id = :id and a.deleted = false")
	int softDeleteIfActive(@Param("id") Long id);

}
