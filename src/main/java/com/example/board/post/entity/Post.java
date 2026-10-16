package com.example.board.post.entity;

import com.example.board.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 게시글. 정본: sp-docs/domain-model.md §4.1.
 *
 * <p><b>{@code writerId} 는 논리 참조다.</b> {@code sp_auth.account.id} 를 가리키지만 스키마
 * 경계를 넘으므로 FK 를 두지 않는다 (sp-docs/requirements/board.md §3 규칙 4,
 * sp-docs/architecture.md §5).
 *
 * <p><b>{@code writerNickname} 은 작성 시점 스냅샷이다.</b> 닉네임은 member-service 소유
 * 데이터라 조인할 수 없다. {@code writerId} 는 검증된 JWT 의 {@code sub} 에서,
 * {@code writerNickname} 은 member 의 내부 API 응답에서 온다 (§3). <b>수정 시 스냅샷을
 * 갱신하지 않는다</b> — {@link #update(String, String)} 가 둘을 건드리지 않는 이유다 (§3 규칙 7).
 *
 * <p>컬럼 폭에 여유가 없다. {@code title} 은 입력 규칙이 1~200자인데 컬럼이 200 이다
 * (sp-docs/requirements/board.md §4). {@code ddl-auto: validate} 는 폭을 보지 않으므로
 * {@code PostRepositoryTest} 가 {@code information_schema} 의 실제 폭과 대조한다
 * (sp-docs/tech-stack.md §5.2).
 *
 * <p>{@code @Setter}·{@code @Data} 를 두지 않는다. 상태 변경은 아래 도메인 메서드로만 한다
 * (sp-docs/conventions.md §2).
 */
@Getter
@Entity
@Table(name = "post")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long writerId;

	@Column(length = 30, nullable = false)
	private String writerNickname;

	@Column(length = 200, nullable = false)
	private String title;

	/**
	 * 본문. 입력 규칙은 1~10,000자다 (sp-docs/requirements/board.md §4).
	 *
	 * <p>{@code TEXT} 는 65,535 <b>바이트</b>이고 10,000자는 utf8mb4 최악 4바이트로도
	 * 40,000바이트이므로 들어간다. 그래서 폭 비교의 대상이 아니다
	 * (sp-docs/domain-model.md §4.2).
	 */
	@Column(nullable = false, columnDefinition = "TEXT")
	private String content;

	@Column(nullable = false)
	private long viewCount;

	/** 목록 N+1 방지용 비정규화. <b>음수가 되지 않는다</b> ({@link #decreaseCommentCount()}). */
	@Column(nullable = false)
	private int commentCount;

	@Column(nullable = false)
	private boolean deleted;

	@Builder
	private Post(Long writerId, String writerNickname, String title, String content) {
		this.writerId = writerId;
		this.writerNickname = writerNickname;
		this.title = title;
		this.content = content;
		this.viewCount = 0L;
		this.commentCount = 0;
		this.deleted = false;
	}

	/**
	 * 제목·내용 변경. <b>{@code writerId}·{@code writerNickname} 은 건드리지 않는다.</b>
	 *
	 * <p>수정 시 작성자 스냅샷을 갱신하면 작성 당시 표시가 사라진다. 그래서 수정 경로는
	 * member-service 를 호출하지 않는다 (sp-docs/requirements/board.md §3 규칙 7).
	 */
	public void update(String title, String content) {
		this.title = title;
		this.content = content;
	}

	/** 논리 삭제. 행을 남긴다 — 하위 댓글의 연쇄 삭제는 서비스가 한다. */
	public void softDelete() {
		this.deleted = true;
	}

	public void increaseViewCount() {
		this.viewCount++;
	}

	public void increaseCommentCount() {
		this.commentCount++;
	}

	/**
	 * 댓글 수 감소. <b>0 에서 호출돼도 0 을 유지한다.</b>
	 *
	 * <p>{@code comment_count} 는 음수가 될 수 없다 (sp-docs/domain-model.md §5,
	 * sp-docs/requirements/board.md §5 규칙 4). 비정규화된 값이라 재계산 없이는 어긋남을
	 * 알아챌 수 없으므로 가드를 엔티티에 둔다 — 호출하는 서비스마다 조건을 복제하면 한 군데가
	 * 빠지는 순간 음수가 저장된다.
	 */
	public void decreaseCommentCount() {
		if (this.commentCount > 0) {
			this.commentCount--;
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Post other)) {
			return false;
		}
		return this.id != null && this.id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.id);
	}

}
