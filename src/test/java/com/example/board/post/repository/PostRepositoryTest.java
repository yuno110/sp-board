package com.example.board.post.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.global.config.JpaConfig;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.post.entity.Post;
import com.example.board.post.entity.PostCategory;
import com.example.board.post.support.PostReader;
import jakarta.persistence.Column;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * sp-docs/plan/phase1.md §6 B-03 의 "검증" 표 중 게시글에 해당하는 것을 테스트로 옮긴 것이다.
 * 환경 자체에 대한 것(실행 중 DataSource URL, 저장된 테이블명, 스키마 출처)은
 * {@link com.example.board.TestDatabaseEnvironmentTest} 가 본다.
 *
 * <p>{@code replace = NONE} 이 필수다. {@code @DataJpaTest} 의 기본값은 DataSource 를
 * {@code jdbc:h2:mem:<uuid>} 로 교체해 {@code MODE=MySQL} 을 날려버린다
 * (sp-docs/tech-stack.md §5.2). 그러면 {@code TEXT}·{@code AUTO_INCREMENT} 를 쓰는
 * 마이그레이션이 호환 모드 없이 실행된다.
 *
 * <p>스키마는 Flyway 가 만들고 {@code ddl-auto: validate} 가 엔티티와 대조한다. 이 클래스가
 * 로딩되는 것 자체가 마이그레이션과 엔티티 매핑의 일치를 뜻한다. <b>다만 {@code validate} 는
 * 컬럼 폭을 보지 않으므로</b> {@link #폭이_title_200_writer_nickname_30이다()} 가 실제 폭을
 * 따로 읽는다.
 *
 * <p>{@code JpaConfig} 를 {@code @Import} 하는 이유 — {@code @DataJpaTest} 슬라이스는
 * {@code @Configuration} 을 컴포넌트 스캔하지 않으므로 {@code @EnableJpaAuditing} 이 빠진다.
 * 그러면 {@code created_at} 이 NULL 로 들어가 NOT NULL 위반이 난다
 * (sp-docs/conventions.md §9.3). {@code PostReader} 도 같은 이유로 직접 올린다.
 *
 * <p>픽스처의 {@code writerId} 는 201 대역을 쓴다(B-03 이 201~210, B-14 가 211~229).
 * {@code DB_CLOSE_DELAY=-1} 과 고정 스키마명 때문에 같은 JVM 의 모든 테스트 클래스가 하나의 DB 를
 * 보므로 (sp-docs/tech-stack.md §5.3), 클래스마다 대역을 겹치지 않게 둔다. 지금은 각 테스트가
 * {@code @DataJpaTest} 의 기본 롤백으로 정리되어 행이 남지 않지만, 대역을 나눠 두면 나중에
 * 정리 방식이 바뀌어도 클래스 간 결합이 생기지 않는다.
 *
 * <p>2차의 컬럼·인덱스와 {@code like_count}·{@code attachment_count} 의 좁은 UPDATE 는
 * sp-docs/plan/phase2-board.md B-14 의 검증 표에서 왔다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, PostReader.class})
@ActiveProfiles("test")
class PostRepositoryTest {

	/** sp-docs/domain-model.md §4.1 이 정한 컬럼 폭이다. */
	private static final int TITLE_WIDTH = 200;

	private static final int WRITER_NICKNAME_WIDTH = 30;

	private static final int CATEGORY_WIDTH = 20;

	/** sp-docs/requirements/board.md §4 의 게시글 본문 입력 상한이다. */
	private static final int CONTENT_MAX_LENGTH = 10_000;

	/** sp-docs/requirements/board.md §11 의 글당 활성 첨부 상한이다. */
	private static final int ATTACHMENT_LIMIT = 10;

	@Autowired
	private TestEntityManager em;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private PostReader postReader;

	@Autowired
	private StatementRecorder statementRecorder;

	@Test
	@DisplayName("저장 후 조회하면 모든 필드가 일치한다")
	void 저장_후_조회() {
		Post saved = postRepository.saveAndFlush(post(201L, "홍길동", "첫 글", "첫 글 본문"));
		em.clear();

		Post found = postRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getId()).isEqualTo(saved.getId());
		assertThat(found.getWriterId()).isEqualTo(201L);
		assertThat(found.getWriterNickname()).isEqualTo("홍길동");
		assertThat(found.getTitle()).isEqualTo("첫 글");
		assertThat(found.getContent()).isEqualTo("첫 글 본문");
		assertThat(found.getViewCount()).isZero();
		assertThat(found.getCommentCount()).isZero();
		assertThat(found.isDeleted()).isFalse();
		assertThat(found.getCreatedAt()).isNotNull();
		assertThat(found.getUpdatedAt()).isNotNull();
	}

	/**
	 * {@code writer_id} 는 {@code sp_auth.account.id} 의 <b>논리</b> 참조다. 스키마 경계를 넘는
	 * FK 제약은 금지이고, 걸면 서비스 간 배포·삭제 순서가 강결합된다
	 * (sp-docs/requirements/board.md §3 규칙 4, sp-docs/architecture.md §5).
	 */
	@Test
	@DisplayName("존재하지 않는 writer_id 로도 저장된다 — FK 제약이 없다")
	void 존재하지_않는_writer_id_로_저장() {
		Post saved = postRepository.saveAndFlush(
				post(999_999_999L, "없는계정", "FK 없음", "본문"));
		em.clear();

		assertThat(postRepository.findById(saved.getId()).orElseThrow().getWriterId())
				.isEqualTo(999_999_999L);
	}

	/**
	 * {@code V1} 을 한 번 읽어 FK 가 없음을 확인하는 것으로는 <b>나중 마이그레이션이 FK 를
	 * 추가하는 것</b>을 막지 못한다. 그래서 지금 테이블에 걸려 있는 제약을 읽는다.
	 */
	@Test
	@DisplayName("post 테이블에 FOREIGN KEY 제약이 하나도 없다")
	void post_에_FK_제약이_없다() {
		List<?> foreignKeys = em.getEntityManager()
				.createNativeQuery("select constraint_name from information_schema.table_constraints "
						+ "where table_name = 'post' and constraint_type = 'FOREIGN KEY'")
				.getResultList();

		assertThat(foreignKeys).map(String::valueOf)
				.as("writer_id 는 sp_auth.account.id 의 논리 참조다 (sp-docs/architecture.md §5)")
				.isEmpty();
	}

	@Test
	@DisplayName("저장하면 viewCount=0, commentCount=0, deleted=false 다")
	void 기본값() {
		Post saved = postRepository.saveAndFlush(post(202L, "신규작성자", "기본값", "본문"));
		em.clear();

		Post found = postRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getViewCount()).isZero();
		assertThat(found.getCommentCount()).isZero();
		assertThat(found.isDeleted()).isFalse();
	}

	/**
	 * {@code view_count}·{@code comment_count} 의 <b>DB DEFAULT</b> 를 본다. 엔티티 생성자가
	 * 0 을 넣는 것과 별개로 마이그레이션이 기본값을 갖는지는 스키마의 몫이다
	 * (sp-docs/domain-model.md §4.1).
	 */
	@Test
	@DisplayName("view_count·comment_count 컬럼의 DB 기본값이 0 이다")
	void DB_기본값이_0이다() {
		assertThat(columnDefault("view_count")).isEqualTo("0");
		assertThat(columnDefault("comment_count")).isEqualTo("0");
	}

	/**
	 * 길이 제약이 의미를 갖는 컬럼은 <b>실제로 들어갈 최대 길이</b>로 저장을 확인한다
	 * (sp-docs/conventions.md §9.2). {@code content} 가 {@code VARCHAR} 로 좁아지면 여기서
	 * 터진다 — {@code TEXT} 라야 10,000자가 들어간다 (sp-docs/domain-model.md §4.2).
	 */
	@Test
	@DisplayName("10,000자 content 가 잘리지 않고 저장된다")
	void 본문_상한_저장() {
		String content = "본".repeat(CONTENT_MAX_LENGTH);
		Post saved = postRepository.saveAndFlush(post(203L, "장문작성자", "긴 본문", content));
		em.clear();

		assertThat(postRepository.findById(saved.getId()).orElseThrow().getContent())
				.hasSize(CONTENT_MAX_LENGTH)
				.isEqualTo(content);
	}

	@Test
	@DisplayName("content 는 TEXT 로 매핑된다")
	void content_가_TEXT_다() throws Exception {
		String columnDefinition = Post.class.getDeclaredField("content")
				.getAnnotation(Column.class).columnDefinition();

		assertThat(columnDefinition)
				.as("sp-docs/domain-model.md §4.1 이 columnDefinition = \"TEXT\" 를 정했다")
				.isEqualTo("TEXT");
	}

	@Test
	@DisplayName("폭 경계인 200자 title 이 잘리지 않고 저장된다")
	void 제목_상한_저장() {
		String title = "제".repeat(TITLE_WIDTH);
		Post saved = postRepository.saveAndFlush(post(204L, "경계작성자", title, "본문"));
		em.clear();

		assertThat(postRepository.findById(saved.getId()).orElseThrow().getTitle())
				.isEqualTo(title);
	}

	/**
	 * {@code ddl-auto: validate} 는 길이 불일치를 통과시킨다 (sp-docs/tech-stack.md §5.2).
	 * board 는 입력 규칙과 컬럼 폭이 같아 여유가 없으므로 (sp-docs/domain-model.md §4.2),
	 * 엔티티의 {@code @Column(length)} 와 마이그레이션의 실제 폭을 여기서 직접 맞춘다.
	 */
	@Test
	@DisplayName("information_schema 의 폭이 title 200, writer_nickname 30 이고 엔티티 선언과 같다")
	void 폭이_title_200_writer_nickname_30이다() throws Exception {
		int titleWidth = actualWidth("title");
		int nicknameWidth = actualWidth("writer_nickname");

		assertThat(titleWidth).as("V1__create_post.sql 의 title VARCHAR 폭").isEqualTo(TITLE_WIDTH);
		assertThat(nicknameWidth)
				.as("V1__create_post.sql 의 writer_nickname VARCHAR 폭")
				.isEqualTo(WRITER_NICKNAME_WIDTH);

		assertThat(declaredLength("title"))
				.as("엔티티와 마이그레이션이 어긋나도 validate 는 통과한다. 여기서 막는다")
				.isEqualTo(titleWidth);
		assertThat(declaredLength("writerNickname")).isEqualTo(nicknameWidth);
	}

	/**
	 * 인덱스 이름은 sp-docs/domain-model.md §4.3 이 정본이다. 나중 마이그레이션이 인덱스를
	 * 떨어뜨리면 여기서 걸린다.
	 */
	@Test
	@DisplayName("post 에 idx_post_created_at·idx_post_writer_id·idx_post_title 이 있다")
	void 인덱스가_있다() {
		List<?> indexes = em.getEntityManager()
				.createNativeQuery("select index_name from information_schema.indexes "
						+ "where table_name = 'post'")
				.getResultList();

		assertThat(indexes).map(name -> String.valueOf(name).toLowerCase())
				.contains("idx_post_created_at", "idx_post_writer_id", "idx_post_title");
	}

	@Test
	@DisplayName("update() 는 제목·내용만 바꾸고 writerId·writerNickname 은 그대로다")
	void 수정() {
		Post saved = postRepository.saveAndFlush(post(205L, "수정전닉네임", "수정 전 제목", "수정 전 본문"));
		Long id = saved.getId();
		em.clear();

		postRepository.findById(id).orElseThrow().update("수정 후 제목", "수정 후 본문");
		em.flush();
		em.clear();

		Post found = postRepository.findById(id).orElseThrow();
		assertThat(found.getTitle()).isEqualTo("수정 후 제목");
		assertThat(found.getContent()).isEqualTo("수정 후 본문");
		assertThat(found.getWriterId())
				.as("수정 시 작성자 스냅샷을 갱신하지 않는다 (sp-docs/requirements/board.md §3 규칙 7)")
				.isEqualTo(205L);
		assertThat(found.getWriterNickname()).isEqualTo("수정전닉네임");
	}

	@Test
	@DisplayName("softDelete() 는 deleted 를 true 로 만들고 행은 남긴다")
	void 논리_삭제() {
		Post saved = postRepository.saveAndFlush(post(206L, "삭제하는사람", "삭제될 글", "본문"));
		Long id = saved.getId();
		em.clear();

		postRepository.findById(id).orElseThrow().softDelete();
		em.flush();
		em.clear();

		assertThat(postRepository.findById(id)).isPresent();
		assertThat(postRepository.findById(id).orElseThrow().isDeleted()).isTrue();
	}

	@Test
	@DisplayName("increaseViewCount() 를 2회 부르면 viewCount 가 2 다")
	void 조회수_증가() {
		Post saved = postRepository.saveAndFlush(post(207L, "조회대상작성자", "조회수", "본문"));
		Long id = saved.getId();
		em.clear();

		Post target = postRepository.findById(id).orElseThrow();
		target.increaseViewCount();
		target.increaseViewCount();
		em.flush();
		em.clear();

		assertThat(postRepository.findById(id).orElseThrow().getViewCount()).isEqualTo(2L);
	}

	@Test
	@DisplayName("increaseCommentCount() 3회 후 decreaseCommentCount() 1회면 commentCount 가 2 다")
	void 댓글수_증감() {
		Post saved = postRepository.saveAndFlush(post(208L, "댓글수작성자", "댓글 수", "본문"));
		Long id = saved.getId();
		em.clear();

		Post target = postRepository.findById(id).orElseThrow();
		target.increaseCommentCount();
		target.increaseCommentCount();
		target.increaseCommentCount();
		target.decreaseCommentCount();
		em.flush();
		em.clear();

		assertThat(postRepository.findById(id).orElseThrow().getCommentCount()).isEqualTo(2);
	}

	/**
	 * 회귀 테스트다. {@code comment_count} 가 음수가 되지 않아야 한다
	 * (sp-docs/domain-model.md §5, sp-docs/requirements/board.md §5 규칙 4).
	 *
	 * <p><b>이 테스트가 보는 것은 {@link Post#decreaseCommentCount()} 의 가드다</b> — 엔티티
	 * 메서드를 직접 부르므로 운영의 댓글 삭제 경로를 지나지 않는다. B-03 의 완료 기준이 그
	 * 메서드에 거는 요구이고, 메서드가 그 자리에 남아 있는 한 그대로 유효하다
	 * (sp-docs/plan/phase1.md §6 B-03).
	 *
	 * <p><b>운영 경로의 음수 방지는 여기가 아니다.</b> B-10 이 집계 갱신을 좁은 UPDATE 로
	 * 바꾸면서 가드가 {@code PostRepository.decreaseCommentCount} 의 {@code where} 절
	 * ({@code comment_count > 0}) 로 옮겨 갔고, 좁은 UPDATE 는 엔티티를 지나지 않는다
	 * (sp-docs/plan/phase1.md §6 B-10). 그 층은
	 * {@code CommentServiceTest.집계는_음수가_되지_않는다()} 가 본다.
	 *
	 * <p><b>두 층이 각각 자기 반증자를 갖는다</b>(실측). 그 {@code where} 절만 지우면 이
	 * 테스트는 녹색으로 남고 저쪽만 0 을 기대한 자리에서 {@code -1} 을 관측해 빨개진다 —
	 * 한쪽을 지워도 다른 쪽이 조용히 덮어 주지 않는다.
	 */
	@Test
	@DisplayName("commentCount 가 0 일 때 decreaseCommentCount() 를 불러도 0 을 유지한다")
	void 댓글수는_음수가_되지_않는다() {
		Post saved = postRepository.saveAndFlush(post(209L, "댓글없는작성자", "댓글 없음", "본문"));
		Long id = saved.getId();
		em.clear();

		Post target = postRepository.findById(id).orElseThrow();
		assertThat(target.getCommentCount()).isZero();
		target.decreaseCommentCount();
		target.decreaseCommentCount();
		em.flush();
		em.clear();

		assertThat(postRepository.findById(id).orElseThrow().getCommentCount())
				.as("가드가 없으면 -2 가 저장된다")
				.isZero();
	}

	@Test
	@DisplayName("PostReader 는 없는 id 조회 시 BusinessException(P001) 을 던진다")
	void 없는_글_조회() {
		assertThatThrownBy(() -> postReader.getActiveById(999_999L))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POST_NOT_FOUND);
	}

	/**
	 * 삭제된 게시글은 목록·상세에서 제외한다 (sp-docs/requirements/board.md §5 규칙 1). 행은
	 * 남아 있으므로 {@code findById} 로는 조회되고, 그 판정을 {@code PostReader} 가 맡는다.
	 */
	@Test
	@DisplayName("PostReader 는 삭제된 글도 P001 로 본다")
	void 삭제된_글_조회() {
		Post saved = postRepository.saveAndFlush(post(210L, "삭제된글작성자", "삭제된 글", "본문"));
		Long id = saved.getId();
		saved.softDelete();
		em.flush();
		em.clear();

		assertThat(postRepository.findById(id)).as("행은 남는다").isPresent();
		assertThatThrownBy(() -> postReader.getActiveById(id))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POST_NOT_FOUND);
	}

	@Test
	@DisplayName("카테고리를 생략하고 만들면 FREE, 고르면 그 값으로 저장된다")
	void 카테고리_생략은_FREE() {
		Post omitted = postRepository.saveAndFlush(post(211L, "카테고리생략", "생략", "본문"));
		Post chosen = postRepository.saveAndFlush(Post.builder()
				.writerId(212L)
				.writerNickname("카테고리선택")
				.title("질문")
				.content("본문")
				.category(PostCategory.QNA)
				.build());
		em.clear();

		assertThat(postRepository.findById(omitted.getId()).orElseThrow().getCategory())
				.as("생략하면 FREE (sp-docs/requirements/board.md §8)")
				.isEqualTo(PostCategory.FREE);
		assertThat(postRepository.findById(chosen.getId()).orElseThrow().getCategory())
				.isEqualTo(PostCategory.QNA);
	}

	/**
	 * 수정 본문의 {@code category} 는 선택이고 생략하면 지금 값을 유지한다
	 * (sp-docs/requirements/board.md §8). {@code update(title, content)} 는 카테고리를 건드리지
	 * 않는다 — 그 모양은 B-03 이 요구한다 (sp-docs/plan/phase2-board.md B-14).
	 */
	@Test
	@DisplayName("changeCategory() 는 null 이면 지금 값을 유지하고, update() 는 카테고리를 바꾸지 않는다")
	void 카테고리_바꾸기() {
		Post saved = postRepository.saveAndFlush(post(213L, "카테고리변경", "변경", "본문"));
		Long id = saved.getId();
		em.clear();

		Post target = postRepository.findById(id).orElseThrow();
		target.changeCategory(PostCategory.NOTICE);
		target.update("바뀐 제목", "바뀐 본문");
		em.flush();
		em.clear();
		assertThat(postRepository.findById(id).orElseThrow().getCategory())
				.as("update() 가 changeCategory() 의 값을 되돌리지 않는다")
				.isEqualTo(PostCategory.NOTICE);

		postRepository.findById(id).orElseThrow().changeCategory(null);
		em.flush();
		em.clear();
		assertThat(postRepository.findById(id).orElseThrow().getCategory())
				.as("생략(null)은 지금 값을 유지한다")
				.isEqualTo(PostCategory.NOTICE);
	}

	/**
	 * 새 컬럼의 <b>DB DEFAULT</b> 를 본다. 엔티티 생성자가 값을 넣는 것과 별개로, 기존 행과
	 * 엔티티를 지나지 않는 INSERT 를 채우는 것은 마이그레이션의 기본값이다
	 * (sp-docs/domain-model.md §4.1·§7).
	 */
	@Test
	@DisplayName("새 컬럼을 생략한 post INSERT 는 category FREE, like_count 0, attachment_count 0 이다")
	void 새_컬럼을_생략한_INSERT() {
		em.getEntityManager()
				.createNativeQuery("insert into post (writer_id, writer_nickname, title, content, "
						+ "created_at, updated_at) values (214, '기본값작성자', '기본값', '본문', "
						+ "current_timestamp, current_timestamp)")
				.executeUpdate();

		Object[] row = (Object[]) em.getEntityManager()
				.createNativeQuery("select category, like_count, attachment_count from post "
						+ "where writer_id = 214")
				.getSingleResult();

		assertThat(String.valueOf(row[0])).isEqualTo("FREE");
		assertThat(((Number) row[1]).intValue()).isZero();
		assertThat(((Number) row[2]).intValue()).isZero();
	}

	/** {@link #폭이_title_200_writer_nickname_30이다()} 와 같은 이유다 (sp-docs/conventions.md §9.2). */
	@Test
	@DisplayName("information_schema 의 category 폭이 20 이고 엔티티 선언과 같다")
	void 폭이_category_20이다() throws Exception {
		int categoryWidth = actualWidth("category");

		assertThat(categoryWidth)
				.as("V3__add_post_category_and_counts.sql 의 category VARCHAR 폭")
				.isEqualTo(CATEGORY_WIDTH);
		assertThat(declaredLength("category"))
				.as("엔티티와 마이그레이션이 어긋나도 validate 는 통과한다. 여기서 막는다")
				.isEqualTo(categoryWidth);
	}

	/** 인덱스 이름은 sp-docs/domain-model.md §4.3 이 정본이다. */
	@Test
	@DisplayName("post 에 idx_post_category 가 있다")
	void 카테고리_인덱스가_있다() {
		List<?> indexes = em.getEntityManager()
				.createNativeQuery("select index_name from information_schema.indexes "
						+ "where table_name = 'post'")
				.getResultList();

		assertThat(indexes).map(name -> String.valueOf(name).toLowerCase())
				.contains("idx_post_category");
	}

	/**
	 * {@code like_count} 의 좁은 UPDATE (sp-docs/domain-model.md §6.1). 1 행인 경우를 함께 봐야
	 * 0 행 단언이 뜻을 갖는다 — 늘 0 을 돌려주는 문장도 0 행 단언은 통과한다.
	 */
	@Test
	@DisplayName("like_count 는 +1·-1 이 1 행이고, 0 에서 -1 은 0 행이며 음수가 되지 않는다")
	void like_count_증감() {
		Long id = postRepository.saveAndFlush(post(215L, "좋아요증감", "좋아요", "본문")).getId();

		assertThat(postRepository.increaseLikeCount(id)).isEqualTo(1);
		assertThat(column(id, "like_count")).isEqualTo(1);
		assertThat(postRepository.decreaseLikeCount(id)).isEqualTo(1);
		assertThat(column(id, "like_count")).isZero();

		assertThat(postRepository.decreaseLikeCount(id)).as("0 에서 -1").isZero();
		assertThat(column(id, "like_count")).as("음수가 되지 않는다").isZero();
	}

	/**
	 * 지워진 글의 좋아요는 0 행이다 — 부르는 쪽이 404 {@code P001} 로 바꾼다
	 * (sp-docs/domain-model.md §6.1). 감소는 집계를 1 로 둔 뒤 지워서 본다 — 0 에서 보면
	 * {@code like_count > 0} 조건만으로도 0 행이라 {@code deleted} 조건을 가르지 못한다.
	 */
	@Test
	@DisplayName("지워진 글에 like_count +1·-1 은 0 행이다")
	void 지워진_글의_like_count() {
		Long id = postRepository.saveAndFlush(post(216L, "좋아요삭제", "지울 글", "본문")).getId();
		assertThat(postRepository.increaseLikeCount(id)).isEqualTo(1);
		markDeleted(id);

		assertThat(postRepository.increaseLikeCount(id)).as("지워진 글에 +1").isZero();
		assertThat(postRepository.decreaseLikeCount(id)).as("지워진 글에 -1").isZero();
		assertThat(column(id, "like_count")).isEqualTo(1);
	}

	/**
	 * 개수 상한의 판정은 이 문장 하나다 (sp-docs/domain-model.md §6.2). 상한 바로 아래(9 → 10)가
	 * 1 행이고 상한에서(10 → 11) 0 행이어야 판정이 엄격 부등호({@code < :limit})로 고정된다. 상한
	 * 값은 이 테스트가 인자로 넘기는 것이고, 운영 코드의 값은 그 값을 쓰는 항목이 고정한다.
	 */
	@Test
	@DisplayName("attachment_count 는 상한까지 +1 이 1 행이고, 상한인 글에 +1 은 0 행이다")
	void attachment_count_상한() {
		Long id = postRepository.saveAndFlush(post(217L, "첨부상한", "첨부", "본문")).getId();

		for (int i = 0; i < ATTACHMENT_LIMIT; i++) {
			assertThat(postRepository.increaseAttachmentCount(id, ATTACHMENT_LIMIT))
					.as("%d 번째 +1", i + 1)
					.isEqualTo(1);
		}
		assertThat(postRepository.increaseAttachmentCount(id, ATTACHMENT_LIMIT))
				.as("상한인 글에 +1")
				.isZero();
		assertThat(column(id, "attachment_count")).isEqualTo(ATTACHMENT_LIMIT);
	}

	@Test
	@DisplayName("지워진 글에 attachment_count +1 은 0 행이다")
	void 지워진_글의_attachment_count_증가() {
		Long id = postRepository.saveAndFlush(post(218L, "첨부삭제글", "지울 글", "본문")).getId();
		markDeleted(id);

		assertThat(postRepository.increaseAttachmentCount(id, ATTACHMENT_LIMIT)).isZero();
		assertThat(column(id, "attachment_count")).isZero();
	}

	@Test
	@DisplayName("attachment_count 는 -1 이 1 행이고, 0 에서 -1 은 0 행이며 음수가 되지 않는다")
	void attachment_count_감소() {
		Long id = postRepository.saveAndFlush(post(219L, "첨부감소", "첨부", "본문")).getId();
		assertThat(postRepository.increaseAttachmentCount(id, ATTACHMENT_LIMIT)).isEqualTo(1);

		assertThat(postRepository.decreaseAttachmentCount(id)).isEqualTo(1);
		assertThat(column(id, "attachment_count")).isZero();

		assertThat(postRepository.decreaseAttachmentCount(id)).as("0 에서 -1").isZero();
		assertThat(column(id, "attachment_count")).as("음수가 되지 않는다").isZero();
	}

	/**
	 * 새 문장이 그 집계 컬럼 하나만 싣는지 발행된 SQL 로 본다 — B-10 의 관측 방식이다
	 * (sp-docs/plan/phase1.md §6 B-10). 엔티티 더티 체킹의 UPDATE 는 모든 컬럼을 실으므로
	 * {@code title}·{@code content} 가 들어 있다.
	 */
	@Test
	@DisplayName("새 문장이 발행한 update post 는 그 집계 컬럼만 싣고 title·content 가 없다")
	void 새_문장은_그_집계_컬럼만_싣는다() {
		Long id = postRepository.saveAndFlush(post(220L, "문장모양", "문장", "본문")).getId();

		assertThat(발행된_post_UPDATE(() -> postRepository.increaseLikeCount(id)))
				.singleElement().satisfies(sql -> 그_컬럼만_싣는다(sql, "like_count"));
		assertThat(발행된_post_UPDATE(() -> postRepository.decreaseLikeCount(id)))
				.singleElement().satisfies(sql -> 그_컬럼만_싣는다(sql, "like_count"));
		assertThat(발행된_post_UPDATE(() -> postRepository.increaseAttachmentCount(id, ATTACHMENT_LIMIT)))
				.singleElement().satisfies(sql -> 그_컬럼만_싣는다(sql, "attachment_count"));
		assertThat(발행된_post_UPDATE(() -> postRepository.decreaseAttachmentCount(id)))
				.singleElement().satisfies(sql -> 그_컬럼만_싣는다(sql, "attachment_count"));
	}

	private List<String> 발행된_post_UPDATE(Runnable action) {
		statementRecorder.clear();
		action.run();
		return statementRecorder.postUpdates();
	}

	private static void 그_컬럼만_싣는다(String sql, String column) {
		String setClause = sql.substring(sql.indexOf(" set ") + " set ".length(), sql.indexOf(" where "));
		assertThat(setClause).as("SET 절: %s", sql).contains(column).doesNotContain(",");
		assertThat(sql).doesNotContain("title").doesNotContain("content");
	}

	private int column(Long id, String columnName) {
		Number value = (Number) em.getEntityManager()
				.createNativeQuery("select " + columnName + " from post where id = :id")
				.setParameter("id", id)
				.getSingleResult();
		return value.intValue();
	}

	private void markDeleted(Long id) {
		em.getEntityManager()
				.createNativeQuery("update post set deleted = true where id = :id")
				.setParameter("id", id)
				.executeUpdate();
	}

	private int actualWidth(String columnName) {
		Number width = (Number) em.getEntityManager()
				.createNativeQuery("select character_maximum_length from information_schema.columns "
						+ "where table_name = 'post' and column_name = :columnName")
				.setParameter("columnName", columnName)
				.getSingleResult();
		return width.intValue();
	}

	private String columnDefault(String columnName) {
		return String.valueOf(em.getEntityManager()
				.createNativeQuery("select column_default from information_schema.columns "
						+ "where table_name = 'post' and column_name = :columnName")
				.setParameter("columnName", columnName)
				.getSingleResult());
	}

	private static int declaredLength(String fieldName) throws Exception {
		return Post.class.getDeclaredField(fieldName).getAnnotation(Column.class).length();
	}

	private static Post post(Long writerId, String writerNickname, String title, String content) {
		return Post.builder()
				.writerId(writerId)
				.writerNickname(writerNickname)
				.title(title)
				.content(content)
				.build();
	}

	/**
	 * 발행된 SQL 을 모은다. Hibernate 의 {@code StatementInspector} 라 문장이 JDBC 로 넘어가기
	 * 전에 불린다. 로그·통계 설정에 기대지 않는다 — 그 설정이 꺼지면 조용히 빈 결과가 되지만, 이
	 * 수집기는 테스트가 직접 꽂고 꽂히지 않으면 {@code singleElement()} 가 빈 목록에서 터진다
	 * ({@code CommentServiceTest.StatementRecorder} 와 같은 수단이다).
	 */
	static class StatementRecorder implements StatementInspector {

		private final List<String> statements = new CopyOnWriteArrayList<>();

		@Override
		public String inspect(String sql) {
			this.statements.add(sql);
			return sql;
		}

		void clear() {
			this.statements.clear();
		}

		/** {@code post} 를 바꾸는 UPDATE 만. 소문자·공백이 정규화된 문장이다. */
		List<String> postUpdates() {
			return this.statements.stream()
					.map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim())
					.filter(sql -> sql.startsWith("update post"))
					.toList();
		}

	}

	/** {@link StatementRecorder} 를 Hibernate 에 꽂는다. */
	@TestConfiguration
	static class StatementRecorderConfig {

		@Bean
		StatementRecorder statementRecorder() {
			return new StatementRecorder();
		}

		@Bean
		HibernatePropertiesCustomizer statementRecorderRegistration(StatementRecorder recorder) {
			return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, recorder);
		}

	}

}
