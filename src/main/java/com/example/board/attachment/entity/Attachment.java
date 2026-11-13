package com.example.board.attachment.entity;

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
 * 첨부. 정본: sp-docs/domain-model.md §4.6.
 *
 * <p><b>{@code storageKey} 는 서버가 만든 UUID 문자열이다.</b> 원본 파일명은 저장 경로에 들어가지
 * 않고 메타데이터로만 쓴다 — 경로 탐색 차단 (§4.6).
 *
 * <p>폭에 여유가 없다. {@code originalFilename} 은 규칙과 폭이 같다(255). {@code ddl-auto: validate}
 * 는 폭을 보지 않으므로 {@code AttachmentRepositoryTest} 가 {@code information_schema} 의 실제
 * 폭과 대조한다 (§4.6, sp-docs/tech-stack.md §5.2).
 *
 * <p>첨부 삭제는 {@code deleted} 를 조건부 UPDATE 로 바꾼다 — 그 전환의 영향 행 수가 동시 삭제의
 * 최종 판정이기 때문이다 (sp-docs/domain-model.md §6.2). 그 문장은 그 경로를 만드는 기능 항목이
 * 더한다.
 *
 * <p>{@code @Setter}·{@code @Data} 를 두지 않는다 (sp-docs/conventions.md §2).
 */
@Getter
@Entity
@Table(name = "attachment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attachment extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "post_id", nullable = false)
	private Post post;

	@Column(length = 36, nullable = false)
	private String storageKey;

	@Column(length = 255, nullable = false)
	private String originalFilename;

	/** 매직 바이트로 판정한 형식 (sp-docs/requirements/board.md §11). */
	@Column(length = 30, nullable = false)
	private String contentType;

	/** 바이트. */
	@Column(nullable = false)
	private long size;

	@Column(nullable = false)
	private boolean deleted;

	@Builder
	private Attachment(Post post, String storageKey, String originalFilename, String contentType,
			long size) {
		this.post = post;
		this.storageKey = storageKey;
		this.originalFilename = originalFilename;
		this.contentType = contentType;
		this.size = size;
		this.deleted = false;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Attachment other)) {
			return false;
		}
		return this.id != null && this.id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.id);
	}

}
