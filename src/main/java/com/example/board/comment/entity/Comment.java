package com.example.board.comment.entity;

import com.example.board.global.common.BaseTimeEntity;
import com.example.board.post.entity.Post;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 댓글. 정본: sp-docs/domain-model.md §4.2.
 *
 * <p><b>{@code post} 는 연관관계 매핑이다.</b> 같은 서비스(같은 스키마) 안이므로
 * {@code @ManyToOne(LAZY)} 를 쓴다. 반면 {@code writerId} 는 {@code sp_auth.account.id} 의
 * 논리 참조이므로 FK 를 두지 않는다 (sp-docs/requirements/board.md §3 규칙 4).
 *
 * <p>{@code content} 는 입력 규칙이 1~500자인데 컬럼이 500 으로 여유가 없다 (§4).
 * {@code ddl-auto: validate} 는 폭을 보지 않으므로 {@code CommentRepositoryTest} 가
 * {@code information_schema} 의 실제 폭과 대조한다 (sp-docs/tech-stack.md §5.2).
 *
 * <p>{@code @Setter}·{@code @Data} 를 두지 않는다 (sp-docs/conventions.md §2).
 * 연관관계를 포함한 {@code @ToString} 도 두지 않는다.
 */
@Getter
@Entity
@Table(name = "comment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "post_id", nullable = false)
	private Post post;

	@Column(nullable = false)
	private Long writerId;

	@Column(length = 30, nullable = false)
	private String writerNickname;

	@Column(length = 500, nullable = false)
	private String content;

	/**
	 * 논리 삭제 여부.
	 *
	 * <p>삭제해도 행을 남긴다 — "삭제된 댓글입니다" 로 표시할 수 있어야 하기 때문이다
	 * (sp-docs/requirements/board.md §5 규칙 2).
	 */
	@Column(nullable = false)
	private boolean deleted;

	/**
	 * 답글이면 그 부모(최상위 댓글), 최상위 댓글이면 {@code null} 이다
	 * (sp-docs/domain-model.md §4.2, sp-docs/requirements/board.md §12).
	 *
	 * <p>같은 테이블 안의 참조이므로 FK({@code fk_comment_parent_id})를 둔다. 깊이가 1단계라는
	 * 불변식 — 부모의 {@code parent} 는 언제나 {@code null} — 은 제약이 아니라 작성 경로의
	 * 판정(400 {@code CM003})이 지킨다. 이 엔티티는 그 판정을 하지 않는다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_id")
	private Comment parent;

	/** {@code parent} 를 생략하면({@code null}) 최상위 댓글이다. */
	@Builder
	private Comment(Post post, Long writerId, String writerNickname, String content,
			Comment parent) {
		this.post = post;
		this.writerId = writerId;
		this.writerNickname = writerNickname;
		this.content = content;
		this.parent = parent;
		this.deleted = false;
	}

	/** 내용 변경. 작성자 스냅샷은 건드리지 않는다 (sp-docs/requirements/board.md §3 규칙 7). */
	public void update(String content) {
		this.content = content;
	}

	/**
	 * 메모리 안의 논리 삭제. 행은 남는다.
	 *
	 * <p><b>댓글 삭제 경로는 이 메서드를 쓰지 않는다</b> — 전환은
	 * {@code CommentRepository.softDeleteIfActive} 의 조건부 UPDATE 가 한다
	 * (sp-docs/plan/phase2-board.md B-17). 이 메서드는 조건 없이 {@code deleted} 를 바꾸고 더티
	 * 체킹이 {@code where id = ?} 로만 저장하므로, 같은 댓글을 지우는 동시 요청이 모두 활성으로
	 * 읽으면 모두 성공해 집계를 그만큼 줄인다 — B-17 이 고치기 전의 코드에서 집계 4 인 글에 동시
	 * 삭제 3건을 보내 3건이 모두 성공해 3 줄인 결함이다 ({@code CommentServiceTest} 의 B-17 중첩 클래스). <b>삭제 경로를 이 메서드로
	 * 되돌리면 그 결함이 돌아온다.</b>
	 *
	 * <p><b>그래도 메서드가 남아 있는 이유는 B-03 이다.</b> B-03 의 도메인 메서드 표가 이 메서드를
	 * 담고, 완료 기준이 그 표의 메서드가 모두 구현되어 있기를 요구한다
	 * (sp-docs/plan/phase1.md §6 B-03) — {@code Post.increaseCommentCount()} 가 남아 있는 이유와
	 * 같다.
	 */
	public void softDelete() {
		this.deleted = true;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Comment other)) {
			return false;
		}
		return this.id != null && this.id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.id);
	}

}
