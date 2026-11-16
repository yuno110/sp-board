package com.example.board.like.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.like.dto.LikeResponse;
import com.example.board.like.entity.PostLike;
import com.example.board.like.repository.PostLikeRepository;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * sp-docs/plan/phase2-board.md B-20 의 검증 표 중 저장·집계·순서·동시성 쪽이다. HTTP 상태·응답 키·
 * 인증·member 호출 0회·글 삭제 뒤의 행은
 * {@link com.example.board.like.controller.LikeControllerTest} 가 본다.
 *
 * <p>정본은 sp-docs/requirements/board.md §10, sp-docs/domain-model.md §4.4 §6 §6.1,
 * sp-docs/api-contract.md §4.6 이다.
 *
 * <p>리포지터리는 스텁으로 바꾸지 않고 H2 를 그대로 쓴다 — 「증가가 남지 않는다」 는 커밋된 행을
 * 봐야 확인된다. {@link PostLikeRepository} 는 스파이다 — 대역으로 바꾼 메서드 밖은 실제 빈에
 * 위임된다. 바꾸는 것은 UNIQUE 충돌 경로의 확인과, UNIQUE 가 아닌 위반을 던지는 저장이다.
 *
 * <p><b>커밋된 값은 {@code JdbcTemplate} 으로 읽는다.</b> 서비스가 스스로 커밋하므로 영속성
 * 컨텍스트가 아니라 DB 를 봐야 하고, 동시성 테스트에서는 다른 스레드가 커밋한 것을 봐야 한다.
 *
 * <p><b>픽스처는 서비스를 거치지 않고 만든다</b>({@link #누른_상태로_둔다}). 서비스에 넣은 변이가
 * 픽스처까지 바꾸면 단언이 무엇을 보는지 흐려진다.
 *
 * <p>픽스처의 {@code writerId} 와 좋아요를 누르는 {@code accountId} 는 1101~1149 를 쓴다. 같은 JVM 의
 * 모든 테스트가 하나의 H2 DB 를 보므로 (sp-docs/tech-stack.md §5.3) 2026-11-16 에 테스트 소스
 * 전체에서 그 값을 쓰는 곳이 없는 것을 확인하고 골랐다. 이 클래스는 롤백에 기대지 않고 —
 * 서비스가 스스로 커밋한다 — {@code @AfterEach} 가 자기 대역의 글과 그 글의 좋아요만 지운다.
 * {@code post_like.post_id} 에 FK 가 있으므로 ({@code fk_post_like_post_id}) 좋아요를 먼저 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(classes = LikeServiceTest.StatementRecorderConfig.class)
class LikeServiceTest {

	/** 동시 요청 수. 둘 이상이어야 같은 글의 요청들이 {@code post} 행 락에서 줄을 선다. */
	private static final int 동시_요청 = 4;

	/** 집결지·끼워넣은 쓰기를 기다리는 한도. 넘으면 강제가 일어나지 않은 것이므로 터진다. */
	private static final int 대기_한도_초 = 10;

	@Autowired
	private LikeService likeService;

	@Autowired
	private PostRepository postRepository;

	/** 대역으로 바꾸는 것은 클래스 주석에 있다. 바꾸지 않은 메서드는 실제 빈에 위임된다. */
	@MockitoSpyBean
	private PostLikeRepository postLikeRepository;

	/**
	 * <b>경계를 관측하는 지점이다.</b> 서비스의 템플릿과 리포지터리의 {@code @Transactional} 이 이
	 * 빈으로 트랜잭션을 열고 닫으므로 그 커밋이 여기를 지난다.
	 */
	@MockitoSpyBean
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StatementRecorder 발행된_문장;

	@AfterEach
	void 픽스처_정리() {
		this.발행된_문장.비운다();
		// post_like.post_id 에 FK 가 있으므로 (fk_post_like_post_id) 좋아요를 먼저 지운다
		this.jdbcTemplate.update("DELETE FROM post_like WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 1101 AND 1149)");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 1101 AND 1149");
	}

	// --- 멱등 (sp-docs/api-contract.md §4.6, sp-docs/domain-model.md §6.1) ---

	/**
	 * 검증 표 <b>「다시 누르기 → 200 {@code liked: true}, {@code likeCount} 1 — 증가가 남지
	 * 않는다」</b> 중 저장·집계 쪽이다. 200 은 {@code LikeControllerTest} 가 본다.
	 *
	 * <p><b>증가를 실제로 냈다가 되돌린 것인지 본다.</b> 증가 문장이 나가지 않았어도 집계는 1 이므로,
	 * {@code update post} 가 발행됐다는 것까지 봐야 「남지 않는다」 가 되돌림의 결과가 된다.
	 *
	 * <p><b>되돌림의 근거가 락 뒤의 확인이다</b> (§6.1 의 2)) — 행이 이미 있으면 INSERT 를 내지 않는다.
	 * 확인을 빼고 UNIQUE 충돌에만 맡겨도 응답과 집계는 같지만, 그러면 다시 누를 때마다 실패하는 INSERT
	 * 가 나간다.
	 */
	@Test
	@DisplayName("이미 누른 글에 다시 누르면 liked:true 이고 증가가 남지 않으며 INSERT 를 내지 않는다")
	void 다시_누르면_증가가_남지_않는다() {
		Post post = 게시글을_만든다(1101L);
		누른_상태로_둔다(post, 1102L);
		this.발행된_문장.비운다();

		LikeResponse response = this.likeService.like(post.getId(), 1102L);

		assertThat(response).isEqualTo(new LikeResponse(true, 1));
		assertThat(커밋된_집계(post)).as("그 요청의 증가가 남지 않는다").isEqualTo(1);
		assertThat(좋아요_행_수(post, 1102L)).isEqualTo(1);
		assertThat(this.발행된_문장.발행된_쓰기())
				.as("증가는 나갔다가 되돌아갔고 post_like 쓰기는 없다 — 수집기가 꽂히지 않았으면 빈 목록이다")
				.extracting(StatementRecorder::대상_테이블)
				.containsExactly("post");
	}

	/**
	 * 검증 표 <b>「누르지 않은 글 취소 → 200 {@code liked: false}, 집계 불변」</b> 중 저장·집계
	 * 쪽이다.
	 *
	 * <p><b>다른 계정의 좋아요를 둔다.</b> 집계가 0 이면 감소가 0 행이라 되돌릴 증감이 없다 — 삭제의
	 * 영향 행 수를 보지 않는 구현도 「집계 불변」 을 지난다. 1 이면 감소가 실제로 나가고, 삭제의 0 행을
	 * 보고 되돌려야 불변이다.
	 */
	@Test
	@DisplayName("누르지 않은 글을 취소하면 liked:false 이고 다른 계정의 좋아요와 집계가 그대로다")
	void 누르지_않은_글을_취소하면_집계가_그대로다() {
		Post post = 게시글을_만든다(1103L);
		누른_상태로_둔다(post, 1104L);
		this.발행된_문장.비운다();

		LikeResponse response = this.likeService.unlike(post.getId(), 1103L);

		assertThat(response).isEqualTo(new LikeResponse(false, 1));
		assertThat(커밋된_집계(post)).as("그 요청의 감소가 남지 않는다").isEqualTo(1);
		assertThat(좋아요_행_수(post, 1104L)).as("다른 계정의 좋아요는 그대로다").isEqualTo(1);
		assertThat(this.발행된_문장.발행된_UPDATE())
				.as("감소가 실제로 나가야 그것이 되돌아가는지 볼 수 있다")
				.hasSize(1);
	}

	// --- 축 — 글과 계정을 둘 다 가린다 ---

	/**
	 * 검증 표 「누르기 → {@code likeCount} 1, 행 1」 을 대각선이 아닌 조합으로 본다. 대상 글에는
	 * <b>다른 계정의</b> 좋아요가 있고, <b>다른 글에는 이 계정의</b> 좋아요가 있다. 확인이 글·계정 중
	 * 하나만 보면 이 계정이 이미 누른 것으로 판정해 증가를 되돌리고, 증가가 엉뚱한 글을 노리면 다른 글의
	 * 집계가 움직인다.
	 */
	@Test
	@DisplayName("누르기는 그 글에 그 계정의 행 하나와 집계 1 을 더하고 다른 글·다른 계정의 것은 그대로 둔다")
	void 누르기는_그_글_그_계정의_것만_더한다() {
		Post 대상 = 게시글을_만든다(1105L);
		Post 다른_글 = 게시글을_만든다(1105L);
		누른_상태로_둔다(대상, 1107L);
		누른_상태로_둔다(다른_글, 1106L);

		LikeResponse response = this.likeService.like(대상.getId(), 1106L);

		assertThat(response).isEqualTo(new LikeResponse(true, 2));
		assertThat(커밋된_집계(대상)).isEqualTo(2);
		assertThat(좋아요_행_수(대상, 1106L)).as("이 계정의 행이 생겼다").isEqualTo(1);
		assertThat(좋아요_행_수(대상, 1107L)).as("다른 계정의 행은 그대로다").isEqualTo(1);
		assertThat(커밋된_집계(다른_글)).as("다른 글의 집계는 그대로다").isEqualTo(1);
		assertThat(좋아요_행_수(다른_글, 1106L)).isEqualTo(1);
	}

	/**
	 * 검증 표 「누르고 취소 → {@code likeCount} 0, 행 0」 을 대각선이 아닌 조합으로 본다. 대상 글에는
	 * 이 계정과 <b>다른 계정의</b> 좋아요가 있고, <b>다른 글에도 이 계정의</b> 좋아요가 있다. 삭제가
	 * 글·계정 중 하나만 보면 남아야 할 행까지 지운다.
	 *
	 * <p>어긋난 상태가 아니므로 WARN 이 없어야 한다 — 감소가 1 행인 취소다.
	 */
	@Test
	@DisplayName("취소는 그 글에서 그 계정의 행만 지우고 집계를 1 줄이며 WARN 을 남기지 않는다")
	void 취소는_그_글_그_계정의_것만_지운다() {
		Post 대상 = 게시글을_만든다(1108L);
		Post 다른_글 = 게시글을_만든다(1108L);
		누른_상태로_둔다(대상, 1109L, 1110L);
		누른_상태로_둔다(다른_글, 1109L);
		AtomicReference<LikeResponse> response = new AtomicReference<>();

		List<Level> 로그 = 서비스_로그_수준(() -> response.set(this.likeService.unlike(대상.getId(), 1109L)));

		assertThat(response.get()).isEqualTo(new LikeResponse(false, 1));
		assertThat(커밋된_집계(대상)).isEqualTo(1);
		assertThat(좋아요_행_수(대상, 1109L)).as("이 계정의 행이 지워졌다").isZero();
		assertThat(좋아요_행_수(대상, 1110L)).as("다른 계정의 행은 그대로다").isEqualTo(1);
		assertThat(커밋된_집계(다른_글)).as("다른 글의 집계는 그대로다").isEqualTo(1);
		assertThat(좋아요_행_수(다른_글, 1109L)).isEqualTo(1);
		assertThat(로그).as("근거 행과 어긋난 상태가 아니다").isEmpty();
	}

	// --- 어긋난 상태 (sp-docs/domain-model.md §6.1 의 DELETE 1)) ---

	/**
	 * 검증 표 <b>「행은 있는데 집계가 0 인 글을 취소 → 200 {@code liked: false}, 집계 0, WARN」</b>.
	 *
	 * <p>감소가 0 행이어도 글이 있으면 삭제를 그대로 하고(§6.1), 삭제가 1 행이므로 커밋한다 — 행이
	 * 지워져 근거 행과 집계가 다시 같아진다. 0 아래로 내려가지 않는 것은 감소 문장의 {@code where}
	 * 절이다.
	 */
	@Test
	@DisplayName("행은 있는데 집계가 0 인 글을 취소하면 집계를 0 에 두고 행을 지우며 WARN 을 하나 남긴다")
	void 어긋난_상태의_취소는_WARN_을_남긴다() {
		Post post = 게시글을_만든다(1111L);
		좋아요_행만_둔다(post, 1112L);
		AtomicReference<LikeResponse> response = new AtomicReference<>();

		List<Level> 로그 = 서비스_로그_수준(() -> response.set(this.likeService.unlike(post.getId(), 1112L)));

		assertThat(response.get()).isEqualTo(new LikeResponse(false, 0));
		assertThat(커밋된_집계(post)).as("집계를 0 에 둔다 — 음수가 아니다").isZero();
		assertThat(좋아요_행_수(post, 1112L)).as("행을 지우고 커밋했다").isZero();
		assertThat(로그).as("어긋난 상태를 WARN 으로 드러낸다 (sp-docs/domain-model.md §6.1)")
				.containsExactly(Level.WARN);
	}

	/**
	 * {@link #어긋난_상태의_취소는_WARN_을_남긴다()} 의 반대 조합 — 감소가 0 행이고 <b>삭제도 0 행</b>이다.
	 * 누르지 않은 글의 집계가 0 인 것이고 어긋난 상태가 아니다. 감소의 0 행만 보고 WARN 을 남기면 여기서
	 * 드러난다.
	 */
	@Test
	@DisplayName("집계가 0 이고 행도 없는 글을 취소하면 liked:false 이고 WARN 을 남기지 않는다")
	void 집계도_행도_없으면_어긋난_상태가_아니다() {
		Post post = 게시글을_만든다(1113L);
		AtomicReference<LikeResponse> response = new AtomicReference<>();

		List<Level> 로그 = 서비스_로그_수준(() -> response.set(this.likeService.unlike(post.getId(), 1113L)));

		assertThat(response.get()).isEqualTo(new LikeResponse(false, 0));
		assertThat(커밋된_집계(post)).isZero();
		assertThat(로그).isEmpty();
	}

	// --- UNIQUE 충돌 (sp-docs/domain-model.md §6.1 의 PUT 3)) ---

	/**
	 * 검증 표 <b>「UNIQUE 충돌 경로 — 확인을 지난 뒤 INSERT 가 충돌하게 만든다 → 200
	 * {@code liked: true}, 커밋된 {@code like_count} 불변, 500 아님」</b>.
	 *
	 * <p>수단은 계획이 예로 든 것이다 — 확인을 대역으로 바꿔 행이 없다고 답하게 하고, 행은 실제로
	 * 둔다. 그러면 INSERT 가 {@code uk_post_like_post_account} 에 실제로 걸린다. 증가와 INSERT 가
	 * 실제로 나갔는지까지 본다 — 둘 중 하나가 나가지 않으면 「불변」 이 되돌림의 결과가 아니다.
	 *
	 * <p>여기에는 요청이 연 영속성 컨텍스트(OSIV)가 없다 — 쓰는 트랜잭션의 컨텍스트가 그 트랜잭션과
	 * 함께 닫힌다. OSIV 안에서 같은 경로를 지나는 것은 {@code LikeControllerTest} 가 본다.
	 */
	@Test
	@DisplayName("확인을 지난 INSERT 가 UNIQUE 에 걸리면 liked:true 이고 그 요청의 증가가 남지 않는다")
	void UNIQUE_충돌이면_liked_true_이고_증가가_남지_않는다() {
		Post post = 게시글을_만든다(1114L);
		누른_상태로_둔다(post, 1115L);
		willReturn(false).given(this.postLikeRepository)
				.existsByPostIdAndAccountId(post.getId(), 1115L);
		this.발행된_문장.비운다();

		LikeResponse response = this.likeService.like(post.getId(), 1115L);

		assertThat(response).isEqualTo(new LikeResponse(true, 1));
		assertThat(커밋된_집계(post)).as("그 요청의 증가가 남지 않는다").isEqualTo(1);
		assertThat(좋아요_행_수(post, 1115L)).isEqualTo(1);
		assertThat(this.발행된_문장.발행된_쓰기())
				.as("증가와 INSERT 가 실제로 나갔다 — 수집기가 꽂히지 않았으면 빈 목록이다")
				.extracting(StatementRecorder::대상_테이블)
				.containsExactly("post", "post_like");
	}

	/**
	 * UNIQUE 가 아닌 무결성 위반은 「2)와 같은 결과」 가 아니므로 삼키지 않는다 — 삼키면 실패가
	 * {@code liked: true} 로 위장된다 (sp-docs/AGENTS.md 불변식 9). 증가도 함께 되돌아간다.
	 *
	 * <p>이 경로에는 INSERT 를 UNIQUE 가 아닌 제약에 실제로 걸 길이 없다 — 1) 의 락이 글 행을 쥐고
	 * 있고 글은 논리 삭제라 행이 남는다. 그래서 저장을 대역으로 바꿔 Hibernate 가 UNIQUE 가 아닌
	 * 종류로 분류한 위반을 던진다. 대역은 리포지터리의 트랜잭션 어드바이스를 지나지 않으므로 이 예외는
	 * 트랜잭션을 rollback-only 로 만들지 않는다 — 되돌림은 서비스의 템플릿이 예외를 보고 한다.
	 *
	 * <p>{@code save} 도 같은 위반을 던지게 한다. 이 테스트가 보는 것은 위반의 처리이고 INSERT 를 내는
	 * 수단이 아니다 — {@code saveAndFlush} 만 대역이면 수단을 {@code save} 로 바꾼 구현에서 대역이
	 * 비껴가 처리와 무관하게 빨개진다(B-20 실측).
	 */
	@Test
	@DisplayName("UNIQUE 가 아닌 무결성 위반은 그대로 던지고 증가가 남지 않는다")
	void UNIQUE_가_아닌_무결성_위반은_삼키지_않는다() {
		Post post = 게시글을_만든다(1116L);
		DataIntegrityViolationException 외래_키_위반 = new DataIntegrityViolationException("외래 키 위반",
				new ConstraintViolationException("외래 키 위반", new SQLException("외래 키 위반", "23506"),
						"insert into post_like", ConstraintKind.OTHER, "fk_post_like_post_id"));
		willThrow(외래_키_위반).given(this.postLikeRepository).saveAndFlush(any(PostLike.class));
		willThrow(외래_키_위반).given(this.postLikeRepository).save(any(PostLike.class));

		assertThatThrownBy(() -> this.likeService.like(post.getId(), 1117L)).isSameAs(외래_키_위반);

		assertThat(커밋된_집계(post)).as("증가도 함께 되돌아간다").isZero();
		assertThat(좋아요_행_수(post)).isZero();
	}

	// --- 순서와 경계 (sp-docs/domain-model.md §6 §6.1) ---

	/**
	 * 검증 표 <b>「발행 순서 → {@code post} 행의 배타 락을 잡는 문장(좁은 UPDATE, 또는 같은 절이 허용한
	 * 잠금 읽기)이 {@code post_like} 쓰기보다 먼저다」</b>의 누르기 쪽이다.
	 *
	 * <p><b>그 문장이 첫 문장인지도 본다.</b> 락 앞의 일반 읽기는 반복 읽기 격리에서 그때의 스냅샷을
	 * 만들어 락 뒤의 확인이 앞서 커밋된 좋아요를 놓치게 할 수 있다 (§6.1). 이 테스트는 그 결과를 재지
	 * 않고, 읽기가 락보다 앞서지 않는다는 순서로 본다.
	 */
	@Test
	@DisplayName("누르기의 첫 문장은 post 의 좁은 UPDATE 이고 post_like INSERT 가 그 뒤다")
	void 누르기는_post_락을_먼저_잡는다() {
		Post post = 게시글을_만든다(1118L);
		this.발행된_문장.비운다();

		this.likeService.like(post.getId(), 1119L);

		assertThat(this.발행된_문장.전부())
				.as("수집기가 꽂히지 않았으면 빈 목록이다")
				.isNotEmpty()
				.first().asString()
				.as("락보다 앞선 읽기가 없다 (sp-docs/domain-model.md §6.1)")
				.startsWith("update post ");
		assertThat(this.발행된_문장.발행된_쓰기())
				.extracting(StatementRecorder::대상_테이블)
				.as("post 의 배타 락이 post_like 쓰기보다 먼저다. 거꾸로면 INSERT 의 FK 공유 락과 "
						+ "UPDATE 의 배타 락 사이에서 교착할 것으로 본다 (§6.1)")
				.containsExactly("post", "post_like");
	}

	/** 검증 표 「발행 순서」 의 취소 쪽이다. 첫 문장을 보는 이유는 {@link #누르기는_post_락을_먼저_잡는다()} 와 같다. */
	@Test
	@DisplayName("취소의 첫 문장은 post 의 좁은 UPDATE 이고 post_like DELETE 가 그 뒤다")
	void 취소는_post_락을_먼저_잡는다() {
		Post post = 게시글을_만든다(1118L);
		누른_상태로_둔다(post, 1119L);
		this.발행된_문장.비운다();

		this.likeService.unlike(post.getId(), 1119L);

		assertThat(this.발행된_문장.전부())
				.as("수집기가 꽂히지 않았으면 빈 목록이다")
				.isNotEmpty()
				.first().asString()
				.as("락보다 앞선 읽기가 없다 (sp-docs/domain-model.md §6.1)")
				.startsWith("update post ");
		assertThat(this.발행된_문장.발행된_쓰기())
				.extracting(StatementRecorder::대상_테이블)
				.containsExactly("post", "post_like");
	}

	/**
	 * 「집계와 근거 행의 쓰기는 한 로컬 트랜잭션이다」(sp-docs/domain-model.md §6 의 표)의 경계 관측이다
	 * (sp-docs/process/review-policy.md §4.1). 행위 단언으로는 한 트랜잭션과 연산마다 커밋되는
	 * 트랜잭션이 정상 경로에서 구별되지 않는다.
	 *
	 * <p><b>{@code isNewTransaction()} 이 참인 커밋만 센다.</b> 리포지터리 호출은 템플릿의 트랜잭션에
	 * 참여하므로 매니저의 {@code commit} 을 지나도 물리 커밋이 아니다. 단언은 「정확히 한 번」 에만
	 * 건다.
	 */
	@Test
	@DisplayName("한 번의 누르기와 한 번의 취소에 물리 트랜잭션이 각각 정확히 하나 커밋된다")
	void 누르기와_취소의_커밋이_각각_정확히_한_번이다() {
		Post post = 게시글을_만든다(1120L);
		List<TransactionStatus> 누르기_커밋 = 물리_커밋을_센다();

		this.likeService.like(post.getId(), 1121L);

		assertThat(좋아요_행_수(post, 1121L)).as("누르기가 일어나지 않았다면 커밋 수는 아무것도 말하지 않는다")
				.isEqualTo(1);
		assertThat(누르기_커밋).as("post 증가와 post_like INSERT 가 한 로컬 트랜잭션이다").hasSize(1);

		List<TransactionStatus> 취소_커밋 = 물리_커밋을_센다();

		this.likeService.unlike(post.getId(), 1121L);

		assertThat(좋아요_행_수(post, 1121L)).as("취소가 일어나지 않았다면 커밋 수는 아무것도 말하지 않는다")
				.isZero();
		assertThat(취소_커밋).as("post 감소와 post_like DELETE 가 한 로컬 트랜잭션이다").hasSize(1);
	}

	/**
	 * <b>없는·삭제된 글의 판정이 락을 잡는 문장에 있다</b> (sp-docs/domain-model.md §6.1 의 1)). 이
	 * 요청의 첫 문장 직전에 다른 트랜잭션이 글을 지우고 커밋한다 — 판정을 락 전의 읽기로 하면 그 읽기는
	 * 지워지기 전의 글을 보고 지워진 글에 좋아요가 남는다.
	 *
	 * <p>404 {@code P001} 의 HTTP 응답은 {@code LikeControllerTest} 가 본다.
	 */
	@Test
	@DisplayName("누르기의 락 직전에 글이 지워지면 404 P001 이고 행도 증가도 남지 않는다")
	void 락_직전에_지워진_글에_누르면_404_다() {
		Post post = 게시글을_만든다(1122L);
		AtomicInteger 끼워넣은_삭제 = new AtomicInteger(-1);
		this.발행된_문장.훅을_건다(sql -> sql.startsWith("update post "),
				() -> 끼워넣은_삭제.set(다른_트랜잭션에서_글을_지운다(post.getId())));

		assertThatThrownBy(() -> this.likeService.like(post.getId(), 1123L))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POST_NOT_FOUND);

		assertThat(끼워넣은_삭제.get()).as("글이 먼저 지워지고 커밋돼야 이 측정이 일한다").isEqualTo(1);
		assertThat(커밋된_집계(post)).isZero();
		assertThat(좋아요_행_수(post)).isZero();
	}

	/**
	 * {@link #락_직전에_지워진_글에_누르면_404_다()} 의 취소 쪽이다. 감소가 0 행이 되고, 글이 있는지
	 * 다시 읽는 자리에서 지워진 글을 본다. 지워진 글의 좋아요 행은 남는다
	 * (sp-docs/requirements/board.md §10).
	 */
	@Test
	@DisplayName("취소의 락 직전에 글이 지워지면 404 P001 이고 행과 집계가 그대로다")
	void 락_직전에_지워진_글을_취소하면_404_다() {
		Post post = 게시글을_만든다(1122L);
		누른_상태로_둔다(post, 1123L);
		AtomicInteger 끼워넣은_삭제 = new AtomicInteger(-1);
		this.발행된_문장.훅을_건다(sql -> sql.startsWith("update post "),
				() -> 끼워넣은_삭제.set(다른_트랜잭션에서_글을_지운다(post.getId())));

		assertThatThrownBy(() -> this.likeService.unlike(post.getId(), 1123L))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POST_NOT_FOUND);

		assertThat(끼워넣은_삭제.get()).as("글이 먼저 지워지고 커밋돼야 이 측정이 일한다").isEqualTo(1);
		assertThat(좋아요_행_수(post, 1123L)).as("지워진 글의 좋아요 행은 남는다").isEqualTo(1);
		assertThat(커밋된_집계(post)).isEqualTo(1);
	}

	/**
	 * 검증 표의 동시 요청 줄들. 요청마다 첫 {@code update post} 가 발행되기 직전에 스레드를 세워,
	 * {@code 동시_요청} 개가 모두 모인 뒤에야 첫 쓰기가 실행되게 한다 — 모인 시점에 어느 요청도 락을
	 * 쥐지 않았고 그 뒤로는 H2 의 행 락이 그들을 한 줄로 세운다. 「응답 전부 200」 은 여기서 예외가
	 * 없다는 것이다.
	 *
	 * <p>여기서 보는 것은 H2 의 행 락이 세운 줄의 결과다. 실 MySQL 의 측정은
	 * sp-docs/plan/phase2-board.md §4.1 이 통합으로 넘겼다.
	 *
	 * <p><b>감소가 0 행인 취소는 이 줄에 서지 않는다.</b> H2 는 그 감소에 락을 쥐지 않는다 (B-20
	 * 실측, {@link LikeService#unlike} 의 주석). 이 클래스의 취소는 집계가 0 보다 큰 글에서 시작하고,
	 * 집계가 0 인 글에 같은 계정의 누르기와 취소가 겹치는 경우는 다루지 않는다.
	 */
	@Nested
	@DisplayName("같은 글의 동시 요청")
	class 같은_글의_동시_요청 {

		/** 검증 표 <b>「같은 계정의 동시 누르기 N건 → {@code likeCount} 1, 행 1, 응답 전부 200 {@code liked: true}」</b>. */
		@Test
		@DisplayName("같은 계정의 동시 누르기는 행 하나와 집계 1 만 남기고 모두 liked:true 다")
		void 같은_계정의_동시_누르기는_한_번만_센다() throws InterruptedException {
			Post post = 게시글을_만든다(1124L);
			모든_스레드를_첫_쓰기_직전에_모은다();

			결과 결과 = 동시에_실행한다(i -> likeService.like(post.getId(), 1125L));

			assertThat(결과.실패()).isEmpty();
			assertThat(결과.응답())
					.as("이미 누른 뒤의 요청도 200 이고 그 요청 뒤의 상태를 돌려준다 (sp-docs/api-contract.md §4.6)")
					.hasSize(동시_요청)
					.containsOnly(new LikeResponse(true, 1));
			assertThat(커밋된_집계(post)).as("증가가 한 번만 남는다").isEqualTo(1);
			assertThat(좋아요_행_수(post, 1125L)).isEqualTo(1);
		}

		/**
		 * 검증 표 <b>「누른 뒤 같은 계정의 동시 취소 N건 → {@code likeCount} 는 한 번만 준다, 행 0,
		 * 응답 전부 200 {@code liked: false}」</b>.
		 *
		 * <p>다른 계정의 좋아요를 {@code 동시_요청} 개 더 두어 시작 집계를 {@code 동시_요청 + 1} 로
		 * 만든다. 시작 집계가 동시 요청 수 이상이어야 요청마다의 감소가 {@code like_count > 0} 가드에
		 * 막히지 않고 모두 집계에 드러난다.
		 */
		@Test
		@DisplayName("누른 뒤 같은 계정의 동시 취소는 집계를 한 번만 줄이고 모두 liked:false 다")
		void 같은_계정의_동시_취소는_한_번만_줄인다() throws InterruptedException {
			Post post = 게시글을_만든다(1126L);
			누른_상태로_둔다(post, 1127L);
			for (int i = 0; i < 동시_요청; i++) {
				누른_상태로_둔다(post, 1128L + i);
			}
			int 시작_집계 = 커밋된_집계(post);
			assertThat(시작_집계).as("요청마다의 감소가 0 의 가드에 막히지 않아야 한다").isEqualTo(동시_요청 + 1);
			모든_스레드를_첫_쓰기_직전에_모은다();

			결과 결과 = 동시에_실행한다(i -> likeService.unlike(post.getId(), 1127L));

			assertThat(결과.실패()).isEmpty();
			assertThat(결과.응답()).hasSize(동시_요청).containsOnly(new LikeResponse(false, 시작_집계 - 1));
			assertThat(커밋된_집계(post)).as("감소가 한 번만 남는다").isEqualTo(시작_집계 - 1);
			assertThat(좋아요_행_수(post, 1127L)).isZero();
			assertThat(좋아요_행_수(post)).as("집계가 근거 행의 수와 같다 (sp-docs/domain-model.md §6)")
					.isEqualTo(커밋된_집계(post));
		}

		/** 검증 표 <b>「서로 다른 계정의 동시 누르기 N건 → {@code likeCount} = 행 수 = N」</b>. */
		@Test
		@DisplayName("서로 다른 계정의 동시 누르기는 집계와 행 수가 모두 요청 수다")
		void 서로_다른_계정의_동시_누르기는_모두_센다() throws InterruptedException {
			Post post = 게시글을_만든다(1132L);
			모든_스레드를_첫_쓰기_직전에_모은다();

			결과 결과 = 동시에_실행한다(i -> likeService.like(post.getId(), 1133L + i));

			assertThat(결과.실패()).isEmpty();
			assertThat(결과.응답()).hasSize(동시_요청)
					.allSatisfy(response -> assertThat(response.liked()).isTrue());
			assertThat(커밋된_집계(post)).as("증가가 하나도 소실되지 않는다").isEqualTo(동시_요청);
			assertThat(좋아요_행_수(post)).isEqualTo(동시_요청);
		}

		/**
		 * 같은 글에 누르기와 취소가 함께 몰려도 집계가 근거 행의 수와 같다 — sp-docs/domain-model.md §6 의
		 * 「{@code post} 의 집계 컬럼은 근거 행과 같아야 한다」 다. 스레드 번호 {@code i} 의 계정이
		 * {@code 1139 + i} 이고, 앞 절반은 이미 누른 계정이라 취소하고 뒤 절반은 새 계정이라 누른다. 실
		 * MySQL 에서 같은 것을 재는 일은 sp-docs/plan/phase2-board.md §4.1 이 통합으로 넘겼다.
		 */
		@Test
		@DisplayName("같은 글에 서로 다른 계정의 누르기와 취소가 동시에 와도 집계가 행 수와 같다")
		void 누르기와_취소가_섞여도_집계가_행_수와_같다() throws InterruptedException {
			Post post = 게시글을_만든다(1138L);
			int 절반 = 동시_요청 / 2;
			for (int i = 0; i < 절반; i++) {
				누른_상태로_둔다(post, 1139L + i);
			}
			모든_스레드를_첫_쓰기_직전에_모은다();

			결과 결과 = 동시에_실행한다(i -> i < 절반
					? likeService.unlike(post.getId(), 1139L + i)
					: likeService.like(post.getId(), 1139L + i));

			assertThat(결과.실패()).isEmpty();
			assertThat(결과.응답().subList(0, 절반)).as("취소한 요청")
					.extracting(LikeResponse::liked).containsOnly(false);
			assertThat(결과.응답().subList(절반, 동시_요청)).as("누른 요청")
					.extracting(LikeResponse::liked).containsOnly(true);
			assertThat(커밋된_집계(post)).as("집계가 근거 행의 수와 같다").isEqualTo(좋아요_행_수(post));
			for (int i = 0; i < 동시_요청; i++) {
				assertThat(좋아요_행_수(post, 1139L + i))
						.as("계정 %d 의 행 — 취소했으면 0, 눌렀으면 1", 1139L + i)
						.isEqualTo(i < 절반 ? 0 : 1);
			}
		}

	}

	// --- 도구 ---

	/**
	 * <b>끼워넣기를 강제한다.</b> 요청마다 첫 {@code update post} 가 발행되기 직전에 스레드를 세워,
	 * {@code 동시_요청} 개가 모두 모인 뒤에야 첫 쓰기가 실행되게 한다. 한도 안에 모이지 않으면
	 * {@link IllegalStateException} 으로 터진다.
	 *
	 * <p><b>발행 직전에 세운다.</b> 커밋 시점에 세우면 먼저 락을 쥔 스레드가 집결지를 기다리는 동안
	 * 나머지가 그 락을 기다려 교착한다.
	 */
	private void 모든_스레드를_첫_쓰기_직전에_모은다() {
		CyclicBarrier 집결지 = new CyclicBarrier(동시_요청);
		this.발행된_문장.훅을_건다(sql -> sql.startsWith("update post "), () -> {
			try {
				집결지.await(대기_한도_초, TimeUnit.SECONDS);
			}
			catch (InterruptedException | BrokenBarrierException | TimeoutException ex) {
				throw new IllegalStateException(
						"첫 쓰기 직전에 모든 스레드가 모이지 않았다 — 요청들이 락에서 줄을 서는 끼워넣기가 "
								+ "강제되지 않았으므로 이 측정은 동시 요청을 보지 못한다", ex);
			}
		});
	}

	private record 결과(List<LikeResponse> 응답, List<Throwable> 실패) {
	}

	/** 스레드 번호를 받는 작업을 {@code 동시_요청} 개 스레드로 동시에 돌리고 응답과 실패를 모은다. */
	private 결과 동시에_실행한다(IntFunction<LikeResponse> 작업) throws InterruptedException {
		ExecutorService 풀 = Executors.newFixedThreadPool(동시_요청);
		try {
			List<Future<LikeResponse>> futures = new ArrayList<>();
			for (int i = 0; i < 동시_요청; i++) {
				int index = i;
				futures.add(풀.submit(() -> 작업.apply(index)));
			}

			List<LikeResponse> 응답 = new ArrayList<>();
			List<Throwable> 실패 = new ArrayList<>();
			for (Future<LikeResponse> future : futures) {
				try {
					응답.add(future.get(대기_한도_초 * 3L, TimeUnit.SECONDS));
				}
				catch (ExecutionException ex) {
					실패.add(ex.getCause());
				}
				catch (TimeoutException ex) {
					실패.add(ex);
				}
			}
			return new 결과(응답, 실패);
		}
		finally {
			풀.shutdownNow();
		}
	}

	/**
	 * 다른 커넥션의 자동 커밋으로 글을 지우고 영향 행 수를 돌려준다 — 논리 삭제가 바꾸는 컬럼만 바꾼다.
	 * <b>다른 스레드에서 돌린다</b> — 부르는 스레드에는 서비스의 트랜잭션이 묶여 있어 같은 스레드의
	 * {@code JdbcTemplate} 은 그 트랜잭션에 참여한다.
	 */
	private int 다른_트랜잭션에서_글을_지운다(Long postId) {
		ExecutorService 다른_스레드 = Executors.newSingleThreadExecutor();
		try {
			return 다른_스레드.submit(() -> this.jdbcTemplate.update(
							"UPDATE post SET deleted = TRUE WHERE id = ? AND deleted = FALSE", postId))
					.get(대기_한도_초, TimeUnit.SECONDS);
		}
		catch (InterruptedException | ExecutionException | TimeoutException ex) {
			throw new IllegalStateException("끼워넣은 삭제가 커밋되지 않았다 — 이 측정은 락 직전의 삭제를 볼 수 없다", ex);
		}
		finally {
			다른_스레드.shutdownNow();
		}
	}

	/** 이 뒤로 커밋되는 물리 트랜잭션을 모은다. 참여 트랜잭션의 커밋은 물리 커밋이 아니므로 뺀다. */
	private List<TransactionStatus> 물리_커밋을_센다() {
		List<TransactionStatus> 커밋 = new CopyOnWriteArrayList<>();
		willAnswer(invocation -> {
			TransactionStatus status = invocation.getArgument(0);
			if (status.isNewTransaction()) {
				커밋.add(status);
			}
			return invocation.callRealMethod();
		}).given(this.transactionManager).commit(any(TransactionStatus.class));
		return 커밋;
	}

	/** 작업을 돌리는 동안 {@link LikeService} 가 남긴 로그 수준을 모은다. */
	private static List<Level> 서비스_로그_수준(Runnable 작업) {
		Logger logger = (Logger) LoggerFactory.getLogger(LikeService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level original = logger.getLevel();
		// 수집 결과가 주변 설정에 좌우되지 않게 수준을 이 안에서 정한다
		logger.setLevel(Level.TRACE);
		logger.addAppender(appender);
		try {
			작업.run();
			return appender.list.stream().map(ILoggingEvent::getLevel).toList();
		}
		finally {
			logger.detachAppender(appender);
			logger.setLevel(original);
			appender.stop();
		}
	}

	private Post 게시글을_만든다(long writerId) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname("글작성자")
				.title("좋아요 대상")
				.content("본문")
				.build());
	}

	/**
	 * 계정마다 근거 행 하나를 두고 집계를 그 수만큼 올린다 — 근거 행과 집계가 같은 상태다. 집계는
	 * {@code JdbcTemplate} 으로 올리고, 영향 행이 1 이 아니면 그 자리에서 터진다.
	 */
	private void 누른_상태로_둔다(Post post, long... accountIds) {
		for (long accountId : accountIds) {
			좋아요_행만_둔다(post, accountId);
		}
		int updated = this.jdbcTemplate.update(
				"UPDATE post SET like_count = like_count + ? WHERE id = ?",
				accountIds.length, post.getId());
		if (updated != 1) {
			throw new IllegalStateException("like_count 픽스처가 1 행이 아니다: " + updated);
		}
	}

	/** 집계를 건드리지 않고 근거 행만 둔다. 어긋난 상태(행은 있는데 집계가 0)의 픽스처다. */
	private void 좋아요_행만_둔다(Post post, long accountId) {
		this.postLikeRepository.saveAndFlush(
				PostLike.builder().post(post).accountId(accountId).build());
	}

	/** JPA 를 거치지 않고 커밋된 값을 읽는다. 다른 스레드가 커밋한 것을 봐야 한다. */
	private int 커밋된_집계(Post post) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT like_count FROM post WHERE id = ?", Integer.class, post.getId());
		return count == null ? 0 : count;
	}

	private int 좋아요_행_수(Post post) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM post_like WHERE post_id = ?", Integer.class, post.getId());
		return count == null ? 0 : count;
	}

	private int 좋아요_행_수(Post post, long accountId) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM post_like WHERE post_id = ? AND account_id = ?",
				Integer.class, post.getId(), accountId);
		return count == null ? 0 : count;
	}

	/**
	 * 발행된 SQL 을 모으고, 지정한 문장이 <b>발행되기 직전에</b> 훅을 돌린다. Hibernate 의
	 * {@code StatementInspector} 라 문장이 JDBC 로 넘어가기 전에 불린다.
	 *
	 * <p><b>로그·통계 설정에 기대지 않는다.</b> 그 설정이 꺼지면 조용히 빈 결과를 돌려주는 수단이
	 * 된다. 이 수집기는 테스트가 직접 꽂고, 꽂히지 않으면 단언이 빈 목록에서 터진다
	 * (sp-docs/process/review-policy.md §4.1 「실패할 수 없는 단언을 쓰지 않는다」). 훅과 수집은
	 * {@code CommentServiceTest} 의 같은 이름의 수집기와 같고(조회 메서드는 다르다), 그것이 그 패키지
	 * 안에 있어 여기 둔다.
	 */
	static class StatementRecorder implements StatementInspector {

		private final List<String> 발행된_문장 = new CopyOnWriteArrayList<>();

		private volatile Predicate<String> 훅_대상 = sql -> false;

		private volatile Runnable 훅 = () -> {
		};

		/** 스레드마다 한 번만 돈다. 한 스레드가 두 번 모이면 집결지가 다시 닫혀 열리지 않는다. */
		private final ThreadLocal<Boolean> 훅_소진 = ThreadLocal.withInitial(() -> false);

		@Override
		public String inspect(String sql) {
			this.발행된_문장.add(sql);
			if (!this.훅_소진.get() && this.훅_대상.test(정규화(sql))) {
				this.훅_소진.set(true);
				this.훅.run();
			}
			return sql;
		}

		void 훅을_건다(Predicate<String> 대상, Runnable 훅) {
			this.훅_대상 = 대상;
			this.훅 = 훅;
		}

		void 비운다() {
			this.발행된_문장.clear();
			this.훅_대상 = sql -> false;
			this.훅 = () -> {
			};
			this.훅_소진.remove();
		}

		/** 수집된 순서 그대로의 모든 문장. 소문자·공백이 정규화된 문장이다. */
		List<String> 전부() {
			return this.발행된_문장.stream().map(StatementRecorder::정규화).toList();
		}

		/** 모든 UPDATE. */
		List<String> 발행된_UPDATE() {
			return 전부().stream().filter(sql -> sql.startsWith("update ")).toList();
		}

		/** 쓰기 문장(UPDATE·INSERT·DELETE)만, 수집된 순서 그대로. */
		List<String> 발행된_쓰기() {
			return 전부().stream()
					.filter(sql -> sql.startsWith("update ") || sql.startsWith("insert ")
							|| sql.startsWith("delete "))
					.toList();
		}

		/**
		 * 정규화된 쓰기 문장의 테이블 이름 — {@code update <t>}, {@code insert into <t>},
		 * {@code delete from <t>}.
		 */
		static String 대상_테이블(String 정규화된_쓰기) {
			String[] 토큰 = 정규화된_쓰기.split(" ");
			return "update".equals(토큰[0]) ? 토큰[1] : 토큰[2];
		}

		private static String 정규화(String sql) {
			return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
		}

	}

	/**
	 * {@link StatementRecorder} 를 Hibernate 에 꽂는다. {@code @TestConfiguration} 이므로
	 * {@code @SpringBootConfiguration} 탐색을 막지 않고 덧붙는다.
	 */
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
