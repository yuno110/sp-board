package com.example.board.post.repository;

import com.example.board.post.entity.PostView;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회수 판정 행 리포지터리 (sp-docs/domain-model.md §4.5).
 *
 * <p>판정의 문장은 그 판정을 쓰는 기능 항목이 더한다 (sp-docs/plan/phase2-board.md §2.1).
 * {@code view_count} 를 바꾸는 문장은 여기가 아니라 {@code PostRepository} 에 있다.
 */
public interface PostViewRepository extends JpaRepository<PostView, Long> {

}
