package com.example.board.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 조회수 판정 행. 정본: sp-docs/domain-model.md §4.5.
 *
 * <p>행 하나가 "이 조회자의 이 글 조회가 이 시각에 집계됐다" 이다. 판정 자체는
 * sp-docs/requirements/board.md §9.3 이 적고, 상세 조회의 일부이므로 {@code post} 패키지에 둔다
 * (sp-docs/conventions.md §1.3).
 *
 * <p><b>{@code postId} 는 연관관계가 아니라 값이고 FK 가 없다.</b> 판정 INSERT 의 FK 검사가
 * {@code post} 행에 공유 락을 잡으면 이어지는 {@code view_count} 의 좁은 UPDATE 와 승격 교착을
 * 만들 것으로 본다 (§4.5 — 추정, 실 MySQL 측정 대상).
 *
 * <p><b>{@code viewedAt} 은 감사 시각이 아니다.</b> {@code @CreatedDate}·{@code @LastModifiedDate}
 * 를 쓰지 않고 판정하는 코드가 넣는다 (§4.5). 그래서 {@code BaseTimeEntity} 를 상속하지 않는다.
 *
 * <p>{@code viewerKey} 는 80자이고 {@code ddl-auto: validate} 는 폭을 보지 않으므로
 * {@code PostViewRepositoryTest} 가 {@code information_schema} 의 실제 폭과 대조한다.
 *
 * <p>{@code @Setter}·{@code @Data} 를 두지 않는다 (sp-docs/conventions.md §2).
 */
@Getter
@Entity
@Table(name = "post_view")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostView {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long postId;

	/** {@code a:{accountId}} 또는 {@code i:{HMAC-SHA256 의 소문자 hex 64자}} (sp-docs/requirements/board.md §9.1). */
	@Column(length = 80, nullable = false)
	private String viewerKey;

	/** 마지막으로 집계된 조회의 시각. 앱 시계의 값이다 (sp-docs/domain-model.md §4.5). */
	@Column(nullable = false)
	private LocalDateTime viewedAt;

	@Builder
	private PostView(Long postId, String viewerKey, LocalDateTime viewedAt) {
		this.postId = postId;
		this.viewerKey = viewerKey;
		this.viewedAt = viewedAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof PostView other)) {
			return false;
		}
		return this.id != null && this.id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.id);
	}

}
