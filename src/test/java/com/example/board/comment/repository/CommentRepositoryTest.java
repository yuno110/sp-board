package com.example.board.comment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.board.comment.entity.Comment;
import com.example.board.global.config.JpaConfig;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * sp-docs/plan/phase1.md §6 B-03 의 "검증" 표 중 댓글에 해당하는 것을 테스트로 옮긴 것이다.
 *
 * <p>두 애노테이션의 이유는 {@link com.example.board.post.repository.PostRepositoryTest} 와
 * 같다 (sp-docs/conventions.md §9.3).
 *
 * <p>픽스처의 {@code writerId} 는 301 대역을 쓴다 — 같은 JVM 의 테스트 클래스가 하나의 DB 를
 * 보므로 클래스마다 대역을 겹치지 않게 둔다 (sp-docs/tech-stack.md §5.3).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("test")
class CommentRepositoryTest {

	/** sp-docs/domain-model.md §4.2 가 정한 컬럼 폭이다. */
	private static final int CONTENT_WIDTH = 500;

	private static final int WRITER_NICKNAME_WIDTH = 30;

	@Autowired
	private TestEntityManager em;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private CommentRepository commentRepository;

	@Test
	@DisplayName("저장 후 조회하면 모든 필드가 일치한다")
	void 저장_후_조회() {
		Post post = savedPost(301L, "글작성자");
		Comment saved = commentRepository.saveAndFlush(comment(post, 302L, "댓글작성자", "댓글 내용"));
		em.clear();

		Comment found = commentRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getId()).isEqualTo(saved.getId());
		assertThat(found.getPost().getId()).isEqualTo(post.getId());
		assertThat(found.getWriterId()).isEqualTo(302L);
		assertThat(found.getWriterNickname()).isEqualTo("댓글작성자");
		assertThat(found.getContent()).isEqualTo("댓글 내용");
		assertThat(found.isDeleted()).isFalse();
		assertThat(found.getCreatedAt()).isNotNull();
		assertThat(found.getUpdatedAt()).isNotNull();
	}

	@Test
	@DisplayName("존재하지 않는 writer_id 로도 저장된다 — writer_id 에 FK 제약이 없다")
	void 존재하지_않는_writer_id_로_저장() {
		Post post = savedPost(303L, "글작성자");
		Comment saved = commentRepository.saveAndFlush(
				comment(post, 999_999_999L, "없는계정", "FK 없음"));
		em.clear();

		assertThat(commentRepository.findById(saved.getId()).orElseThrow().getWriterId())
				.isEqualTo(999_999_999L);
	}

	/**
	 * {@code post_id} 는 같은 스키마이므로 FK 를 둔다. {@code writer_id} 는 스키마 경계를 넘는
	 * 논리 참조이므로 두지 않는다 (sp-docs/requirements/board.md §3 규칙 4). 나중
	 * 마이그레이션이 {@code writer_id} 에 FK 를 붙이는 것을 여기서 막는다.
	 */
	@Test
	@DisplayName("comment 의 FOREIGN KEY 는 post_id 하나뿐이고 writer_id 에는 없다")
	void FK_는_post_id_하나뿐이다() {
		List<?> foreignKeyColumns = em.getEntityManager()
				.createNativeQuery("select k.column_name "
						+ "from information_schema.table_constraints c "
						+ "join information_schema.key_column_usage k "
						+ "  on k.constraint_name = c.constraint_name "
						+ "where c.table_name = 'comment' and c.constraint_type = 'FOREIGN KEY'")
				.getResultList();

		assertThat(foreignKeyColumns).map(name -> String.valueOf(name).toLowerCase())
				.containsExactly("post_id");
	}

	/**
	 * FK 제약 이름은 sp-docs/domain-model.md §4.3 이 정본이다 — 그 표가 {@code fk_comment_post_id}
	 * 를 배정했다. 인덱스 이름 네 개를 이름으로 단언하면서 FK 만 컬럼으로 보면 비대칭이고,
	 * 이름이 바뀌어도 아무것도 깨지지 않는다.
	 *
	 * <p>이름은 소문자화해 비교한다 — H2 와 MySQL 의 대소문자 저장이 다를 수 있다
	 * ({@link #인덱스가_있다()} 와 같은 방식이다).
	 */
	@Test
	@DisplayName("comment 의 FK 이름이 fk_comment_post_id 다")
	void FK_이름이_정본과_같다() {
		List<?> constraintNames = em.getEntityManager()
				.createNativeQuery("select constraint_name from information_schema.table_constraints "
						+ "where table_name = 'comment' and constraint_type = 'FOREIGN KEY'")
				.getResultList();

		assertThat(constraintNames).map(name -> String.valueOf(name).toLowerCase())
				.containsExactly("fk_comment_post_id");
	}

	/**
	 * {@code post} 는 {@code @ManyToOne(LAZY)} 여야 한다 (sp-docs/domain-model.md §4.2).
	 *
	 * <p><b>가드가 없으면 조용히 {@code EAGER} 로 바뀐다.</b> 댓글 목록을 읽을 때마다 게시글을
	 * 함께 끌어오는 N+1 이 되고, 목록 조회 p95 300ms 기준(sp-docs/nfr.md §1)을 깎아먹는다.
	 * 기능 테스트는 결과가 같으므로 통과한다 — 그래서 매핑 자체를 읽는다
	 * ({@code PostRepositoryTest.content_가_TEXT_다()} 와 같은 방식이다).
	 */
	@Test
	@DisplayName("post 연관관계가 LAZY 로 매핑된다")
	void post_는_LAZY_다() throws Exception {
		FetchType fetch = Comment.class.getDeclaredField("post")
				.getAnnotation(ManyToOne.class).fetch();

		assertThat(fetch).isEqualTo(FetchType.LAZY);
	}

	@Test
	@DisplayName("저장하면 deleted 가 false 다")
	void 기본값() {
		Post post = savedPost(304L, "글작성자");
		Comment saved = commentRepository.saveAndFlush(comment(post, 305L, "댓글작성자", "기본값"));
		em.clear();

		assertThat(commentRepository.findById(saved.getId()).orElseThrow().isDeleted()).isFalse();
	}

	/**
	 * 폭 경계로 저장을 확인한다 (sp-docs/conventions.md §9.2). 입력 규칙 상한이 500자이고
	 * 컬럼도 500 이라 여유가 없다 (sp-docs/requirements/board.md §4).
	 */
	@Test
	@DisplayName("폭 경계인 500자 content 가 잘리지 않고 저장된다")
	void 내용_상한_저장() {
		Post post = savedPost(306L, "글작성자");
		String content = "댓".repeat(CONTENT_WIDTH);
		Comment saved = commentRepository.saveAndFlush(comment(post, 307L, "댓글작성자", content));
		em.clear();

		assertThat(commentRepository.findById(saved.getId()).orElseThrow().getContent())
				.isEqualTo(content);
	}

	/**
	 * {@code ddl-auto: validate} 는 길이 불일치를 통과시킨다 (sp-docs/tech-stack.md §5.2).
	 * 엔티티의 {@code @Column(length)} 와 마이그레이션의 실제 폭을 여기서 직접 맞춘다.
	 */
	@Test
	@DisplayName("information_schema 의 폭이 content 500, writer_nickname 30 이고 엔티티 선언과 같다")
	void 폭이_content_500_writer_nickname_30이다() throws Exception {
		int contentWidth = actualWidth("content");
		int nicknameWidth = actualWidth("writer_nickname");

		assertThat(contentWidth)
				.as("V2__create_comment.sql 의 content VARCHAR 폭")
				.isEqualTo(CONTENT_WIDTH);
		assertThat(nicknameWidth)
				.as("V2__create_comment.sql 의 writer_nickname VARCHAR 폭")
				.isEqualTo(WRITER_NICKNAME_WIDTH);

		assertThat(declaredLength("content"))
				.as("엔티티와 마이그레이션이 어긋나도 validate 는 통과한다. 여기서 막는다")
				.isEqualTo(contentWidth);
		assertThat(declaredLength("writerNickname")).isEqualTo(nicknameWidth);
	}

	@Test
	@DisplayName("comment 에 idx_comment_post_id 가 있다")
	void 인덱스가_있다() {
		List<?> indexes = em.getEntityManager()
				.createNativeQuery("select index_name from information_schema.indexes "
						+ "where table_name = 'comment'")
				.getResultList();

		assertThat(indexes).map(name -> String.valueOf(name).toLowerCase())
				.contains("idx_comment_post_id");
	}

	@Test
	@DisplayName("update() 는 내용만 바꾸고 작성자 스냅샷은 그대로다")
	void 수정() {
		Post post = savedPost(308L, "글작성자");
		Comment saved = commentRepository.saveAndFlush(comment(post, 309L, "수정전닉네임", "수정 전 내용"));
		Long id = saved.getId();
		em.clear();

		commentRepository.findById(id).orElseThrow().update("수정 후 내용");
		em.flush();
		em.clear();

		Comment found = commentRepository.findById(id).orElseThrow();
		assertThat(found.getContent()).isEqualTo("수정 후 내용");
		assertThat(found.getWriterId()).isEqualTo(309L);
		assertThat(found.getWriterNickname()).isEqualTo("수정전닉네임");
	}

	@Test
	@DisplayName("softDelete() 는 deleted 를 true 로 만들고 행은 남긴다")
	void 논리_삭제() {
		Post post = savedPost(310L, "글작성자");
		Comment saved = commentRepository.saveAndFlush(comment(post, 311L, "댓글작성자", "삭제될 댓글"));
		Long id = saved.getId();
		em.clear();

		commentRepository.findById(id).orElseThrow().softDelete();
		em.flush();
		em.clear();

		assertThat(commentRepository.findById(id))
				.as("\"삭제된 댓글입니다\" 로 표시할 수 있도록 행을 남긴다")
				.isPresent();
		assertThat(commentRepository.findById(id).orElseThrow().isDeleted()).isTrue();
	}

	/**
	 * 게시글 삭제 시 하위 댓글을 함께 논리 삭제한다 (sp-docs/requirements/board.md §5 규칙 3).
	 * B-07 이 이 메서드를 쓴다.
	 *
	 * <p><b>다른 게시글의 댓글을 함께 보는 것이 핵심이다.</b> 한 게시글만 두면
	 * {@code where} 절이 사라진 구현(전체 댓글 삭제)도 통과한다.
	 */
	@Test
	@DisplayName("softDeleteByPostId() 는 그 게시글의 댓글만 전부 deleted = true 로 만든다")
	void 게시글별_연쇄_논리삭제() {
		Post target = savedPost(312L, "삭제될글작성자");
		Post other = savedPost(313L, "남는글작성자");
		Comment first = commentRepository.saveAndFlush(comment(target, 314L, "댓글1", "대상 댓글 1"));
		Comment second = commentRepository.saveAndFlush(comment(target, 315L, "댓글2", "대상 댓글 2"));
		Comment untouched = commentRepository.saveAndFlush(comment(other, 316L, "댓글3", "남는 댓글"));
		em.clear();

		int affected = commentRepository.softDeleteByPostId(target.getId());

		assertThat(affected).isEqualTo(2);
		assertThat(commentRepository.findById(first.getId()).orElseThrow().isDeleted()).isTrue();
		assertThat(commentRepository.findById(second.getId()).orElseThrow().isDeleted()).isTrue();
		assertThat(commentRepository.findById(untouched.getId()).orElseThrow().isDeleted())
				.as("다른 게시글의 댓글은 건드리지 않는다")
				.isFalse();
	}

	@Test
	@DisplayName("softDeleteByPostId() 는 댓글이 없으면 0 을 돌려준다")
	void 댓글이_없는_게시글의_연쇄_논리삭제() {
		Post post = savedPost(317L, "댓글없는글작성자");
		em.clear();

		assertThat(commentRepository.softDeleteByPostId(post.getId())).isZero();
	}

	private int actualWidth(String columnName) {
		Number width = (Number) em.getEntityManager()
				.createNativeQuery("select character_maximum_length from information_schema.columns "
						+ "where table_name = 'comment' and column_name = :columnName")
				.setParameter("columnName", columnName)
				.getSingleResult();
		return width.intValue();
	}

	private static int declaredLength(String fieldName) throws Exception {
		return Comment.class.getDeclaredField(fieldName).getAnnotation(Column.class).length();
	}

	private Post savedPost(Long writerId, String writerNickname) {
		return postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname(writerNickname)
				.title("댓글 테스트용 게시글")
				.content("본문")
				.build());
	}

	private static Comment comment(Post post, Long writerId, String writerNickname, String content) {
		return Comment.builder()
				.post(post)
				.writerId(writerId)
				.writerNickname(writerNickname)
				.content(content)
				.build();
	}

}
