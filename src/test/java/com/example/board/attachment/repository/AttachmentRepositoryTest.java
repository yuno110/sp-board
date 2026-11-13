package com.example.board.attachment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.attachment.entity.Attachment;
import com.example.board.global.config.JpaConfig;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import jakarta.persistence.Column;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code attachment} 의 스키마와 엔티티. sp-docs/plan/phase2-board.md B-14 의 검증 표 중 첨부에
 * 해당하는 것이다. 정본은 sp-docs/domain-model.md §4.3·§4.6 이다.
 *
 * <p>두 애노테이션의 이유는 {@link com.example.board.post.repository.PostRepositoryTest} 와
 * 같다 (sp-docs/conventions.md §9.3). {@code Attachment} 는 {@code BaseTimeEntity} 를 상속하므로
 * 감사가 쓰인다.
 *
 * <p>픽스처의 {@code writerId} 는 961~969 를 쓴다 — 같은 JVM 의 테스트 클래스가 하나의 DB 를
 * 보므로 클래스마다 대역을 겹치지 않게 둔다 (sp-docs/tech-stack.md §5.3).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("test")
class AttachmentRepositoryTest {

	/** sp-docs/domain-model.md §4.6 이 정한 컬럼 폭이다. */
	private static final int STORAGE_KEY_WIDTH = 36;

	private static final int ORIGINAL_FILENAME_WIDTH = 255;

	private static final int CONTENT_TYPE_WIDTH = 30;

	@Autowired
	private TestEntityManager em;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private AttachmentRepository attachmentRepository;

	@Test
	@DisplayName("저장 후 조회하면 모든 필드가 일치하고 deleted 는 false, 감사 시각이 있다")
	void 저장_후_조회() {
		Post post = savedPost(961L);
		String storageKey = UUID.randomUUID().toString();
		Attachment saved = attachmentRepository.saveAndFlush(
				attachment(post, storageKey, "고양이.png"));
		em.clear();

		Attachment found = attachmentRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getPost().getId()).isEqualTo(post.getId());
		assertThat(found.getStorageKey()).isEqualTo(storageKey);
		assertThat(found.getOriginalFilename()).isEqualTo("고양이.png");
		assertThat(found.getContentType()).isEqualTo("image/png");
		assertThat(found.getSize()).isEqualTo(1_024L);
		assertThat(found.isDeleted()).isFalse();
		assertThat(found.getCreatedAt()).as("BaseTimeEntity 를 상속한다 (§4.6)").isNotNull();
		assertThat(found.getUpdatedAt()).isNotNull();
	}

	/**
	 * {@code original_filename} 은 규칙과 폭이 같아 여유가 없다 (sp-docs/domain-model.md §4.6).
	 * 실제로 들어갈 최대 길이로 저장을 확인한다 (sp-docs/conventions.md §9.2).
	 */
	@Test
	@DisplayName("폭 경계인 255자 original_filename 이 잘리지 않고 저장된다")
	void 원본_파일명_상한_저장() {
		String originalFilename = "파".repeat(ORIGINAL_FILENAME_WIDTH - ".png".length()) + ".png";
		Attachment saved = attachmentRepository.saveAndFlush(
				attachment(savedPost(962L), UUID.randomUUID().toString(), originalFilename));
		em.clear();

		assertThat(attachmentRepository.findById(saved.getId()).orElseThrow().getOriginalFilename())
				.hasSize(ORIGINAL_FILENAME_WIDTH)
				.isEqualTo(originalFilename);
	}

	@Test
	@DisplayName("같은 storage_key 를 두 번 저장하면 uk_attachment_storage_key 위반이다")
	void 같은_storage_key_두_번은_UNIQUE_위반이다() {
		Post post = savedPost(963L);
		String storageKey = UUID.randomUUID().toString();
		attachmentRepository.saveAndFlush(attachment(post, storageKey, "첫째.png"));

		assertThatThrownBy(() -> attachmentRepository.saveAndFlush(
				attachment(post, storageKey, "둘째.png")))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(e -> assertThat(
						((DataIntegrityViolationException) e).getMostSpecificCause().getMessage())
						.containsIgnoringCase("uk_attachment_storage_key"));
	}

	/**
	 * {@code ddl-auto: validate} 는 길이 불일치를 통과시킨다 (sp-docs/tech-stack.md §5.2).
	 * 엔티티의 {@code @Column(length)} 와 마이그레이션의 실제 폭을 여기서 직접 맞춘다.
	 */
	@Test
	@DisplayName("information_schema 의 폭이 storage_key 36, original_filename 255, content_type 30 이고 엔티티 선언과 같다")
	void 폭이_정본과_엔티티_선언과_같다() throws Exception {
		int storageKeyWidth = actualWidth("storage_key");
		int originalFilenameWidth = actualWidth("original_filename");
		int contentTypeWidth = actualWidth("content_type");

		assertThat(storageKeyWidth).as("V7 의 storage_key VARCHAR 폭").isEqualTo(STORAGE_KEY_WIDTH);
		assertThat(originalFilenameWidth)
				.as("V7 의 original_filename VARCHAR 폭")
				.isEqualTo(ORIGINAL_FILENAME_WIDTH);
		assertThat(contentTypeWidth).as("V7 의 content_type VARCHAR 폭").isEqualTo(CONTENT_TYPE_WIDTH);

		assertThat(declaredLength("storageKey"))
				.as("엔티티와 마이그레이션이 어긋나도 validate 는 통과한다. 여기서 막는다")
				.isEqualTo(storageKeyWidth);
		assertThat(declaredLength("originalFilename")).isEqualTo(originalFilenameWidth);
		assertThat(declaredLength("contentType")).isEqualTo(contentTypeWidth);
	}

	@Test
	@DisplayName("attachment 의 FK 는 post_id 의 fk_attachment_post_id 다")
	void FK_는_post_id_의_fk_attachment_post_id_다() {
		List<?> rows = em.getEntityManager()
				.createNativeQuery("select c.constraint_name, k.column_name "
						+ "from information_schema.table_constraints c "
						+ "join information_schema.key_column_usage k "
						+ "  on k.constraint_name = c.constraint_name "
						+ "where c.table_name = 'attachment' and c.constraint_type = 'FOREIGN KEY'")
				.getResultList();

		assertThat(rows).map(row -> (Object[]) row)
				.map(row -> String.valueOf(row[0]).toLowerCase() + ":" + String.valueOf(row[1]).toLowerCase())
				.containsExactly("fk_attachment_post_id:post_id");
	}

	/** 제약·인덱스 이름은 sp-docs/domain-model.md §4.3 이 정본이다. */
	@Test
	@DisplayName("attachment 에 uk_attachment_storage_key·idx_attachment_post_id 가 있다")
	void 제약과_인덱스_이름이_정본과_같다() {
		List<?> uniqueNames = em.getEntityManager()
				.createNativeQuery("select constraint_name from information_schema.table_constraints "
						+ "where table_name = 'attachment' and constraint_type = 'UNIQUE'")
				.getResultList();
		List<?> indexes = em.getEntityManager()
				.createNativeQuery("select index_name from information_schema.indexes "
						+ "where table_name = 'attachment'")
				.getResultList();

		assertThat(uniqueNames).map(name -> String.valueOf(name).toLowerCase())
				.containsExactly("uk_attachment_storage_key");
		assertThat(indexes).map(name -> String.valueOf(name).toLowerCase())
				.contains("idx_attachment_post_id");
	}

	private int actualWidth(String columnName) {
		Number width = (Number) em.getEntityManager()
				.createNativeQuery("select character_maximum_length from information_schema.columns "
						+ "where table_name = 'attachment' and column_name = :columnName")
				.setParameter("columnName", columnName)
				.getSingleResult();
		return width.intValue();
	}

	private static int declaredLength(String fieldName) throws Exception {
		return Attachment.class.getDeclaredField(fieldName).getAnnotation(Column.class).length();
	}

	private Post savedPost(Long writerId) {
		return postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname("글작성자")
				.title("첨부 테스트용 게시글")
				.content("본문")
				.build());
	}

	/** 저장 이름은 서버가 만든 UUID 문자열이다 (sp-docs/domain-model.md §4.6). */
	private static Attachment attachment(Post post, String storageKey, String originalFilename) {
		return Attachment.builder()
				.post(post)
				.storageKey(storageKey)
				.originalFilename(originalFilename)
				.contentType("image/png")
				.size(1_024L)
				.build();
	}

}
