package com.example.board.like.repository;

import com.example.board.like.entity.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;

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
	 * <p>좋아요의 멱등 판정과 상세 응답의 {@code liked} 가 이 확인을 쓴다
	 * (sp-docs/domain-model.md §6.1, sp-docs/plan/phase2-board.md §2.1). <b>회원당 한 번의 최종
	 * 판정은 이 확인이 아니라 {@code uk_post_like_post_account} 다</b> — 락 전의 일반 읽기는 앞서
	 * 커밋된 좋아요를 놓칠 수 있다 (§6.1).
	 */
	boolean existsByPostIdAndAccountId(Long postId, Long accountId);

}
