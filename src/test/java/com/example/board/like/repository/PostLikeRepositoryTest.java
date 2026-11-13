package com.example.board.like.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.global.config.JpaConfig;
import com.example.board.like.entity.PostLike;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import java.util.List;
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
 * {@code post_like} 의 스키마와 엔티티. sp-docs/plan/phase2-board.md B-14 의 검증 표 중 좋아요에
 * 해당하는 것이다. 정본은 sp-docs/domain-model.md §4.3·§4.4 다.
 *
 * <p>두 애노테이션의 이유는 {@link com.example.board.post.repository.PostRepositoryTest} 와
 * 같다 (sp-docs/conventions.md §9.3).
 *
 * <p>픽스처의 {@code writerId}·{@code accountId} 는 941~949 를 쓴다 — 같은 JVM 의 테스트 클래스가
 * 하나의 DB 를 보므로 클래스마다 대역을 겹치지 않게 둔다 (sp-docs/tech-stack.md §5.3).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("test")
class PostLikeRepositoryTest {

	@Autowired
	private TestEntityManager em;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private PostLikeRepository postLikeRepository;

	@Test
	@DisplayName("저장 후 조회하면 글·계정·createdAt 이 일치한다")
	void 저장_후_조회() {
		Post post = savedPost(941L);
		PostLike saved = postLikeRepository.saveAndFlush(like(post, 942L));
		em.clear();

		PostLike found = postLikeRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getPost().getId()).isEqualTo(post.getId());
		assertThat(found.getAccountId()).isEqualTo(942L);
		assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());
	}

	/**
	 * {@code BaseTimeEntity} 를 상속하지 않아도 감사 시각과 같은 초 자르기를 받는다
	 * (sp-docs/domain-model.md §1.1·§4.4). 자르지 않으면 H2 는 소수부를 그대로 저장한다 —
	 * {@code JpaConfig} 의 주석이 적은 B-06 실측이다. 그래서 다시 읽은 값으로도 본다.
	 */
	@Test
	@DisplayName("PostLike 의 createdAt 에 소수부가 없다")
	void createdAt_에_소수부가_없다() {
		PostLike saved = postLikeRepository.saveAndFlush(like(savedPost(943L), 944L));
		em.clear();

		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getCreatedAt().getNano()).as("쓰기 시점의 엔티티 값").isZero();
		assertThat(postLikeRepository.findById(saved.getId()).orElseThrow().getCreatedAt().getNano())
				.as("저장된 값")
				.isZero();
	}

	/**
	 * {@code account_id} 는 {@code sp_auth.account.id} 의 논리 참조다. 스키마 경계를 넘는 FK 는
	 * 두지 않는다 (sp-docs/domain-model.md §4.3).
	 */
	@Test
	@DisplayName("없는 account_id 로도 저장된다 — FK 가 없다")
	void 없는_account_id_로_저장된다() {
		PostLike saved = postLikeRepository.saveAndFlush(like(savedPost(945L), 999_999_999L));
		em.clear();

		assertThat(postLikeRepository.findById(saved.getId()).orElseThrow().getAccountId())
				.isEqualTo(999_999_999L);
	}

	/** {@code uk_post_like_post_account} 가 회원당 한 번의 최종 판정이다 (sp-docs/domain-model.md §6.1). */
	@Test
	@DisplayName("같은 (글, 계정) 을 두 번 저장하면 uk_post_like_post_account 위반이다")
	void 같은_글_계정_두_번은_UNIQUE_위반이다() {
		Post post = savedPost(946L);
		postLikeRepository.saveAndFlush(like(post, 946L));

		assertThatThrownBy(() -> postLikeRepository.saveAndFlush(like(post, 946L)))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(e -> assertThat(
						((DataIntegrityViolationException) e).getMostSpecificCause().getMessage())
						.containsIgnoringCase("uk_post_like_post_account"));
	}

	/**
	 * 존재 확인은 글과 계정을 <b>둘 다</b> 본다. 한쪽만 다른 경우를 함께 둬야 조건 하나가 빠진
	 * 구현을 가른다.
	 */
	@Test
	@DisplayName("existsByPostIdAndAccountId 는 그 글·그 계정의 행이 있을 때만 true 다")
	void 존재_확인() {
		Post liked = savedPost(947L);
		Post other = savedPost(948L);
		postLikeRepository.saveAndFlush(like(liked, 949L));
		em.clear();

		assertThat(postLikeRepository.existsByPostIdAndAccountId(liked.getId(), 949L)).isTrue();
		assertThat(postLikeRepository.existsByPostIdAndAccountId(liked.getId(), 948L))
				.as("다른 계정")
				.isFalse();
		assertThat(postLikeRepository.existsByPostIdAndAccountId(other.getId(), 949L))
				.as("다른 글")
				.isFalse();
	}

	/**
	 * FK 는 {@code post_id} 의 {@code fk_post_like_post_id} 하나이고 {@code account_id} 에는 없다
	 * (sp-docs/domain-model.md §4.3). 나중 마이그레이션이 {@code account_id} 에 FK 를 붙이는 것을
	 * 여기서 막는다.
	 */
	@Test
	@DisplayName("post_like 의 FK 는 post_id 의 fk_post_like_post_id 이고 account_id 에는 없다")
	void FK_는_post_id_에만_있다() {
		List<?> rows = em.getEntityManager()
				.createNativeQuery("select c.constraint_name, k.column_name "
						+ "from information_schema.table_constraints c "
						+ "join information_schema.key_column_usage k "
						+ "  on k.constraint_name = c.constraint_name "
						+ "where c.table_name = 'post_like' and c.constraint_type = 'FOREIGN KEY'")
				.getResultList();

		assertThat(rows).map(row -> (Object[]) row)
				.map(row -> String.valueOf(row[0]).toLowerCase() + ":" + String.valueOf(row[1]).toLowerCase())
				.containsExactly("fk_post_like_post_id:post_id");
	}

	/** UNIQUE 제약 이름은 sp-docs/domain-model.md §4.3 이 정본이다. */
	@Test
	@DisplayName("post_like 의 UNIQUE 제약 이름이 uk_post_like_post_account 다")
	void UNIQUE_이름이_정본과_같다() {
		List<?> names = em.getEntityManager()
				.createNativeQuery("select constraint_name from information_schema.table_constraints "
						+ "where table_name = 'post_like' and constraint_type = 'UNIQUE'")
				.getResultList();

		assertThat(names).map(name -> String.valueOf(name).toLowerCase())
				.containsExactly("uk_post_like_post_account");
	}

	private Post savedPost(Long writerId) {
		return postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname("글작성자")
				.title("좋아요 테스트용 게시글")
				.content("본문")
				.build());
	}

	private static PostLike like(Post post, Long accountId) {
		return PostLike.builder()
				.post(post)
				.accountId(accountId)
				.build();
	}

}
