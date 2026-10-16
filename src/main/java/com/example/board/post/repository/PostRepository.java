package com.example.board.post.repository;

import com.example.board.post.entity.Post;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 게시글 리포지터리.
 *
 * <p>동적 검색(QueryDSL)은 {@code PostQueryRepository} 가 맡는다 — B-06 의 산출물이다
 * (sp-docs/plan/phase1.md §2.2).
 */
public interface PostRepository extends JpaRepository<Post, Long> {

	/**
	 * 삭제되지 않은 게시글 한 건.
	 *
	 * <p>삭제된 게시글은 목록·상세에서 제외한다 (sp-docs/requirements/board.md §5 규칙 1).
	 * 그 판정을 {@link com.example.board.post.support.PostReader} 한 곳에 모으기 위해
	 * {@code deleted = false} 를 쿼리에 둔다.
	 */
	Optional<Post> findByIdAndDeletedFalse(Long id);

}
