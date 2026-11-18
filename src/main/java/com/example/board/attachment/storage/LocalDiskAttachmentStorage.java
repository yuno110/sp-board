package com.example.board.attachment.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 로컬 디스크의 첨부 저장소. 정본: sp-docs/adr/0013-attachment-storage.md — 인터페이스 뒤의 로컬
 * 디스크. 저장 루트는 {@link AttachmentStorageProperties} 가 기동 시점에 검사한 디렉터리다
 * (sp-docs/tech-stack.md §4.5).
 *
 * <p>파일 하나의 경로는 {@code <저장 루트>/<UUID>} 다 — 하위 디렉터리를 만들지 않는다
 * (sp-docs/domain-model.md §4.6).
 */
@Component
public class LocalDiskAttachmentStorage implements AttachmentStorage {

	private final Path root;

	public LocalDiskAttachmentStorage(AttachmentStorageProperties properties) {
		this.root = properties.storageRoot().toAbsolutePath().normalize();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p><b>새 파일로만 연다</b>({@link StandardOpenOption#CREATE_NEW}). 그 이름의 파일이 이미 있으면
	 * 쓰기 전에 실패하고, 그 파일을 덮지도 지우지도 않는다 — 실패 시의 정리는 이 메서드가 만든 파일만
	 * 대상이다.
	 */
	@Override
	public String store(InputStream content) throws IOException {
		String storageKey = UUID.randomUUID().toString();
		Path target = this.root.resolve(storageKey);
		OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);
		try (out) {
			content.transferTo(out);
		}
		catch (IOException | RuntimeException ex) {
			try {
				Files.deleteIfExists(target);
			}
			catch (IOException cleanupFailure) {
				ex.addSuppressed(cleanupFailure);
			}
			throw ex;
		}
		return storageKey;
	}

	@Override
	public InputStream open(String storageKey) throws IOException {
		return Files.newInputStream(resolve(storageKey));
	}

	@Override
	public void delete(String storageKey) throws IOException {
		Files.deleteIfExists(resolve(storageKey));
	}

	/**
	 * 저장 이름을 경로로 바꾼다. UUID 의 정규 문자열이 아니면 {@link IllegalArgumentException} 이다 —
	 * 그 문자열은 16진수와 하이픈뿐이라 구분자·{@code ..}·드라이브 문자·NUL 을 담지 못한다.
	 */
	private Path resolve(String storageKey) {
		Objects.requireNonNull(storageKey, "storageKey");
		if (!isCanonicalUuid(storageKey)) {
			throw new IllegalArgumentException("저장 이름이 UUID 의 정규 문자열이 아니다");
		}
		return this.root.resolve(storageKey);
	}

	private static boolean isCanonicalUuid(String value) {
		try {
			return UUID.fromString(value).toString().equals(value);
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

}
