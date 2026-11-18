package com.example.board.attachment.dto;

import com.example.board.attachment.entity.Attachment;
import java.time.LocalDateTime;

/**
 * 첨부 하나의 모양. 정본: sp-docs/api-contract.md §4.7 의 「첨부 하나의 모양」 표.
 *
 * <p><b>URL 키를 두지 않는다.</b> 내려받기 경로는 {@code /api/v1/attachments/{id}} 로 정해져 있고
 * 화면이 board 의 기준 주소에 붙여 만든다 (§4.7). <b>저장 이름({@code storage_key})은 싣지
 * 않는다</b> (§4.7, sp-docs/security.md §8).
 *
 * <p>{@code createdAt} 은 엔터티의 감사 시각이다. 감사가 초로 잘라 넣으므로 저장값과 같다 —
 * sp-docs/api-contract.md §4.3 의 「쓰기 응답의 {@code createdAt} 은 저장값과 같다」 를 그 자르기
 * ({@code JpaConfig})가 지킨다.
 *
 * <p>선언 순서가 곧 JSON 키 순서다. §4.7 의 표와 같은 순서로 둔다.
 *
 * @param originalFilename 정리한 원본 파일명 — 경로 부분과 제어 문자를 버린 것 (§4.7)
 * @param contentType 서버가 매직 바이트로 판정한 형식. 올린 요청의 {@code Content-Type} 이 아니다
 * @param size 바이트 수
 * @param createdAt 올린 시각. 오프셋을 표기하지 않는다 (sp-docs/tech-stack.md §6)
 */
public record AttachmentResponse(
		Long id,
		String originalFilename,
		String contentType,
		long size,
		LocalDateTime createdAt
) {

	public static AttachmentResponse from(Attachment attachment) {
		return new AttachmentResponse(
				attachment.getId(),
				attachment.getOriginalFilename(),
				attachment.getContentType(),
				attachment.getSize(),
				attachment.getCreatedAt()
		);
	}

}
