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

	@Builder
	private Comment(Post post, Long writerId, String writerNickname, String content) {
		this.post = post;
		this.writerId = writerId;
		this.writerNickname = writerNickname;
		this.content = content;
		this.deleted = false;
	}

	/** 내용 변경. 작성자 스냅샷은 건드리지 않는다 (sp-docs/requirements/board.md §3 규칙 7). */
	public void update(String content) {
		this.content = content;
	}

	/** 논리 삭제. 행은 남는다. */
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
