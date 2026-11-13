package com.example.board.like.entity;

import com.example.board.post.entity.Post;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 좋아요. 정본: sp-docs/domain-model.md §4.4.
 *
 * <p>행 하나가 "이 회원이 이 글을 눌렀다" 이다. 회원당 한 번의 최종 판정은
 * {@code uk_post_like_post_account} 이고, 취소하면 행을 지운다(물리 삭제).
 *
 * <p><b>{@code BaseTimeEntity} 를 상속하지 않는다.</b> 갱신할 것이 없어 {@code updated_at} 이
 * 없다. 그래도 {@code createdAt} 은 감사 시각과 같은 초 자르기를 받아야 하므로
 * {@code AuditingEntityListener} 와 {@code @CreatedDate} 를 직접 둔다 — 값을 넣는 것은
 * {@code JpaConfig} 의 {@code DateTimeProvider} 다 (sp-docs/domain-model.md §1.1).
 *
 * <p><b>{@code accountId} 는 논리 참조다.</b> {@code sp_auth.account.id} 를 가리키지만 스키마
 * 경계를 넘으므로 FK 를 두지 않는다 (sp-docs/domain-model.md §4.3). {@code post} 는 같은 스키마이므로
 * 연관관계 매핑이고 FK({@code fk_post_like_post_id})가 있다.
 *
 * <p>{@code @Setter}·{@code @Data} 를 두지 않는다 (sp-docs/conventions.md §2).
 */
@Getter
@Entity
@Table(name = "post_like")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostLike {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "post_id", nullable = false)
	private Post post;

	@Column(nullable = false)
	private Long accountId;

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	private PostLike(Post post, Long accountId) {
		this.post = post;
		this.accountId = accountId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof PostLike other)) {
			return false;
		}
		return this.id != null && this.id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.id);
	}

}
