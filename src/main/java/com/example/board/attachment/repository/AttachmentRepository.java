package com.example.board.attachment.repository;

import com.example.board.attachment.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 첨부 리포지터리 (sp-docs/domain-model.md §4.6).
 *
 * <p>첨부의 문장은 그 경로를 만드는 기능 항목이 더한다 (sp-docs/plan/phase2-board.md §2.1).
 * {@code attachment_count} 를 바꾸는 문장은 여기가 아니라 {@code PostRepository} 에 있다 — 그
 * 문장이 바꾸는 테이블이 {@code post} 다 (sp-docs/conventions.md §1.3).
 */
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

}
