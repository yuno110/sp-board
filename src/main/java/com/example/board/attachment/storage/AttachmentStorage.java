package com.example.board.attachment.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;

/**
 * 첨부 파일의 저장소. 정본: sp-docs/adr/0013-attachment-storage.md 의 결정 — 서비스 코드는 이
 * 인터페이스(저장·열기·지우기)만 알고 디스크 경로를 모른다.
 *
 * <p><b>저장 이름은 저장소가 만든다.</b> {@link #store} 는 이름을 받지 않고, 만든 이름을 돌려준다 —
 * 서버가 만든 UUID 다 (sp-docs/domain-model.md §4.6). 요청에서 온 값이 저장 이름이 될 자리가
 * 시그니처에 없다 (sp-docs/security.md §8). 원본 파일명은 메타데이터로 DB 에만 둔다.
 *
 * <p><b>파일은 저장 루트 바로 아래의 그 이름 하나다</b> (sp-docs/domain-model.md §4.6). 열기·지우기는
 * DB 행이 가진 저장 이름을 받는다. 그 값이 저장소가 만드는 모양(UUID 의 정규 문자열)이 아니면
 * {@link IllegalArgumentException} 이다 — 저장 루트 밖이나 하위 경로를 가리킬 수 있는 값으로 파일에
 * 닿지 않는다.
 */
public interface AttachmentStorage {

	/**
	 * 내용을 새 저장 이름으로 끝까지 쓰고 그 이름을 돌려준다. 내용 스트림은 닫지 않는다.
	 *
	 * <p>쓰다가 실패하면 그 자리에 생긴 파일을 지우고 예외를 던진다 — 부르는 쪽은 이름을 받지 못해
	 * 그 파일을 지울 수 없다.
	 *
	 * @return 서버가 만든 저장 이름 (UUID 의 정규 문자열, 36자)
	 */
	String store(InputStream content) throws IOException;

	/**
	 * 그 저장 이름의 파일을 읽는 스트림을 연다. 닫는 것은 부르는 쪽이다.
	 *
	 * @throws NoSuchFileException 그 이름의 파일이 없을 때
	 */
	InputStream open(String storageKey) throws IOException;

	/** 그 저장 이름의 파일을 지운다. 이미 없으면 아무것도 하지 않는다. */
	void delete(String storageKey) throws IOException;

}
