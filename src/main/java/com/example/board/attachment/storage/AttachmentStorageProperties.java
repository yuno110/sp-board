package com.example.board.attachment.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 첨부 저장소의 설정 {@code attachment.storage-root} 를 묶고, 기동 시점에 검사한다
 * (sp-docs/tech-stack.md §4.5).
 *
 * <p><b>정책값이고 기본값이 없다</b> (sp-docs/tech-stack.md §4.3). 값은 <b>이미 있고 쓸 수 있는
 * 디렉터리</b>여야 하고 아니면 기동이 실패한다 — <b>서버가 만들지 않는다.</b> 엉뚱한 곳에
 * 디렉터리를 만들고 올라오는 것보다 실패가 낫다 (§4.5).
 *
 * <p><b>그 실패는 이 생성자가 만든다.</b> {@code @ConfigurationProperties} 로 묶으면 환경변수가
 * 없을 때 미해석 {@code ${ATTACHMENT_STORAGE_ROOT}} 리터럴이 그대로 들어오고, 빈 환경변수는 빈
 * 문자열로 들어온다 (sp-docs/tech-stack.md §4.3 의 측정값) — 기본값을 두지 않는 것만으로는
 * 기동이 실패하지 않는다. 그 리터럴은 있는 디렉터리의 이름이 아니므로 디렉터리 검사가 막는다.
 *
 * <p>로컬에서는 {@code application-local.yml} 이 이 키를 준다. 다만 환경변수
 * {@code ATTACHMENT_STORAGE_ROOT} 가 설정돼 있으면 yml 의 값보다 앞선다 — 그 이름이 속성 경로의
 * 완화 바인딩 형식(점·대시를 밑줄로 바꾼 대문자)과 같아 placeholder 를 거치지 않고 이 키에 묶이고,
 * OS 환경변수는 yml 보다 우선한다. 측정값이다 — 이 환경변수를 준 테스트 실행에서
 * {@code application-test.yml} 의 값 대신 쓰였다.
 */
@ConfigurationProperties("attachment")
public class AttachmentStorageProperties {

	private final Path storageRoot;

	public AttachmentStorageProperties(String storageRoot) {
		this.storageRoot = requireWritableDirectory(storageRoot);
	}

	/** 저장 루트. 기동 시점에 있었고 쓸 수 있었던 디렉터리다. */
	public Path storageRoot() {
		return this.storageRoot;
	}

	private static Path requireWritableDirectory(String value) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalStateException(
					"attachment.storage-root 가 비어 있다. 이미 있고 쓸 수 있는 디렉터리를 외부에서 "
							+ "주입한다 (sp-docs/tech-stack.md §4.5)");
		}
		Path root = Path.of(value);
		if (!Files.isDirectory(root)) {
			throw new IllegalStateException(
					"attachment.storage-root 가 이미 있는 디렉터리가 아니다: " + value
							+ " — 서버가 만들지 않는다 (sp-docs/tech-stack.md §4.5)");
		}
		if (!Files.isWritable(root)) {
			throw new IllegalStateException(
					"attachment.storage-root 에 쓸 수 없다: " + value + " (sp-docs/tech-stack.md §4.5)");
		}
		return root;
	}

}
