package com.example.board.attachment.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 저장 루트의 기동 가드 — sp-docs/plan/phase2-board.md B-16 검증표의 「저장 루트」 줄이다.
 * 요건의 정본은 sp-docs/tech-stack.md §4.5 다.
 *
 * <p><b>실제 바인딩으로 컨텍스트를 띄워 본다.</b> 생성자를 직접 부르면 {@code @ConfigurationProperties}
 * 가 무엇을 넘기는지 — 키가 없을 때의 값, 환경변수가 없을 때의 미해석 {@code ${…}} 리터럴
 * (sp-docs/tech-stack.md §4.3 의 측정값) — 를 지나치므로, 그 경로를 지나 기동이 실패하는지를
 * 본다.
 */
class AttachmentStoragePropertiesTest {

	/**
	 * <b>시스템 환경 소스를 뺀다.</b> 환경변수 {@code ATTACHMENT_STORAGE_ROOT} 는 이름이 속성 경로의
	 * 완화 바인딩 형식과 같아 설정돼 있으면 이 키에 묶인다 — 그러면 실행하는 PC·CI 의 환경이 「키가
	 * 없다」 와 「placeholder 가 리터럴로 남는다」 의 전제를 깬다.
	 */
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(context -> context.getEnvironment().getPropertySources()
					.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
			.withUserConfiguration(PropertiesConfiguration.class);

	@EnableConfigurationProperties(AttachmentStorageProperties.class)
	static class PropertiesConfiguration {
	}

	@TempDir
	private Path tempDir;

	@Test
	@DisplayName("이미 있고 쓸 수 있는 디렉터리면 기동하고 그 경로가 저장 루트다")
	void 쓸_수_있는_디렉터리면_기동한다() {
		this.contextRunner.withPropertyValues("attachment.storage-root=" + this.tempDir)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(AttachmentStorageProperties.class).storageRoot())
							.isEqualTo(this.tempDir);
				});
	}

	@Test
	@DisplayName("키가 없으면 기동이 실패한다")
	void 키가_없으면_기동이_실패한다() {
		this.contextRunner.run(context -> assertStartupFailed(context, "비어 있다"));
	}

	/**
	 * {@code application.yml} 의 {@code ${ATTACHMENT_STORAGE_ROOT}} 에 환경변수가 없을 때와 같은
	 * 상태다. 러너가 시스템 환경 소스를 빼므로 실제 이름을 그대로 쓴다.
	 */
	@Test
	@DisplayName("환경변수가 없어 placeholder 가 리터럴로 남으면 기동이 실패한다")
	void 미해석_placeholder_면_기동이_실패한다() {
		this.contextRunner
				.withPropertyValues("attachment.storage-root=${ATTACHMENT_STORAGE_ROOT}")
				.run(context -> assertStartupFailed(context, "이미 있는 디렉터리가 아니다"));
	}

	@Test
	@DisplayName("빈 값이면 기동이 실패한다")
	void 빈_값이면_기동이_실패한다() {
		this.contextRunner.withPropertyValues("attachment.storage-root=")
				.run(context -> assertStartupFailed(context, "비어 있다"));
	}

	/**
	 * 공백뿐인 값은 빈 값으로 본다. {@code withPropertyValues} 는 값의 앞뒤 공백을 지워 위의 빈 값
	 * 테스트와 같아지므로 속성 소스로 직접 넣는다.
	 */
	@Test
	@DisplayName("공백뿐인 값이면 기동이 실패한다")
	void 공백뿐인_값이면_기동이_실패한다() {
		this.contextRunner
				.withInitializer(context -> context.getEnvironment().getPropertySources()
						.addFirst(new MapPropertySource("blank", Map.of("attachment.storage-root", "   "))))
				.run(context -> assertStartupFailed(context, "비어 있다"));
	}

	/** <b>서버가 만들지 않는다</b> (sp-docs/tech-stack.md §4.5) — 실패한 뒤에도 경로가 없어야 한다. */
	@Test
	@DisplayName("없는 경로면 기동이 실패하고 그 디렉터리를 만들지 않는다")
	void 없는_경로면_기동이_실패하고_만들지_않는다() {
		Path missing = this.tempDir.resolve("missing");

		this.contextRunner.withPropertyValues("attachment.storage-root=" + missing)
				.run(context -> assertStartupFailed(context, "이미 있는 디렉터리가 아니다"));

		assertThat(missing)
				.as("엉뚱한 곳에 디렉터리를 만들고 올라오는 것보다 실패가 낫다 (sp-docs/tech-stack.md §4.5)")
				.doesNotExist();
	}

	@Test
	@DisplayName("파일인 경로면 기동이 실패한다")
	void 파일이면_기동이_실패한다() throws IOException {
		Path file = Files.createFile(this.tempDir.resolve("not-a-directory.txt"));

		this.contextRunner.withPropertyValues("attachment.storage-root=" + file)
				.run(context -> assertStartupFailed(context, "이미 있는 디렉터리가 아니다"));
	}

	/**
	 * 쓸 수 없는 디렉터리를 플랫폼의 권한 수단으로 만든다 — ACL 이 있으면 현재 사용자에게 쓰기
	 * 거부 항목을 더하고, 없으면 POSIX 권한에서 쓰기를 뺀다. 끝나면 원래 권한으로 되돌린다.
	 *
	 * <p><b>만들지 못하면 건너뛴다</b> — 예: POSIX 의 root 는 권한 비트와 무관하게 쓸 수 있다.
	 * 계획이 「만들 수 있으면 넣는다」 고 적은 줄이다 (sp-docs/plan/phase2-board.md B-16 의
	 * 블록인용).
	 */
	@Test
	@DisplayName("쓸 수 없는 디렉터리면 기동이 실패한다")
	void 쓸_수_없는_디렉터리면_기동이_실패한다() throws IOException {
		Path readOnly = Files.createDirectory(this.tempDir.resolve("read-only"));
		Runnable restore = denyWrite(readOnly);
		try {
			assumeFalse(Files.isWritable(readOnly), "이 플랫폼에서 쓸 수 없는 디렉터리를 만들지 못했다");

			this.contextRunner.withPropertyValues("attachment.storage-root=" + readOnly)
					.run(context -> assertStartupFailed(context, "쓸 수 없다"));
		} finally {
			restore.run();
		}
	}

	private static void assertStartupFailed(AssertableApplicationContext context, String reason) {
		assertThat(context).hasFailed();
		assertThat(context.getStartupFailure())
				.rootCause()
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("attachment.storage-root")
				.hasMessageContaining(reason);
	}

	/** 쓰기를 막고, 원래 권한으로 되돌리는 동작을 돌려준다. */
	private static Runnable denyWrite(Path directory) throws IOException {
		AclFileAttributeView aclView = Files.getFileAttributeView(directory, AclFileAttributeView.class);
		if (aclView != null) {
			List<AclEntry> original = aclView.getAcl();
			UserPrincipal currentUser = directory.getFileSystem().getUserPrincipalLookupService()
					.lookupPrincipalByName(System.getProperty("user.name"));
			AclEntry denyWrite = AclEntry.newBuilder()
					.setType(AclEntryType.DENY)
					.setPrincipal(currentUser)
					.setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.ADD_FILE,
							AclEntryPermission.APPEND_DATA, AclEntryPermission.ADD_SUBDIRECTORY)
					.build();
			List<AclEntry> denied = new ArrayList<>(original);
			// 거부 항목은 허용 항목보다 앞이어야 먼저 평가된다
			denied.add(0, denyWrite);
			aclView.setAcl(denied);
			return () -> {
				try {
					aclView.setAcl(original);
				} catch (IOException e) {
					throw new IllegalStateException("권한을 되돌리지 못했다: " + directory, e);
				}
			};
		}
		PosixFileAttributeView posixView = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
		Set<PosixFilePermission> original = posixView.readAttributes().permissions();
		Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
		return () -> {
			try {
				Files.setPosixFilePermissions(directory, original);
			} catch (IOException e) {
				throw new IllegalStateException("권한을 되돌리지 못했다: " + directory, e);
			}
		};
	}

}
