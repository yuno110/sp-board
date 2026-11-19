package com.example.board.attachment.service;

import java.io.InputStream;

/**
 * 내려받기로 내줄 첨부 — 열린 파일 스트림과 서빙 응답 헤더를 만드는 값 (sp-docs/security.md §8 의
 * 서빙 응답 헤더 표).
 *
 * <p><b>스트림을 닫는 것은 받는 쪽이다.</b> 파일을 메모리에 올리지 않고 응답으로 흘려보내므로, 서비스는
 * 연 스트림을 그대로 넘긴다.
 *
 * <p>저장 이름({@code storage_key})을 싣지 않는다 — 응답 어디에도 나가지 않는다 (sp-docs/api-contract.md
 * §4.7).
 *
 * @param contentType 매직 바이트로 판정해 저장한 형식 (sp-docs/requirements/board.md §11). 요청의
 *     {@code Content-Type} 이 아니다
 * @param originalFilename 정리한 원본 파일명. 헤더에는 퍼센트 인코딩으로만 싣는다
 * @param size 저장된 바이트 수 — {@code Content-Length}
 * @param content 그 저장 이름의 파일을 읽는 스트림
 */
public record AttachmentContent(
		String contentType,
		String originalFilename,
		long size,
		InputStream content
) {

}
