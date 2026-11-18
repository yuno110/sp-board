package com.example.board.attachment.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 로컬 디스크 저장소 — sp-docs/plan/phase2-board.md B-23 검증표의 「저장된 파일」 줄(저장 루트 바로
 * 아래 UUID 이름)과 산출물의 저장·열기·지우기다. 근거는 sp-docs/domain-model.md §4.6,
 * sp-docs/adr/0013-attachment-storage.md, sp-docs/security.md §8.
 *
 * <p><b>저장 루트는 테스트마다 비운 임시 디렉터리다</b> (sp-docs/tech-stack.md §4.5) — 필드의
 * {@code @TempDir} 은 테스트 메서드마다 새로 만들어진다. 그래서 「루트에 파일이 하나다」 를 디렉터리
 * 목록 전체로 단언할 수 있다.
 */
class LocalDiskAttachmentStorageTest {

	/** 테스트마다 새로 만들어지는 임시 디렉터리. 저장 루트는 그 아래의 빈 디렉터리다 — 루트 밖에 미끼를 둘 자리다. */
	@TempDir
	private Path base;

	private Path root;

	private LocalDiskAttachmentStorage storage;

	@BeforeEach
	void setUp() throws IOException {
		this.root = Files.createDirectory(this.base.resolve("root"));
		this.storage = new LocalDiskAttachmentStorage(new AttachmentStorageProperties(this.root.toString()));
	}

	@Test
	@DisplayName("저장하면 저장 루트 바로 아래에 서버가 만든 UUID 이름의 파일 하나가 생기고 내용이 같다")
	void 저장_루트_바로_아래_UUID_이름_하나다() throws IOException {
		byte[] content = {(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3};

		String storageKey = this.storage.store(new ByteArrayInputStream(content));

		assertThat(storageKey).hasSize(36);
		assertThat(UUID.fromString(storageKey).toString()).isEqualTo(storageKey);
		assertThat(entries()).containsExactly(this.root.resolve(storageKey));
		assertThat(Files.isRegularFile(this.root.resolve(storageKey))).isTrue();
		assertThat(Files.readAllBytes(this.root.resolve(storageKey))).isEqualTo(content);
	}

	@Test
	@DisplayName("저장할 때마다 다른 이름이고 각각 루트 바로 아래다")
	void 저장마다_새_이름이다() throws IOException {
		String first = this.storage.store(new ByteArrayInputStream(new byte[] {1}));
		String second = this.storage.store(new ByteArrayInputStream(new byte[] {2}));

		assertThat(first).isNotEqualTo(second);
		assertThat(entries()).containsExactlyInAnyOrder(this.root.resolve(first), this.root.resolve(second));
	}

	@Test
	@DisplayName("열면 저장한 내용을 읽는다")
	void 열면_저장한_내용이다() throws IOException {
		byte[] content = "GIF89a-body".getBytes(StandardCharsets.US_ASCII);
		String storageKey = this.storage.store(new ByteArrayInputStream(content));

		try (InputStream in = this.storage.open(storageKey)) {
			assertThat(in.readAllBytes()).isEqualTo(content);
		}
	}

	@Test
	@DisplayName("없는 이름을 열면 NoSuchFileException 이다")
	void 없는_파일을_열면_예외다() {
		String missing = UUID.randomUUID().toString();

		assertThatThrownBy(() -> this.storage.open(missing)).isInstanceOf(NoSuchFileException.class);
	}

	@Test
	@DisplayName("지우면 그 파일이 없어지고 다른 파일은 남는다")
	void 지우면_그_파일만_없어진다() throws IOException {
		String kept = this.storage.store(new ByteArrayInputStream(new byte[] {1}));
		String removed = this.storage.store(new ByteArrayInputStream(new byte[] {2}));

		this.storage.delete(removed);

		assertThat(entries()).containsExactly(this.root.resolve(kept));
	}

	@Test
	@DisplayName("이미 없는 이름을 지워도 예외가 없다")
	void 없는_파일을_지워도_된다() throws IOException {
		this.storage.delete(UUID.randomUUID().toString());

		assertThat(entries()).isEmpty();
	}

	@Test
	@DisplayName("쓰다가 내용 스트림이 실패하면 예외를 던지고 루트에 파일이 남지 않는다")
	void 쓰다가_실패하면_파일을_남기지_않는다() throws IOException {
		InputStream failing = new InputStream() {
			private int remaining = 4;

			@Override
			public int read() throws IOException {
				if (this.remaining-- > 0) {
					return 0x41;
				}
				throw new IOException("읽기 실패");
			}
		};

		assertThatThrownBy(() -> this.storage.store(failing)).isInstanceOf(IOException.class)
				.hasMessage("읽기 실패");
		assertThat(entries()).isEmpty();
	}

	/**
	 * 열기·지우기는 저장소가 만드는 모양(UUID 의 정규 문자열)만 받는다 — 저장 루트 밖이나 하위 경로를
	 * 가리킬 수 있는 값으로 파일에 닿지 않는다 (sp-docs/security.md §8). 그 이름들이 가리킬 자리 —
	 * 루트 밖({@code ../bait}), 루트 안의 UUID 아닌 이름({@code bait}), 하위 경로({@code sub/x}) — 에
	 * 미끼 파일을 두고 지우기가 그것들을 건드리지 않는 것을 본다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"../bait", "..", "sub/x", "sub\\x", "", "bait",
			"3F2504E0-4F89-11D3-9A0C-0305E82C3301", "3f2504e0-4f89-11d3-9a0c-0305e82c3301/../../bait"})
	@DisplayName("UUID 의 정규 문자열이 아닌 이름은 열기·지우기 모두 IllegalArgumentException 이다")
	void 정규_UUID_가_아니면_거부한다(String storageKey) throws IOException {
		Path outside = Files.writeString(this.base.resolve("bait"), "x");
		Path inside = Files.writeString(this.root.resolve("bait"), "x");
		Path nested = Files.writeString(Files.createDirectory(this.root.resolve("sub")).resolve("x"), "x");

		assertThatThrownBy(() -> this.storage.open(storageKey))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.storage.delete(storageKey))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(outside).exists();
		assertThat(inside).exists();
		assertThat(nested).exists();
	}

	private List<Path> entries() throws IOException {
		try (Stream<Path> stream = Files.list(this.root)) {
			return stream.toList();
		}
	}

}
