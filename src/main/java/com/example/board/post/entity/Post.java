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

	/**
	 * 조회수. <b>상세 조회의 증가는 {@code PostRepository.increaseViewCount} 의 좁은 UPDATE 가
	 * 한다</b> ({@code view_count = view_count + 1}, sp-docs/plan/phase1.md §6 B-11).
	 */
	@Column(nullable = false)
	private long viewCount;

	/**
	 * 목록 N+1 방지용 비정규화. <b>음수가 되지 않는다</b> (sp-docs/domain-model.md §5,
	 * sp-docs/requirements/board.md §5 규칙 4).
	 *
	 * <p><b>댓글 작성·삭제의 갱신은 {@code PostRepository} 의 좁은 UPDATE 가 한다</b>
	 * ({@code comment_count = comment_count ± 1}, sp-docs/plan/phase1.md §6 B-10). 음수
	 * 방지도 그 문장의 {@code where} 절에 있다.
	 */
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

	/**
	 * 메모리 안의 조회수 증가.
	 *
	 * <p><b>상세 조회 경로는 이 메서드를 쓰지 않는다</b> — 조회수 증가는
	 * {@code PostRepository.increaseViewCount} 의 좁은 UPDATE 가 한다
	 * (sp-docs/plan/phase1.md §6 B-11). 관리 엔티티를 메모리에서 올리면 더티 체킹이 변경되지
	 * 않은 컬럼까지 UPDATE 에 실어 <b>그 트랜잭션이 읽은 {@code comment_count} 를 그대로 다시
	 * 쓰고</b>, 그 사이에 커밋된 댓글 작성의 증가가 조회 하나로 소실된다. 조회수 자체도 동시
	 * 조회가 같은 값을 읽어 하나가 소실된다. <b>조회수 증가를 이 메서드로 바꾸면 그 결함이
	 * 돌아온다.</b>
	 *
	 * <p><b>그래도 메서드가 남아 있는 이유는 B-03 이다</b> —
	 * {@link #increaseCommentCount()} 와 같다. B-03 의 도메인 메서드 표와 완료 기준이 이
	 * 메서드를 요구하고, 리포지터리 테스트가 두 번 불러 2 가 되는 것을 본다
	 * (sp-docs/plan/phase1.md §6 B-03).
	 */
	public void increaseViewCount() {
		this.viewCount++;
	}

	/**
	 * 메모리 안의 댓글 수 증가.
	 *
	 * <p><b>댓글 작성 경로는 이 메서드를 쓰지 않는다</b> — 집계 동기화는
	 * {@code PostRepository.increaseCommentCount} 의 좁은 UPDATE 가 한다
	 * (sp-docs/plan/phase1.md §6 B-10). 관리 엔티티를 메모리에서 올리면 더티 체킹이 변경되지
	 * 않은 컬럼까지 UPDATE 에 실어 낡은 스냅샷째 덮어쓰고, <b>동시 커밋에서 증가가
	 * 소실된다</b> (sp-docs/nfr.md §1 의 I-03 실측). 집계를 이 메서드로 바꾸면 그 결함이
	 * 돌아온다.
	 *
	 * <p><b>그래도 메서드가 남아 있는 이유는 B-03 이다.</b> B-03 의 완료 기준이 "위 표의
	 * 도메인 메서드 6개가 모두 구현되어 있다" 와 "{@code decreaseCommentCount()} 가 0 에서
	 * 호출돼도 음수가 되지 않는다" 를 요구하고 그 표가 이 둘을 담는다
	 * (sp-docs/plan/phase1.md §6 B-03). B-10 은 <b>갱신 수단</b>을 대체하라고 했고 B-03 의
	 * 완료 기준이 깨지지 않는지 확인하라고 했다 — 지우려면 B-03 의 완료 기준과 검증표를
	 * 먼저 고쳐야 한다.
	 */
	public void increaseCommentCount() {
		this.commentCount++;
	}

	/**
	 * 메모리 안의 댓글 수 감소. <b>0 에서 호출돼도 0 을 유지한다.</b>
	 *
	 * <p>{@code comment_count} 는 음수가 될 수 없다 (sp-docs/domain-model.md §5,
	 * sp-docs/requirements/board.md §5 규칙 4).
	 *
	 * <p><b>댓글 삭제 경로는 이 메서드를 쓰지 않는다</b> —
	 * {@link #increaseCommentCount()} 와 같은 이유다. 그 경로의 음수 방지는
	 * {@code PostRepository.decreaseCommentCount} 의 {@code where} 절에 있다.
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
