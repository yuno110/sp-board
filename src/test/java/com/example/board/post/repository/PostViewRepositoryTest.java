package com.example.board.post.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.post.entity.PostView;
import jakarta.persistence.Column;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code post_view} 의 스키마와 엔티티. sp-docs/plan/phase2-board.md B-14 의 검증 표 중 조회수
 * 판정 행에 해당하는 것이다. 정본은 sp-docs/domain-model.md §4.3·§4.5 다.
 *
 * <p>{@code @Import(JpaConfig.class)} 를 붙이지 않는다. {@code post_view} 에는 감사 컬럼이 없고
 * {@code viewedAt} 은 판정하는 코드가 넣는다 — 감사가 쓰이지 않는 테스트에 그 이유를 적용하지
 * 않는다 (sp-docs/conventions.md §9.3).
 *
 * <p>{@code post_id} 에 FK 가 없으므로 픽스처에 글이 필요 없다. {@code post_id} 는 951~959 를
 * 쓴다 — 다른 클래스의 글 id 와 겹쳐도 FK 가 없어 서로 영향이 없지만, 대역을 나눠 두는 관례를
 * 따른다 (sp-docs/tech-stack.md §5.3).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PostViewRepositoryTest {

	/** sp-docs/domain-model.md §4.5 가 정한 컬럼 폭이다. */
	private static final int VIEWER_KEY_WIDTH = 80;

	/**
	 * 비로그인 판정 키의 모양({@code i:} + HMAC-SHA256 의 소문자 hex 64자,
	 * sp-docs/requirements/board.md §9.1)으로 <b>조립한</b> 값이다. 실제로 들어가는 판정 키 중
	 * 가장 길다. 만드는 규칙은 판정 경로의 몫이라 여기서는 같은 모양으로 조립한다
	 * (sp-docs/conventions.md §9.2).
	 */
	private static final String ANONYMOUS_KEY = "i:" + "0123456789abcdef".repeat(4);

	private static final LocalDateTime VIEWED_AT = LocalDateTime.of(2026, 11, 13, 9, 30, 15);

	@Autowired
	private TestEntityManager em;

	@Autowired
	private PostViewRepository postViewRepository;

	@Test
	@DisplayName("저장 후 조회하면 글 id·판정 키·viewedAt 이 일치한다")
	void 저장_후_조회() {
		PostView saved = postViewRepository.saveAndFlush(view(951L, ANONYMOUS_KEY));
		em.clear();

		PostView found = postViewRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getPostId()).isEqualTo(951L);
		assertThat(found.getViewerKey()).isEqualTo(ANONYMOUS_KEY);
		assertThat(found.getViewedAt()).isEqualTo(VIEWED_AT);
	}

	/**
	 * {@code post_id} 에 FK 를 두지 않는다 — 판정 INSERT 의 FK 공유 락이 {@code view_count} 의
	 * 배타 락과 승격 교착을 만들 것으로 보기 때문이다 (sp-docs/domain-model.md §4.5).
	 */
	@Test
	@DisplayName("없는 post_id 로도 저장된다 — FK 가 없다")
	void 없는_post_id_로_저장된다() {
		PostView saved = postViewRepository.saveAndFlush(view(999_999_999L, "a:952"));
		em.clear();

		assertThat(postViewRepository.findById(saved.getId()).orElseThrow().getPostId())
				.isEqualTo(999_999_999L);
	}

	@Test
	@DisplayName("post_view 에 FOREIGN KEY 제약이 하나도 없다")
	void FK_가_없다() {
		List<?> foreignKeys = em.getEntityManager()
				.createNativeQuery("select constraint_name from information_schema.table_constraints "
						+ "where table_name = 'post_view' and constraint_type = 'FOREIGN KEY'")
				.getResultList();

		assertThat(foreignKeys).map(String::valueOf)
				.as("post_view.post_id 의 FK 부재가 의도다 (sp-docs/domain-model.md §4.3)")
				.isEmpty();
	}

	@Test
	@DisplayName("같은 (글, 판정 키) 를 두 번 저장하면 uk_post_view_post_viewer 위반이다")
	void 같은_글_판정_키_두_번은_UNIQUE_위반이다() {
		postViewRepository.saveAndFlush(view(953L, "a:953"));

		assertThatThrownBy(() -> postViewRepository.saveAndFlush(view(953L, "a:953")))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(e -> assertThat(
						((DataIntegrityViolationException) e).getMostSpecificCause().getMessage())
						.containsIgnoringCase("uk_post_view_post_viewer"));
	}

	/**
	 * {@code ddl-auto: validate} 는 길이 불일치를 통과시킨다 (sp-docs/tech-stack.md §5.2).
	 * 엔티티의 {@code @Column(length)} 와 마이그레이션의 실제 폭을 여기서 직접 맞춘다.
	 */
	@Test
	@DisplayName("information_schema 의 viewer_key 폭이 80 이고 엔티티 선언과 같다")
	void 폭이_viewer_key_80이다() throws Exception {
		Number width = (Number) em.getEntityManager()
				.createNativeQuery("select character_maximum_length from information_schema.columns "
						+ "where table_name = 'post_view' and column_name = 'viewer_key'")
				.getSingleResult();

		assertThat(width.intValue())
				.as("V6__create_post_view.sql 의 viewer_key VARCHAR 폭")
				.isEqualTo(VIEWER_KEY_WIDTH);
		assertThat(PostView.class.getDeclaredField("viewerKey").getAnnotation(Column.class).length())
				.as("엔티티와 마이그레이션이 어긋나도 validate 는 통과한다. 여기서 막는다")
				.isEqualTo(width.intValue());
	}

	/** 제약·인덱스 이름은 sp-docs/domain-model.md §4.3 이 정본이다. */
	@Test
	@DisplayName("post_view 에 uk_post_view_post_viewer·idx_post_view_viewed_at 이 있다")
	void 제약과_인덱스_이름이_정본과_같다() {
		List<?> uniqueNames = em.getEntityManager()
				.createNativeQuery("select constraint_name from information_schema.table_constraints "
						+ "where table_name = 'post_view' and constraint_type = 'UNIQUE'")
				.getResultList();
		List<?> indexes = em.getEntityManager()
				.createNativeQuery("select index_name from information_schema.indexes "
						+ "where table_name = 'post_view'")
				.getResultList();

		assertThat(uniqueNames).map(name -> String.valueOf(name).toLowerCase())
				.containsExactly("uk_post_view_post_viewer");
		assertThat(indexes).map(name -> String.valueOf(name).toLowerCase())
				.contains("idx_post_view_viewed_at");
	}

	private static PostView view(Long postId, String viewerKey) {
		return PostView.builder()
				.postId(postId)
				.viewerKey(viewerKey)
				.viewedAt(VIEWED_AT)
				.build();
	}

}
