package com.example.board.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.board.client.MemberClient;
import com.example.board.comment.dto.CommentCreateRequest;
import com.example.board.comment.dto.CommentPageCondition;
import com.example.board.comment.dto.CommentResponse;
import com.example.board.comment.dto.CommentUpdateRequest;
import com.example.board.comment.dto.TopLevelCommentResponse;
import com.example.board.comment.entity.Comment;
import com.example.board.comment.repository.CommentRepository;
import com.example.board.global.common.PageResponse;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.global.security.LoginMember;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.example.board.support.PhysicalCommitRecorder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * sp-docs/plan/phase1.md §6 B-08 의 "검증" 표 중 저장·집계·목록 쪽을 테스트로 옮긴 것이다.
 * HTTP 상태·응답 키 집합·권한 판정·입력 검증·member 응답별 판정은
 * {@link com.example.board.comment.controller.CommentControllerTest} 가 본다.
 *
 * <p>정본은 sp-docs/requirements/board.md §2 §3 §3.0 §5, sp-docs/domain-model.md §4.2 §5,
 * sp-docs/api-contract.md §4.2 §4.5 §7.1 이다.
 *
 * <p><b>{@link MemberClient} 를 목으로 바꾼다.</b> member 응답과 {@code S001}·{@code S002} 의
 * 대응은 B-04 의 {@code MemberClientTest} 가 스텁 서버로 이미 덮었고, 여기서 볼 것은 그 결과를
 * 받은 <b>board 쪽</b>이다 — 조회 키가 무엇인지, 호출이 트랜잭션 밖인지. 실패를 HTTP 응답으로
 * 끝까지 끌고 가는 쪽은 {@code CommentControllerTest} 가 실제 {@code MemberClient} 와 스텁
 * 서버로 본다.
 *
 * <p>리포지터리는 스텁으로 바꾸지 않고 H2 를 그대로 쓴다. "댓글이 저장되지 않는다" 와
 * "{@code comment_count} 가 1 증가한다" 는 저장된 행을 봐야 확인된다.
 *
 * <p>픽스처의 {@code writerId} 는 801~829 를 쓰고, B-10 의 중첩 클래스
 * ({@link 집계_갱신의_모양과_동시성})가 901~929, B-17 의 중첩 클래스
 * ({@link 삭제의_순서와_조건부_전환})가 971~979, B-22 의 중첩 클래스({@link 답글})가 1311~1339 를
 * 쓴다. 같은 JVM 의 모든 테스트가 하나의
 * H2 DB 를 보므로 (sp-docs/tech-stack.md §5.3) 이미 쓰이는 대역과 겹치지 않게 둔다. 이
 * 클래스는 트랜잭션 롤백에 기대지 않고 — 작성 경로가 스스로 커밋한다 —
 * {@code @AfterEach} 가 자기 대역만 지운다. <b>테이블 전체를 지우지 않는다</b> — 다른
 * 클래스의 픽스처를 없애면 그쪽이 간헐 실패한다. {@code comment.post_id} 에 FK 가 있으므로
 * ({@code fk_comment_post_id}) 댓글을 먼저 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
class CommentServiceTest {

	/** member 의 내부 API 가 돌려주는 닉네임. 스냅샷에 저장될 유일한 출처다 (§3 규칙 2). */
	private static final String MEMBER_NICKNAME = "member가준닉네임";

	/** sp-docs/api-contract.md §6 의 {@code role} claim 값. */
	private static final String ROLE_USER = "USER";

	/** 시각을 직접 박는 픽스처가 쓰는 값. 순서는 이 값에서 상대적으로 만든다. */
	private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 3, 1, 12, 0, 0);

	@Autowired
	private CommentService commentService;

	@Autowired
	private CommentRepository commentRepository;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private MemberClient memberClient;

	@AfterEach
	void 픽스처_정리() {
		// comment.post_id 에 FK 가 있으므로 (fk_comment_post_id) 댓글을 먼저 지운다
		this.jdbcTemplate.update("DELETE FROM comment WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 801 AND 829)");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 801 AND 829");
	}

	// --- 작성 (C-01, sp-docs/requirements/board.md §2 §3) ---

	/**
	 * 검증표 "댓글 작성 → 201, {@code comment_count = 1}" 중 저장·집계 쪽이다. 201 은
	 * {@code CommentControllerTest} 가 본다.
	 *
	 * <p>완료 기준 "댓글 작성 시 {@code post.comment_count} 가 1 증가한다" 가 여기 있다.
	 * <b>증가의 수단은 B-10 이 좁은 UPDATE 로 바꿨다</b> (sp-docs/plan/phase1.md §6 B-10) —
	 * 발행된 문장의 모양은 {@link 집계_갱신의_모양과_동시성} 이 본다.
	 */
	@Test
	@DisplayName("정상 작성이면 댓글이 저장되고 comment_count 가 1 증가한다")
	void 정상_작성() {
		given(this.memberClient.findActiveNickname(801L)).willReturn(MEMBER_NICKNAME);
		Post post = savedPost(801L);

		CommentResponse created = this.commentService.create(
				post.getId(), 801L, new CommentCreateRequest("첫 댓글", null));

		Comment saved = this.commentRepository.findById(created.id()).orElseThrow();
		assertThat(saved.getContent()).isEqualTo("첫 댓글");
		assertThat(saved.getPost().getId()).isEqualTo(post.getId());
		assertThat(saved.isDeleted()).isFalse();
		assertThat(커밋된_집계(post.getId()))
				.as("댓글 작성 시 comment_count 가 1 증가한다 (sp-docs/domain-model.md §5)")
				.isEqualTo(1);
	}

	/**
	 * 완료 기준 "{@code writer_id} 는 JWT {@code sub}" (§3 규칙 1). 서비스에 그 값이 파라미터로
	 * 들어오고 요청 본문에는 자리가 없다 — 본문에 {@code writerId} 를 실었을 때 무시되는 것은
	 * {@code CommentControllerTest} 가 본다.
	 *
	 * <p><b>조회 키도 그 {@code sub} 다</b> (§3 규칙 3).
	 */
	@Test
	@DisplayName("저장된 writer_id 는 전달된 accountId(JWT sub)이고 member 조회 키도 그 값이다")
	void 저장된_writer_id_는_sub_다() {
		given(this.memberClient.findActiveNickname(802L)).willReturn(MEMBER_NICKNAME);
		Post post = savedPost(801L);

		CommentResponse created = this.commentService.create(
				post.getId(), 802L, new CommentCreateRequest("작성자 확인", null));

		assertThat(this.commentRepository.findById(created.id()).orElseThrow().getWriterId())
				.isEqualTo(802L);
		assertThat(created.writerId()).isEqualTo(802L);
		verify(this.memberClient).findActiveNickname(802L);
	}

	/**
	 * 검증표 "저장된 {@code writer_nickname} → {@code MemberClient} 응답값과 일치"
	 * (§3 규칙 2). JWT Claim 에 {@code nickname} 이 없으므로 다른 취득 경로가 없다
	 * (sp-docs/adr/0012 §3).
	 */
	@Test
	@DisplayName("저장된 writer_nickname 은 MemberClient 응답값이다")
	void 저장된_닉네임은_member_응답값이다() {
		given(this.memberClient.findActiveNickname(803L)).willReturn(MEMBER_NICKNAME);
		Post post = savedPost(801L);

		CommentResponse created = this.commentService.create(
				post.getId(), 803L, new CommentCreateRequest("닉네임 확인", null));

		assertThat(this.commentRepository.findById(created.id()).orElseThrow().getWriterNickname())
				.isEqualTo(MEMBER_NICKNAME);
		assertThat(created.writerNickname()).isEqualTo(MEMBER_NICKNAME);
	}

	/**
	 * 완료 기준 <b>"원격 호출이 DB 트랜잭션 밖에서 일어난다"</b>
	 * (sp-docs/requirements/board.md §3 규칙 8, sp-docs/architecture.md §5).
	 *
	 * <p><b>행위가 아니라 경계를 관측한다</b> (sp-docs/process/review-policy.md §4.1). 호출된
	 * 스레드에서 실제 트랜잭션이 활성인지를 호출 시점에 읽는다 — 호출을 트랜잭션 안으로 옮기는
	 * 변이에서 그 값이 {@code true} 가 되어 이 단언이 빨개진다. 저장된 행만 보는 단언으로는
	 * 호출 위치를 가를 수 없다: 트랜잭션 안에서 호출해도 댓글은 똑같이 저장된다.
	 *
	 * <p><b>"호출됐다" 를 함께 단언한다.</b> 기록을 {@code null} 로 두고 시작하므로, 호출이
	 * 아예 없어지면 {@code isNotNull()} 이 터진다 — 그러지 않으면 호출을 지우는 변이에서도
	 * "활성 아님" 이 통과해 단언이 반증 불가능해진다.
	 *
	 * <p>이 테스트는 {@code @Transactional} 을 달지 않는다. 달면 테스트가 연 트랜잭션이 이미
	 * 활성이어서 관측값이 구현과 무관하게 {@code true} 가 된다.
	 */
	@Test
	@DisplayName("MemberClient 호출 시점에 DB 트랜잭션이 활성이 아니다 (조회 -> BEGIN 순서)")
	void 원격_호출은_트랜잭션_시작_전이다() {
		Post post = savedPost(801L);
		AtomicReference<Boolean> 호출_시점의_트랜잭션 = new AtomicReference<>();
		willAnswer(invocation -> {
			호출_시점의_트랜잭션.set(TransactionSynchronizationManager.isActualTransactionActive());
			return MEMBER_NICKNAME;
		}).given(this.memberClient).findActiveNickname(804L);

		this.commentService.create(post.getId(), 804L, new CommentCreateRequest("순서", null));

		assertThat(호출_시점의_트랜잭션.get())
				.as("MemberClient 가 호출되지 않았다면 이 단언은 아무것도 보지 못한다")
				.isNotNull()
				.as("원격 호출이 트랜잭션 안에 있으면 DB 커넥션이 member 의 지연만큼 점유된다 "
						+ "(sp-docs/architecture.md §5)")
				.isFalse();
	}

	/**
	 * 검증표 "없는 게시글에 댓글 → 404 {@code P001}". 판정은 {@code PostReader} 가 하고
	 * {@code CommentService} 는 {@code deleted} 조건을 다시 쓰지 않는다
	 * (sp-docs/requirements/board.md §5 규칙 1).
	 */
	@Test
	@DisplayName("없는 게시글에 댓글을 달면 P001 이고 댓글이 저장되지 않는다")
	void 없는_게시글은_P001() {
		given(this.memberClient.findActiveNickname(805L)).willReturn(MEMBER_NICKNAME);

		assertThatThrownBy(() -> this.commentService.create(
				987654323L, 805L, new CommentCreateRequest("없는 글에 쓰는 댓글", null)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POST_NOT_FOUND);

		assertThat(내용으로_센_댓글_수("없는 글에 쓰는 댓글")).isZero();
	}

	// --- 수정 (C-03) ---

	/** 검증표 "본인 댓글 수정 → 200" 중 <b>반영됨</b> 쪽이다. 200 은 컨트롤러 테스트가 본다. */
	@Test
	@DisplayName("본인 댓글 수정이면 내용이 바뀌고 응답에도 바뀐 값이 실린다")
	void 본인_댓글_수정이_반영된다() {
		Post post = savedPost(806L);
		Comment comment = savedComment(post, 806L, "원래 댓글");

		CommentResponse updated = this.commentService.update(
				comment.getId(), user(806L), new CommentUpdateRequest("바뀐 댓글"));

		assertThat(updated.content()).isEqualTo("바뀐 댓글");
		assertThat(this.commentRepository.findById(comment.getId()).orElseThrow().getContent())
				.isEqualTo("바뀐 댓글");
	}

	/**
	 * <b>작성자 스냅샷은 수정되지 않는다</b> (sp-docs/requirements/board.md §3 규칙 7).
	 * {@code Comment.update(String)} 가 {@code content} 만 받는 것이 집행 지점이고, 그래서 수정
	 * 경로는 member 를 호출하지 않는다 (sp-docs/api-contract.md §4.2).
	 *
	 * <p><b>"반영됨" 을 먼저 단언한다.</b> {@code update} 가 아무것도 하지 않는 변이에서도
	 * 스냅샷은 그대로이므로, 수정이 실제로 일어났는지를 보지 않으면 아래 두 단언이 그 변이를
	 * 통과시킨다.
	 */
	@Test
	@DisplayName("수정해도 writer_id 와 writer_nickname 은 그대로다")
	void 수정_후_작성자_스냅샷은_그대로다() {
		Post post = savedPost(806L);
		Comment comment = savedComment(post, 807L, "원래 댓글");

		this.commentService.update(
				comment.getId(), user(807L), new CommentUpdateRequest("바뀐 댓글"));

		Comment saved = this.commentRepository.findById(comment.getId()).orElseThrow();
		assertThat(saved.getContent())
				.as("수정이 일어나지 않았다면 아래 두 단언은 아무것도 보지 못한다")
				.isEqualTo("바뀐 댓글");
		assertThat(saved.getWriterId()).isEqualTo(807L);
		assertThat(saved.getWriterNickname()).isEqualTo("댓글작성자");
	}

	// --- 삭제 (C-04, sp-docs/domain-model.md §5) ---

	/**
	 * 완료 기준 "삭제는 Soft Delete 다" 와 "댓글 삭제 시 1 감소한다". 행이 남는 것은
	 * sp-docs/requirements/board.md §5 규칙 2 다.
	 */
	@Test
	@DisplayName("본인 댓글 삭제는 deleted = true 로 행을 남기고 comment_count 를 1 줄인다")
	void 본인_댓글_삭제는_soft_delete_다() {
		Post post = savedPost(808L);
		Comment comment = 서비스로_작성한_댓글(post, 808L, "지울 댓글");

		this.commentService.delete(comment.getId(), user(808L));

		assertThat(this.commentRepository.findById(comment.getId()))
				.as("논리 삭제이므로 행은 남아 있다 (sp-docs/requirements/board.md §5 규칙 2)")
				.isPresent()
				.get()
				.extracting(Comment::isDeleted)
				.isEqualTo(true);
		assertThat(커밋된_집계(post.getId()))
				.as("댓글 삭제 시 comment_count 가 1 감소한다 (sp-docs/domain-model.md §5)")
				.isZero();
	}

	/** 검증표 "댓글 3개 작성 후 1개 삭제 → {@code comment_count = 2}". */
	@Test
	@DisplayName("댓글 3개를 작성한 뒤 1개를 삭제하면 comment_count 가 2 다")
	void 셋_작성_하나_삭제면_집계가_2() {
		Post post = savedPost(809L);
		서비스로_작성한_댓글(post, 809L, "댓글1");
		Comment second = 서비스로_작성한_댓글(post, 809L, "댓글2");
		서비스로_작성한_댓글(post, 809L, "댓글3");
		assertThat(커밋된_집계(post.getId()))
				.as("세 번 작성이 집계에 반영되지 않았다면 아래 단언은 아무것도 보지 못한다")
				.isEqualTo(3);

		this.commentService.delete(second.getId(), user(809L));

		assertThat(커밋된_집계(post.getId())).isEqualTo(2);
	}

	/**
	 * 검증표 "같은 댓글 2회 삭제 시도 → {@code comment_count} 음수 안 됨".
	 *
	 * <p><b>이미 삭제된 댓글은 404 {@code CM001} 이므로 집계가 두 번 깎이지 않는다.</b> 픽스처를
	 * 댓글 둘로 두는 것이 요점이다 — 하나면 두 번째 삭제가 집계를 깎아도 0 에서 가드에 막혀
	 * 1회 삭제의 결과와 구별되지 않는다. 둘이면 두 번째 삭제가 통과하는 구현에서 집계가 0 이
	 * 되어 빨개진다.
	 *
	 * <p>sp-docs/plan/phase2-board.md B-17 검증표의 「같은 댓글 순차 2회 삭제」 도 이 테스트다.
	 * 순차에서는 두 번째 요청이 읽기의 판정에서 404 를 받아 쓰기에 닿지 않는다 — 동시 삭제는
	 * 그렇지 않으므로
	 * {@link 삭제의_순서와_조건부_전환#같은_댓글의_동시_삭제는_집계를_한_번만_줄인다()} 가 따로 본다.
	 */
	@Test
	@DisplayName("같은 댓글을 두 번 삭제하면 두 번째는 CM001 이고 집계가 두 번 깎이지 않는다")
	void 같은_댓글_두_번_삭제() {
		Post post = savedPost(810L);
		Comment target = 서비스로_작성한_댓글(post, 810L, "지울 댓글");
		서비스로_작성한_댓글(post, 810L, "남는 댓글");

		this.commentService.delete(target.getId(), user(810L));

		assertThatThrownBy(() -> this.commentService.delete(target.getId(), user(810L)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.COMMENT_NOT_FOUND);
		assertThat(커밋된_집계(post.getId()))
				.as("두 번째 삭제가 집계를 다시 깎으면 1 이 아니라 0 이 된다")
				.isEqualTo(1);
	}

	/**
	 * 완료 기준 <b>"{@code comment_count} 가 음수가 되지 않는다"</b>
	 * (sp-docs/requirements/board.md §5 규칙 4, sp-docs/domain-model.md §5).
	 *
	 * <p>집계와 실제 행 수가 어긋난 상태를 픽스처로 만든다 — 집계는 0 인데 활성 댓글이 있다.
	 * 비정규화된 값이라 운영에서 어긋날 수 있고, 그때 삭제가 집계를 0 아래로 내리면 안 된다.
	 *
	 * <p><b>가드는 {@code PostRepository.decreaseCommentCount} 의 {@code where} 절이다</b>
	 * ({@code comment_count > 0}). B-10 이 집계 갱신을 좁은 UPDATE 로 바꾸면서 가드가 엔티티
	 * 메서드에서 그 문장으로 옮겨 갔다 (sp-docs/plan/phase1.md §6 B-10) — 좁은 UPDATE 는
	 * 엔티티를 지나지 않으므로 조건이 엔티티에 남아 있으면 지나가지 않는다. <b>조건을 뺀
	 * 문장은 여기서 {@code -1} 을 커밋해 이 단언이 빨개진다</b>(실측).
	 *
	 * <p><b>B-17 뒤로는 이 픽스처에서 감소가 먼저이고 0 행이다.</b> 그래도 댓글의 전환을 그대로
	 * 하고 성공해야 한다 — sp-docs/plan/phase2-board.md B-17 검증표의 「집계가 이미 0 인 활성 댓글
	 * 삭제」 가 이 테스트다. 감소의 0 행을 실패로 다뤄 되돌리는 구현은 여기서 예외로 빨개진다.
	 */
	@Test
	@DisplayName("집계가 0 인데 활성 댓글이 있어도 삭제 후 comment_count 가 음수가 되지 않는다")
	void 집계는_음수가_되지_않는다() {
		Post post = savedPost(811L);
		Comment comment = savedComment(post, 811L, "집계에 안 잡힌 댓글");
		assertThat(커밋된_집계(post.getId()))
				.as("픽스처가 집계 0 으로 시작해야 이 테스트가 일한다")
				.isZero();

		this.commentService.delete(comment.getId(), user(811L));

		assertThat(this.commentRepository.findById(comment.getId()).orElseThrow().isDeleted())
				.as("삭제가 일어나지 않았다면 아래 단언은 아무것도 보지 못한다")
				.isTrue();
		assertThat(커밋된_집계(post.getId())).isZero();
	}

	// --- 수정·삭제·목록은 member 를 호출하지 않는다 (sp-docs/api-contract.md §4.2) ---

	/**
	 * 검증표 "댓글 수정·삭제 시 {@code MemberClient} → 호출 0회". 수정 시 스냅샷을 갱신하지
	 * 않으므로 닉네임을 다시 얻을 이유가 없고, 활성 프로필은 생성에만 필요하다
	 * (sp-docs/requirements/board.md §3 규칙 7, §5 규칙 8). 그래서 <b>member 장애가 댓글
	 * 수정·삭제를 막지 않는다.</b>
	 *
	 * <p>픽스처를 서비스가 아니라 리포지터리로 만든다 — 작성 경로가 호출을 하나 남기면 이
	 * 단언이 그것과 구별되지 않는다.
	 */
	@Test
	@DisplayName("수정·삭제는 MemberClient 를 호출하지 않는다")
	void 수정과_삭제는_member_를_호출하지_않는다() {
		Post post = savedPost(816L);
		Comment comment = savedComment(post, 816L, "원래 댓글");

		this.commentService.update(
				comment.getId(), user(816L), new CommentUpdateRequest("바뀐 댓글"));
		this.commentService.delete(comment.getId(), user(816L));

		assertThat(this.commentRepository.findById(comment.getId()).orElseThrow().isDeleted())
				.as("수정·삭제가 실제로 일어나지 않았다면 아래 단언은 아무것도 보지 못한다")
				.isTrue();
		verifyNoInteractions(this.memberClient);
	}

	/** 검증표 "댓글 목록 조회 시 {@code MemberClient} → 호출 0회" (§4.2). */
	@Test
	@DisplayName("목록 조회는 MemberClient 를 호출하지 않는다")
	void 목록_조회는_member_를_호출하지_않는다() {
		Post post = savedPost(817L);
		savedComment(post, 817L, "댓글");

		PageResponse<TopLevelCommentResponse> page = this.commentService.getList(
				post.getId(), CommentPageCondition.of(null, null));

		assertThat(page.content())
				.as("목록이 비어 있으면 호출이 없는 것이 당연해져 단언이 아무것도 보지 못한다")
				.hasSize(1);
		verifyNoInteractions(this.memberClient);
	}

	// --- 목록 (C-02, sp-docs/api-contract.md §7.1) ---

	/**
	 * 검증표 "목록 조회 → 등록순, 기본 20건" 과 완료 기준 "{@code created_at ASC, id ASC} 다".
	 *
	 * <p><b>저장 순서와 시각 순서를 어긋나게 둔다.</b> 연속 저장만 하면
	 * {@code created_at} 오름차순과 {@code id} 오름차순이 같은 순서(또는 동률)가 되어,
	 * {@code id} 로만 정렬하는 구현과 구별되지 않는다. 시각을 저장 순서의 <b>역순</b>으로 박으면 기대 순서가
	 * 저장 순서의 역순이 되고, {@code created_at DESC} 구현과 {@code id ASC} 구현이 둘 다
	 * 빨개진다.
	 *
	 * <p>21건을 두고 기본 크기로 조회하므로 <b>첫 페이지가 꽉 찬다</b> — {@code PageImpl} 의
	 * 마지막 페이지 보정({@code offset + content.size()})이 일어나지 않아
	 * {@code totalElements} 단언이 건수 쿼리를 실제로 본다
	 * (sp-docs/process/review-policy.md §4.1).
	 */
	@Test
	@DisplayName("목록은 created_at ASC, id ASC 이고 기본 20건이다")
	void 목록은_등록순_기본_20건이다() {
		Post post = savedPost(818L);
		List<Long> 저장_순서 = new ArrayList<>();
		for (int i = 0; i < 21; i++) {
			저장_순서.add(savedComment(post, 818L, "댓글 " + i).getId());
		}
		// 시각을 저장 순서의 역순으로 박는다. 그래서 기대 순서도 저장 순서의 역순이다
		for (int i = 0; i < 저장_순서.size(); i++) {
			setCreatedAt(저장_순서.get(i), FIXED_TIME.plusSeconds(저장_순서.size() - i));
		}
		List<Long> 기대_순서 = new ArrayList<>(저장_순서);
		Collections.reverse(기대_순서);

		PageResponse<TopLevelCommentResponse> page = this.commentService.getList(
				post.getId(), CommentPageCondition.of(null, null));

		assertThat(page.size())
				.as("기본 크기는 20 이다 (sp-docs/requirements/board.md §2 의 C-02)")
				.isEqualTo(20);
		assertThat(page.content()).extracting(TopLevelCommentResponse::id)
				.as("등록순은 created_at ASC, id ASC 다 (sp-docs/api-contract.md §7.1)")
				.containsExactlyElementsOf(기대_순서.subList(0, 20));
		assertThat(page.totalElements()).isEqualTo(21);
		assertThat(page.totalPages()).isEqualTo(2);
	}

	/**
	 * 검증표 "삭제된 댓글 포함 상태 → 목록에서 제외. <b>건수 쿼리도 제외한다</b>"
	 * (sp-docs/requirements/board.md §5 규칙 1).
	 *
	 * <p><b>B-22 뒤로 제외되는 것은 활성 답글이 없는 삭제된 댓글이다</b> — 같은 규칙의 예외로 활성
	 * 답글이 있는 삭제된 최상위는 자리로 남는다 (§5 규칙 1, §12). 이 픽스처의 삭제된 댓글에는 답글이
	 * 없다. 자리로 남는 쪽은 {@link 답글} 이 본다.
	 *
	 * <p><b>건수 쿼리의 조건을 관측하려면 두 가지가 모두 필요하다</b>
	 * (sp-docs/process/review-policy.md §4.1).
	 *
	 * <p>하나는 <b>페이지가 꽉 차는 것</b>이다. {@code PageImpl} 은 마지막 페이지에서
	 * {@code totalElements} 를 {@code offset + content.size()} 로 보정하므로, 결과가 한
	 * 페이지에 다 들어가면 건수 쿼리의 조건이 빠져도 보정이 그것을 덮는다. 그래서
	 * {@code size} 를 2 로 두고 다음 페이지가 남게 만든다 — {@code offset 0 + size 2 = 2} 이고
	 * 올바른 건수는 3 이라 보정 조건({@code offset + size > total})이 거짓이다.
	 *
	 * <p>다른 하나는 <b>그 조건이 배제하는 행이 픽스처에 있는 것</b>이다. 꽉 찬 페이지는
	 * 필요조건일 뿐이다 — 조건이 아무 행도 걸러내지 않으면 조건을 떼어낸 건수와 붙인 건수가
	 * 같은 값이라 단언이 아무것도 보지 못한다. 그래서 <b>삭제된 댓글 하나</b>를 같은 게시글에
	 * 둔다: {@code deleted} 조건이 빠지면 건수가 4 가 되어 터진다.
	 *
	 * <p>다른 게시글의 활성 댓글도 하나 둔다 — 목록이 게시글별인 것을 함께 본다.
	 */
	@Test
	@DisplayName("활성 답글이 없는 삭제된 댓글은 목록과 건수에서 모두 제외된다")
	void 삭제된_댓글은_목록에서_제외된다() {
		Post post = savedPost(819L);
		Long first = savedComment(post, 819L, "남는 댓글 하나").getId();
		Long second = savedComment(post, 819L, "남는 댓글 둘").getId();
		Long third = savedComment(post, 819L, "남는 댓글 셋").getId();
		Comment removed = savedComment(post, 819L, "삭제된 댓글");
		removed.softDelete();
		this.commentRepository.saveAndFlush(removed);
		savedComment(savedPost(820L), 820L, "다른 글의 댓글");

		PageResponse<TopLevelCommentResponse> 전체 = this.commentService.getList(
				post.getId(), CommentPageCondition.of(null, null));
		PageResponse<TopLevelCommentResponse> 꽉_찬_페이지 = this.commentService.getList(
				post.getId(), CommentPageCondition.of(0, 2));

		assertThat(전체.content()).extracting(TopLevelCommentResponse::id)
				.as("삭제된 댓글과 다른 글의 댓글이 목록에서 빠진다")
				.containsExactly(first, second, third);
		assertThat(꽉_찬_페이지.content()).hasSize(2);
		assertThat(꽉_찬_페이지.totalElements())
				.as("건수 쿼리에도 deleted = false 가 붙어야 한다")
				.isEqualTo(3);
		assertThat(꽉_찬_페이지.totalPages()).isEqualTo(2);
		assertThat(this.commentRepository.findById(removed.getId()))
				.as("논리 삭제이므로 행은 남아 있다")
				.isPresent();
	}

	/**
	 * 검증표 "{@code created_at} 이 같은 댓글 다수 → 등록순이 {@code id} 로 결정된다. 중복·누락
	 * 없음" (sp-docs/api-contract.md §7.1).
	 *
	 * <p><b>특정 동률 조합을 만들려면 시각을 직접 박는다.</b> 연속 저장만으로도 동률은
	 * 생긴다 — {@code D-11} 이 감사 시각을 초로 자른 뒤로 테스트도 운영과 같다
	 * (sp-docs/api-contract.md §4.3). <b>앞 판은 "H2 는 마이크로초를 유지한다" 를 이유로
	 * 적었고 그 이유가 끝났다.</b> 박는 이유는 조합을 고르기 위해서다 (§7.1).
	 *
	 * <p>1차 키가 전부 동률이므로 남는 순서는 마지막 정렬 키뿐이고 그것은 {@code id ASC} 다.
	 * 세 페이지를 이어 붙여 <b>중복도 누락도 없는지</b> 본다 — {@code OFFSET} 페이징은 정렬이
	 * 전순서가 아니면 같은 행이 두 페이지에 나오거나 어느 페이지에도 나오지 않는다.
	 */
	@Test
	@DisplayName("created_at 이 전부 같으면 id 오름차순이고 페이지 경계에 중복·누락이 없다")
	void created_at_동률의_페이지_경계() {
		Post post = savedPost(821L);
		List<Long> all = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			all.add(savedComment(post, 821L, "동률 " + i).getId());
		}
		all.forEach(id -> setCreatedAt(id, FIXED_TIME));

		List<Long> 이어_붙인_목록 = new ArrayList<>();
		for (int page = 0; page < 3; page++) {
			이어_붙인_목록.addAll(this.commentService
					.getList(post.getId(), CommentPageCondition.of(page, 2))
					.content().stream().map(TopLevelCommentResponse::id).toList());
		}

		assertThat(이어_붙인_목록)
				.as("1차 키가 전부 동률이므로 남는 순서는 마지막 정렬 키뿐이고 그것은 id ASC 다 "
						+ "(sp-docs/api-contract.md §7.1)")
				.containsExactlyElementsOf(all)
				.doesNotHaveDuplicates();
	}

	/**
	 * 완료 기준 <b>"댓글 INSERT 와 {@code comment_count} 갱신이 한 로컬 트랜잭션이다"</b>
	 * (sp-docs/domain-model.md §5). 검증표의 두 줄 — "댓글 저장 중 예외 발생 → 댓글과
	 * {@code comment_count} 둘 다 롤백. 두 단언이 각각 반증 가능해야 한다" 와 "한 번의 작성에
	 * 커밋이 정확히 한 번 — 변이 셋(A·B·C)을 돌린다" — 이 여기 있다.
	 *
	 * <p>빈 하나를 스파이로 바꾸고 커밋 기록기를 더해야 하므로 중첩 클래스이고, 바깥 클래스의
	 * {@code commentService} 에는 스파이가 아닌 원래 빈이 들어 있으므로 여기서 다시 주입받는다.
	 *
	 * <table border="1">
	 * <caption>{@code create} 변이별 실패 (B-10 이후 전체 스위트로 재측정)</caption>
	 * <tr><th>변이</th><th>무엇이 깨지나</th><th>실측 결과</th></tr>
	 * <tr><td><b>A</b> — 트랜잭션 템플릿 제거 (본문을 그대로 실행)</td>
	 * <td>경계가 아예 없다</td>
	 * <td><b>19개 실패.</b> 밑에 깔린 원인은 19개 모두
	 * {@code InvalidDataAccessApiUsageException: No EntityManager with actual transaction
	 * available for current thread - cannot reliably process 'flush' call} 이다 — 집계 갱신이
	 * 좁은 UPDATE 가 되면서 {@code flushAutomatically} 가 활성 트랜잭션을 요구하므로 그 자리에서
	 * 즉시 터진다. <b>실패의 모양은 그렇지 않다</b> — 11개가 그 예외로 끝나고, 8개는 그것이
	 * 간접으로 드러난 {@code AssertionError} 다 (컨트롤러의 500 응답, 수집한 실패 목록이 비지
	 * 않은 것, 기대한 예외 타입이 아닌 것이 그 모양이다).
	 * <b>{@link #커밋이_정확히_한_번이다()} 도 그 예외로 빨개진다</b></td></tr>
	 * <tr><td><b>B</b> — 예외를 삼켜 커밋시킨 뒤 다시 던지기
	 * ({@code noRollbackFor} 와 같은 효과)</td>
	 * <td>트랜잭션은 있고 원자성만 깨진다</td>
	 * <td><b>1개 실패 — {@link #둘_다_롤백된다()} 하나뿐.</b> 거기서 <b>집계 단언</b>이
	 * 빨개진다 (0 기대, 1 관측)</td></tr>
	 * <tr><td><b>C</b> — 연산마다 자기 트랜잭션 ({@code TransactionTemplate} 두 번)</td>
	 * <td><b>독립적으로 커밋되는 트랜잭션 둘</b></td>
	 * <td><b>2개 실패, 둘 다 진짜 단언 실패.</b>
	 * {@link #커밋이_정확히_한_번이다()} 의 집계가 <b>2</b> 였고,
	 * {@link #둘_다_롤백된다()} 의 집계 단언도 빨개졌다</td></tr>
	 * </table>
	 *
	 * <p><b>A 를 경계 관측의 몫으로 기대하지 않는다</b>
	 * (sp-docs/process/review-policy.md §4.1). B-08 에서는 A 가 예외를 내지 않고 집계가 1
	 * 그대로여서 경계 관측이 통과했고, B-10 이후에는 같은 A 가 예외로 먼저 터진다. 어느 쪽이
	 * 잡는지가 항목·구현마다 다르다는 그 절의 지적이 그대로 재현됐다 — <b>단언은 "정확히 한
	 * 번" 에만 걸려 있고 A 의 거동에 기대지 않는다.</b>
	 *
	 * <p><b>{@link #둘_다_롤백된다()} 의 두 단언이 각각 반증 가능하다</b>(§4.1). 집계 단언은
	 * 변이 B 가 그대로 빨갛게 만든다. 댓글 행 단언은 그 앞의 집계 단언에 가려지므로 따로
	 * 측정했다 — <b>변이 B 에서 집계 단언을 지우면 댓글 행 단언이 빨개진다</b>(0 기대, 1
	 * 관측 — 주입한 INSERT 가 커밋됐다). 둘 다 되돌렸다.
	 *
	 * <p><b>집계 2 는 측정값이고 예측값이 아니다</b>(§4.1 — 그 절의 앞 판이 숫자를 두 번
	 * 틀렸다). 같은 변이의 집계가 항목마다 다르다. <b>단언은 "정확히 한 번" 에만 걸고 이
	 * 수치에 걸지 않는다.</b>
	 *
	 * <p><b>C 가 핵심이고 행위 단언으로 잡히지 않는다</b>
	 * (sp-docs/process/review-policy.md §4.1). 정상 경로는 두 효과가 여전히 둘 다 일어나므로
	 * 통과하고, 실패를 주입해도 "한 트랜잭션" 과 "첫 트랜잭션이 롤백된 두 트랜잭션" 이 관측상
	 * 구별되지 않는다. 그래서 커밋 횟수를 직접 센다.
	 *
	 * <p><b>이 셋이 잡힌다는 것은 아무것도 증명하지 않는다</b>(§4.1). 변이 테스트는 반증
	 * 도구다 — 잡히지 않은 변이가 발견이다.
	 *
	 * <p>C 의 손상이 이 항목에서 뜻하는 것: <b>댓글은 있는데 집계가 그대로이거나, 집계만
	 * 올라가고 댓글이 없다</b> (sp-docs/domain-model.md §5).
	 */
	@Nested
	@DisplayName("댓글 INSERT 와 comment_count 갱신이 한 로컬 트랜잭션이다")
	@ContextConfiguration(classes = PhysicalCommitRecorder.Config.class)
	class 작성_트랜잭션_경계 {

		/** 댓글 저장 지점에 실패를 주입한다. 나머지 메서드는 실제 빈에 위임된다. */
		@MockitoSpyBean
		private CommentRepository spyCommentRepository;

		/** <b>경계를 관측하는 지점이다.</b> 세는 법은 {@link PhysicalCommitRecorder} 다. */
		@Autowired
		private PhysicalCommitRecorder 커밋_기록기;

		/** {@code spyCommentRepository} 를 주입받은 쪽이다. 바깥 클래스의 것과 다른 인스턴스다. */
		@Autowired
		private CommentService commentService;

		@Autowired
		private PostRepository postRepository;

		/**
		 * <b>바깥 클래스의 목 필드를 쓰지 않고 다시 주입받는다.</b> 스파이와 커밋 기록기가 더해져 이
		 * 중첩 클래스는 다른 애플리케이션 컨텍스트를 쓰고, 바깥 인스턴스의 필드는 바깥
		 * 클래스의 컨텍스트에서 주입되므로 거기에 스텁을 걸어도 이쪽 서비스가 보는 목이
		 * 아니다. 바깥의 {@code @MockitoBean} 은 중첩 클래스에 상속되므로 이 컨텍스트의
		 * {@code MemberClient} 빈도 목이고, 그것을 {@code @Autowired} 로 받는다.
		 */
		@Autowired
		private MemberClient memberClient;

		/**
		 * <b>JPA 를 거치지 않고 읽는다.</b> 롤백 여부를 보는 것이므로 영속성 컨텍스트가 아니라
		 * 커밋된 행을 봐야 한다. {@link #둘_다_롤백된다()} 는 쓰기에도 쓴다 — 거기에 이유가
		 * 적혀 있다.
		 */
		@Autowired
		private JdbcTemplate jdbcTemplate;

		/**
		 * 검증표 <b>"한 번의 작성에 커밋이 정확히 한 번"</b>. 경계 자체를 관측하므로 행위
		 * 단언으로 대체할 수 없다 (sp-docs/process/review-policy.md §4.1).
		 *
		 * <p><b>새로 연 트랜잭션의 커밋만 센다</b> — 세는 법은 {@link PhysicalCommitRecorder} 다.
		 * 참여 트랜잭션의 완료는 물리 트랜잭션의 커밋이 아니다. 이 경로의 {@code saveAndFlush} 는
		 * {@code SimpleJpaRepository} 의 {@code @Transactional} 을 지나 템플릿의 트랜잭션에
		 * 참여하므로 세지 않는다.
		 *
		 * <p><b>단언은 "정확히 한 번" 에만 걸고 다른 수치를 예측값으로 쓰지 않는다</b>(§4.1).
		 */
		@Test
		@DisplayName("한 번의 작성에 물리 트랜잭션이 정확히 하나 커밋된다")
		void 커밋이_정확히_한_번이다() {
			given(this.memberClient.findActiveNickname(822L)).willReturn(MEMBER_NICKNAME);
			Post post = 게시글을_만든다(822L);

			// 준비가 끝난 뒤에 센다. 세는 구간을 create 한 번으로 좁혀 둔다
			List<String> 커밋된_물리_트랜잭션 = this.커밋_기록기.물리_커밋(() -> this.commentService.create(
					post.getId(), 822L, new CommentCreateRequest("경계 관측", null)));

			assertThat(커밋된_물리_트랜잭션)
					.as("댓글 INSERT 와 comment_count 갱신이 한 로컬 트랜잭션이다 "
							+ "(sp-docs/domain-model.md §5)")
					.hasSize(1);
		}

		/**
		 * 검증표 <b>"댓글 저장 중 예외 발생 → 댓글과 {@code comment_count} 둘 다 롤백. 두
		 * 단언이 각각 반증 가능해야 한다"</b>.
		 *
		 * <p><b>스텁이 실제 INSERT 를 돌린 뒤에 throw 한다.</b> 그냥 {@code willThrow} 로
		 * 가로채면 INSERT 가 발행될 자리가 사라져 "댓글도 롤백된다" 가 <b>어떤 구현에서도
		 * 참</b>이 된다 — 트랜잭션이 아예 없어도 통과하는 반증 불가능한 단언이다
		 * (sp-docs/process/review-policy.md §4.1). 되돌릴 효과를 실제로 만들어야 단언이
		 * 일한다.
		 *
		 * <p>{@code comment_count} 쪽은 반대로 <b>미반영이 정상</b>이다. 관리 엔티티의 변경이
		 * 커밋 시점에 flush 되므로, 롤백되면 쓰이지 않고 커밋되면 증가분이 쓰인다. 그래서
		 * 서비스가 집계 증가를 INSERT 보다 <b>앞</b>에 둔다 — 뒤에 두면 가로채는 순간 집계
		 * 변경이 아예 일어나지 않아 이 단언도 반증 불가능해진다.
		 *
		 * <p><b>INSERT 를 {@code JdbcTemplate} 으로 발행한다.</b> 리포지터리는 JDK 프록시이고
		 * 스파이가 그것을 {@code delegatesTo} 로 감싸므로 {@code callRealMethod()} 를 쓸 수
		 * 없다("Cannot call abstract real method"). {@code JpaTransactionManager} 가 같은
		 * {@code DataSource} 의 커넥션을 트랜잭션에 묶으므로 이 INSERT 는 <b>서비스의
		 * 트랜잭션에 참여한다</b> — 경계가 없으면 그 자리에서 커밋되고, 있으면 함께 롤백된다.
		 */
		@Test
		@DisplayName("댓글 저장이 실패하면 댓글도 comment_count 증가도 커밋되지 않는다")
		void 둘_다_롤백된다() {
			given(this.memberClient.findActiveNickname(823L)).willReturn(MEMBER_NICKNAME);
			Post post = 게시글을_만든다(823L);

			willAnswer(invocation -> {
				// 이 문장의 짝은 CommentRepository.saveAndFlush 다. 둘이 어긋나면 아래 단언이
				// 그 자리에서 터진다 — 영향 행 수를 버리면 0행이 되어도 조용하고, 되돌릴
				// 효과가 없으므로 댓글 단언이 반증 불가능해진다
				// (sp-docs/process/review-policy.md §4.1 "실패할 수 없는 단언을 쓰지 않는다")
				int inserted = this.jdbcTemplate.update(
						"INSERT INTO comment "
								+ "(post_id, writer_id, writer_nickname, content, deleted, "
								+ "created_at, updated_at) VALUES (?, ?, ?, ?, FALSE, ?, ?)",
						post.getId(), 823L, MEMBER_NICKNAME, "주입된 댓글",
						FIXED_TIME, FIXED_TIME);
				assertThat(inserted)
						.as("주입한 INSERT 가 실제로 행을 넣어야 이 테스트가 일한다")
						.isEqualTo(1);
				throw new DataIntegrityViolationException("댓글 저장 실패");
			}).given(this.spyCommentRepository).saveAndFlush(any(Comment.class));

			assertThatThrownBy(() -> this.commentService.create(
					post.getId(), 823L, new CommentCreateRequest("롤백될 댓글", null)))
					.isInstanceOf(DataIntegrityViolationException.class);

			// 두 단언이 각각 반증 가능해야 한다 (sp-docs/process/review-policy.md §4.1).
			// 변이 B 가 앞의 집계 단언을 빨갛게 만들고, 뒤의 댓글 단언은 그것에 가려지므로
			// 따로 측정했다 — 변이 B 에서 집계 단언을 지우면 댓글 단언이 1 로 빨개진다
			// (클래스 주석의 변이 표). 순서를 바꾸면 가려지는 쪽이 바뀔 뿐이고,
			// 어느 순서든 가려진 쪽은 깨뜨려 봐야 알 수 있다
			assertThat(커밋된_집계(post.getId()))
					.as("두 효과가 한 로컬 트랜잭션이다 (sp-docs/domain-model.md §5)")
					.isZero();
			assertThat(커밋된_댓글_수(post.getId()))
					.as("주입한 INSERT 도 함께 되돌아간다 — 댓글 행이 남아 있으면 안 된다")
					.isZero();
		}

		private Post 게시글을_만든다(long writerId) {
			return this.postRepository.saveAndFlush(Post.builder()
					.writerId(writerId)
					.writerNickname("글작성자")
					.title("경계 관측 대상")
					.content("본문")
					.build());
		}

		private long 커밋된_댓글_수(Long postId) {
			Long count = this.jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM comment WHERE post_id = ?", Long.class, postId);
			return count == null ? 0 : count;
		}

		private int 커밋된_집계(Long postId) {
			Integer count = this.jdbcTemplate.queryForObject(
					"SELECT comment_count FROM post WHERE id = ?", Integer.class, postId);
			return count == null ? 0 : count;
		}

	}

	/**
	 * sp-docs/plan/phase1.md §6 <b>B-10</b> 의 검증표 중 동시 작성·동시 삭제와 발행된 UPDATE 의
	 * 모양이다. 같은 표의 "같은 댓글 2회 삭제" 와 "한 번의 작성에 커밋" 은 B-08 이 둔 테스트가
	 * 그대로 본다 ({@link CommentServiceTest#같은_댓글_두_번_삭제()},
	 * {@link CommentServiceTest#집계는_음수가_되지_않는다()},
	 * {@link 작성_트랜잭션_경계#커밋이_정확히_한_번이다()}).
	 *
	 * <h2>소실 갱신은 DB 가 아니라 애플리케이션에서 일어난다</h2>
	 *
	 * <p>두 트랜잭션이 각각 엔티티를 읽어 메모리에서 증가시키면 마지막 쓰기가 이긴다 —
	 * <b>H2 에서도 재현된다.</b> 실제 MySQL 이 필요하지 않다. 다만 <b>두 읽기가 두 쓰기보다
	 * 먼저 일어나도록 끼워넣기를 강제한다</b> (sp-docs/plan/phase1.md §6 B-10 의 블록인용).
	 *
	 * <p><b>강제는 결정성의 보장이지, 강제가 없으면 공허해진다는 뜻이 아니다.</b> 집결지를
	 * 무력화하고 엔티티 증가·감소 변이와 조합해 7회 돌린 실측에서 <b>14개 관측이 전부 같은
	 * 값으로</b> 빨개졌다 — 작성이 {@code expected: 3 but was: 1}, 삭제가
	 * {@code expected: 0 but was: 2} 이고 <b>0회도 중간값도 없었다.</b> 올바른 구현에
	 * 집결지만 무력화하면 전부 통과하므로 무력화 자체가 빨갛게 만드는 것도 아니다. 소실의
	 * 창이 넓어서다: 첫 스레드가 SELECT → UPDATE → INSERT → COMMIT 을 끝내는 동안 나머지는
	 * SELECT 만 들어가면 되고, H2 의 행 락이 뒤의 UPDATE 를 첫 커밋까지 붙잡는다.
	 *
	 * <p><b>그래도 집결지는 그대로 둔다 — 위는 이 기계의 스케줄 관측이고 보장이 아니다.</b>
	 * 코어가 적거나 부하가 걸리면 제출 루프가 늦어져 첫 스레드가 먼저 끝날 여지가 있고,
	 * 집결지가 그 여지를 닫는다.
	 *
	 * <h2>강제하는 지점 — {@code post} 를 바꾸는 문장이 발행되기 직전</h2>
	 *
	 * <p>{@link StatementRecorder} 가 {@code update post …} 를 보면 그 스레드를 집결지에
	 * 세운다. 모든 스레드가 모일 때까지 <b>어느 스레드도 그 문장을 실행하지 않으므로</b>, 모인
	 * 시점에 모든 읽기가 끝나 있고 아직 아무 쓰기도 없다 — 엔티티 증가 구현이면 그 읽기들이
	 * 모두 같은 값을 보았다는 뜻이다.
	 *
	 * <p><b>커밋 시점에 모이게 하면 올바른 구현이 교착한다.</b> 좁은 UPDATE 는 실행 시점에
	 * {@code post} 행 락을 잡으므로, 먼저 실행한 스레드가 집결지를 기다리는 동안 나머지가 그
	 * 락을 기다린다. 발행 직전에 세우면 모두 모인 뒤에 실행이 시작되어 락이 차례로 풀린다.
	 *
	 * <p><b>읽기 지점에 세우는 것으로는 부족하다.</b> 삭제 경로의 {@code post} 읽기는
	 * {@code comment.getPost()} 의 지연 로딩이라 서비스·리포지터리 호출로 가로챌 자리가 없고,
	 * 읽기 직전에 세우면 풀린 뒤의 실행 순서가 스케줄러에 달려 소실이 간헐적으로만 일어난다.
	 *
	 * <p>픽스처의 {@code writerId} 는 901~929 를 쓴다. 바깥 클래스의 {@code @AfterEach} 는
	 * 801~829 만 지우므로 이 대역은 여기서 지운다. {@code comment.post_id} 에 FK 가 있으므로
	 * 댓글을 먼저 지운다.
	 */
	@Nested
	@DisplayName("comment_count 갱신이 좁은 UPDATE 이고 동시 커밋에서 소실되지 않는다")
	@ContextConfiguration(classes = CommentServiceTest.StatementRecorderConfig.class)
	class 집계_갱신의_모양과_동시성 {

		/**
		 * 동시 요청 수. <b>2 이상이어야 소실이 일어날 수 있다</b> — I-03 실측도 동시 커밋이
		 * 2건 이상인 게시글에서만 어긋났다 (sp-docs/nfr.md §1).
		 */
		private static final int 동시_요청 = 3;

		private static final long 동시_작성_대역 = 901L;

		private static final long 동시_삭제_대역 = 902L;

		private static final long 작성_문장_대역 = 903L;

		private static final long 삭제_문장_대역 = 904L;

		/** 집결지에서 기다리는 한도. 넘으면 끼워넣기가 강제되지 않은 것이므로 시끄럽게 터진다. */
		private static final int 집결_한도_초 = 10;

		@Autowired
		private CommentService commentService;

		@Autowired
		private CommentRepository commentRepository;

		@Autowired
		private PostRepository postRepository;

		/** 바깥 클래스의 {@code @MockitoBean} 이 이 컨텍스트에도 상속된다 (위 중첩 클래스와 같다). */
		@Autowired
		private MemberClient memberClient;

		@Autowired
		private JdbcTemplate jdbcTemplate;

		@Autowired
		private StatementRecorder 발행된_문장;

		@BeforeEach
		void member_응답을_둔다() {
			given(this.memberClient.findActiveNickname(anyLong())).willReturn(MEMBER_NICKNAME);
		}

		@AfterEach
		void 픽스처_정리() {
			this.발행된_문장.비운다();
			this.jdbcTemplate.update("DELETE FROM comment WHERE post_id IN "
					+ "(SELECT id FROM post WHERE writer_id BETWEEN 901 AND 929)");
			this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 901 AND 929");
		}

		/**
		 * 검증표 <b>"같은 게시글에 동시 작성 N건 → {@code comment_count} = 활성 댓글 수.
		 * 엔티티 증가로 되돌리면 깨진다"</b>.
		 *
		 * <p>단언 셋이 각각 다른 것을 지킨다.
		 * <ul>
		 * <li>어느 스레드도 실패하지 않았다는 것 — 집결지가 열리지 않았거나 락을 얻지 못하면
		 * 여기서 사유가 드러난다
		 * <li>활성 댓글이 <b>{@code 동시_요청} 건</b>이라는 것 — 커밋이 하나뿐이었으면 아래
		 * 단언은 소실을 볼 수 없다
		 * <li>집계가 그 활성 댓글 수와 <b>같다</b>는 것 — 완료 기준이고 sp-docs/api-contract.md §4.5
		 * 가 지키라고 적은 등식 「{@code comment_count} = 답글을 포함한 활성 댓글 수」 다. 1차의 그
		 * 절은 그 값이 목록의 {@code totalElements} 와 같다고 적었다 — 답글이 생긴 뒤의
		 * {@code totalElements} 는 최상위 항목의 수라 둘이 다르다
		 * </ul>
		 *
		 * <p><b>엔티티 증가로 되돌리면 빨개진다</b>(실측). 세 트랜잭션이 모두 0 을 읽어
		 * 메모리에서 1 로 올리므로 집계가 1 에서 멈추고 활성 댓글은 3 이다.
		 */
		@Test
		@DisplayName("같은 게시글에 동시 작성 3건이면 comment_count 가 활성 댓글 수와 같다")
		void 동시_작성에서_증가가_소실되지_않는다() throws InterruptedException {
			Post post = 게시글을_만든다(동시_작성_대역);
			모든_스레드를_post_UPDATE_직전에_모은다();

			List<Throwable> 실패 = 동시에_실행한다(i -> this.commentService.create(
					post.getId(), 동시_작성_대역, new CommentCreateRequest("동시 작성 " + i, null)));

			assertThat(실패).as("동시 작성이 실패하면 아래 단언은 소실을 보는 것이 아니다").isEmpty();
			int 활성_댓글 = 활성_댓글_수(post.getId());
			assertThat(활성_댓글)
					.as("동시에 커밋된 댓글이 둘 미만이면 소실이 일어날 자리가 없다")
					.isEqualTo(동시_요청);
			assertThat(커밋된_집계(post.getId()))
					.as("동시 커밋에서 comment_count 증가가 소실되지 않는다 "
							+ "(sp-docs/plan/phase1.md §6 B-10, sp-docs/api-contract.md §4.5)")
					.isEqualTo(활성_댓글);
		}

		/**
		 * 검증표 <b>"같은 게시글에 동시 삭제 N건 → 같음"</b>. 작성과 같은 결함이 삭제에서도
		 * 나므로 따로 본다 — 작성 쪽 테스트는 감소 문장을 보지 않는다.
		 *
		 * <p>픽스처를 서비스로 만들어 집계가 {@code 동시_요청} 에서 시작하게 한다. 그 값을
		 * 먼저 단언하는 것은 시작값이 어긋나면 마지막 단언이 무엇과 비교하는지 알 수 없기
		 * 때문이다.
		 *
		 * <p><b>엔티티 감소로 되돌리면 빨개진다</b>(실측). 세 트랜잭션이 모두 3 을 읽어
		 * 메모리에서 2 로 내리므로 집계가 2 에서 멈추고 활성 댓글은 0 이다.
		 */
		@Test
		@DisplayName("같은 게시글에 동시 삭제 3건이면 comment_count 가 활성 댓글 수와 같다")
		void 동시_삭제에서_감소가_소실되지_않는다() throws InterruptedException {
			Post post = 게시글을_만든다(동시_삭제_대역);
			List<Comment> 지울_댓글 = new ArrayList<>();
			for (int i = 0; i < 동시_요청; i++) {
				지울_댓글.add(서비스로_작성한_댓글(post, 동시_삭제_대역, "지울 댓글 " + i));
			}
			assertThat(커밋된_집계(post.getId()))
					.as("픽스처가 동시 요청 수에서 시작해야 아래 단언이 무엇과 비교하는지 정해진다")
					.isEqualTo(동시_요청);
			모든_스레드를_post_UPDATE_직전에_모은다();

			List<Throwable> 실패 = 동시에_실행한다(
					i -> this.commentService.delete(지울_댓글.get(i).getId(), user(동시_삭제_대역)));

			assertThat(실패).as("동시 삭제가 실패하면 아래 단언은 소실을 보는 것이 아니다").isEmpty();
			int 활성_댓글 = 활성_댓글_수(post.getId());
			assertThat(활성_댓글).as("삭제가 일어나지 않았다면 아래 단언은 아무것도 보지 못한다").isZero();
			assertThat(커밋된_집계(post.getId()))
					.as("동시 커밋에서 comment_count 감소가 소실되지 않는다 "
							+ "(sp-docs/plan/phase1.md §6 B-10)")
					.isEqualTo(활성_댓글);
		}

		/**
		 * 검증표 <b>"발행된 UPDATE 의 모양 → {@code comment_count} 만 싣는다.
		 * {@code title}·{@code content} 가 실리지 않는다"</b> 중 작성 경로다.
		 *
		 * <p><b>빈 목록에서 터지는 것이 수단의 생존 단언이다.</b> 수집기가 꽂히지 않으면
		 * {@code hasSize(1)} 이 0 에서 빨개진다 — 문장을 못 보는 수단이 조용히 통과하지 않는다
		 * (sp-docs/process/review-policy.md §4.1).
		 *
		 * <p>{@code writer_} 로 {@code writer_id}·{@code writer_nickname} 을 함께 본다.
		 */
		@Test
		@DisplayName("작성이 발행한 post UPDATE 는 comment_count 만 싣는다")
		void 작성의_UPDATE_는_좁다() {
			Post post = 게시글을_만든다(작성_문장_대역);
			this.발행된_문장.비운다();

			this.commentService.create(
					post.getId(), 작성_문장_대역, new CommentCreateRequest("문장 모양", null));

			List<String> post_UPDATE = this.발행된_문장.발행된_post_UPDATE();
			assertThat(post_UPDATE)
					.as("수집기가 꽂히지 않았으면 빈 목록이고 아래 단언은 아무것도 보지 못한다")
					.hasSize(1);
			assertThat(post_UPDATE.get(0))
					.as("엔티티 더티 체킹의 전체 컬럼 UPDATE 가 아니다 "
							+ "(sp-docs/plan/phase1.md §6 B-10)")
					.contains("comment_count")
					.doesNotContain("title", "content", "view_count", "writer_");
		}

		/** 같은 검증표 행의 삭제 경로다. 완료 기준 "삭제도 같은 모양이다" 가 여기 있다. */
		@Test
		@DisplayName("삭제가 발행한 post UPDATE 도 comment_count 만 싣는다")
		void 삭제의_UPDATE_도_좁다() {
			Post post = 게시글을_만든다(삭제_문장_대역);
			Comment comment = 서비스로_작성한_댓글(post, 삭제_문장_대역, "문장 모양");
			this.발행된_문장.비운다();

			this.commentService.delete(comment.getId(), user(삭제_문장_대역));

			List<String> post_UPDATE = this.발행된_문장.발행된_post_UPDATE();
			assertThat(post_UPDATE)
					.as("수집기가 꽂히지 않았으면 빈 목록이고 아래 단언은 아무것도 보지 못한다")
					.hasSize(1);
			assertThat(post_UPDATE.get(0))
					.as("삭제도 작성과 같은 좁은 UPDATE 다 (sp-docs/plan/phase1.md §6 B-10)")
					.contains("comment_count")
					.doesNotContain("title", "content", "view_count", "writer_");
		}

		/**
		 * <b>끼워넣기를 강제한다.</b> {@code update post …} 가 발행되기 직전에 스레드를 세워,
		 * {@code 동시_요청} 개가 모두 모인 뒤에야 첫 쓰기가 실행되게 한다.
		 *
		 * <p>한도 안에 모이지 않으면 {@link IllegalStateException} 으로 터진다 — 강제가
		 * 일어나지 않은 측정이 통과하지 않게 한다.
		 */
		private void 모든_스레드를_post_UPDATE_직전에_모은다() {
			CyclicBarrier 집결지 = new CyclicBarrier(동시_요청);
			this.발행된_문장.훅을_건다(sql -> sql.startsWith("update post"), () -> {
				try {
					집결지.await(집결_한도_초, TimeUnit.SECONDS);
				}
				catch (InterruptedException | BrokenBarrierException | TimeoutException ex) {
					throw new IllegalStateException(
							"post UPDATE 직전에 모든 스레드가 모이지 않았다 — 끼워넣기가 강제되지 "
									+ "않았으므로 이 측정은 소실을 볼 수 없다", ex);
				}
			});
		}

		/** 스레드 번호를 받는 작업을 {@code 동시_요청} 개 스레드로 동시에 돌리고 실패를 모은다. */
		private List<Throwable> 동시에_실행한다(IntConsumer 작업) throws InterruptedException {
			ExecutorService 풀 = Executors.newFixedThreadPool(동시_요청);
			try {
				List<Future<?>> 결과 = new ArrayList<>();
				for (int i = 0; i < 동시_요청; i++) {
					int index = i;
					결과.add(풀.submit(() -> 작업.accept(index)));
				}

				List<Throwable> 실패 = new ArrayList<>();
				for (Future<?> future : 결과) {
					try {
						future.get(집결_한도_초 * 3L, TimeUnit.SECONDS);
					}
					catch (ExecutionException ex) {
						실패.add(ex.getCause());
					}
					catch (TimeoutException ex) {
						실패.add(ex);
					}
				}
				return 실패;
			}
			finally {
				풀.shutdownNow();
			}
		}

		private Post 게시글을_만든다(long writerId) {
			return this.postRepository.saveAndFlush(Post.builder()
					.writerId(writerId)
					.writerNickname("글작성자")
					.title("동시 갱신 대상")
					.content("본문")
					.build());
		}

		private Comment 서비스로_작성한_댓글(Post post, long writerId, String content) {
			CommentResponse created = this.commentService.create(
					post.getId(), writerId, new CommentCreateRequest(content, null));
			return this.commentRepository.findById(created.id()).orElseThrow();
		}

		/** JPA 를 거치지 않고 커밋된 값을 읽는다. 다른 스레드가 커밋한 것을 봐야 한다. */
		private int 커밋된_집계(Long postId) {
			Integer count = this.jdbcTemplate.queryForObject(
					"SELECT comment_count FROM post WHERE id = ?", Integer.class, postId);
			return count == null ? 0 : count;
		}

		private int 활성_댓글_수(Long postId) {
			Integer count = this.jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM comment WHERE post_id = ? AND deleted = FALSE",
					Integer.class, postId);
			return count == null ? 0 : count;
		}

	}

	/**
	 * sp-docs/plan/phase2-board.md <b>B-17</b> 의 검증표다. 같은 표의 「집계가 이미 0 인 활성 댓글
	 * 삭제」 와 「같은 댓글 순차 2회 삭제」 는 1차 테스트가 그대로 본다
	 * ({@link CommentServiceTest#집계는_음수가_되지_않는다()},
	 * {@link CommentServiceTest#같은_댓글_두_번_삭제()}).
	 *
	 * <h2>무엇을 고쳤나</h2>
	 *
	 * <p>1차의 삭제는 활성 댓글을 락 없이 읽고, 엔티티의 {@code deleted} 를 더티 체킹으로 조건 없이
	 * 저장한 뒤 {@code comment_count} 를 줄였다. 같은 댓글을 지우는 요청들이 모두 활성으로 읽으면
	 * 모두 줄일 것으로 정본이 추정했다 (sp-docs/domain-model.md §6 의 블록인용, 2026-11-11
	 * {@code D-18}). 고친 모양은 {@code post} 감소 → 댓글의 조건부 전환이고, 전환이 0행이면
	 * 트랜잭션을 되돌린다 (같은 절의 표, §6.2 의 첨부 삭제).
	 *
	 * <h2>고치기 전의 코드에서 잰 값</h2>
	 *
	 * <p>완료 기준 「고치기 전에 검증표 첫 줄이 1차 코드에서 빨개지는 것을 보았다」 의 기록이다.
	 * 고치기 전의 {@code delete} 에 이 클래스를 두 번 돌렸다 — 둘째 실행은 첫 테스트의 단언 순서만
	 * 바꿔 집계를 먼저 보게 했다.
	 * <ul>
	 * <li>같은 댓글의 동시 삭제 3건 — 실패 목록이 비었고(3건 모두 성공, 첫 실행) 집계가 4 에서 1 로
	 * 3 줄었다(둘째 실행). 위 추정이 H2 에서 측정이 됐다
	 * <li>발행 순서 — {@code comment} → {@code post}
	 * <li>댓글 행의 쓰기 — {@code update comment set content=?,deleted=?,parent_id=?,post_id=?,
	 * updated_at=?,writer_id=?,writer_nickname=? where id=?} (더티 체킹)
	 * <li>전환이 0행인 경로 — 다른 트랜잭션이 먼저 지운 댓글의 삭제가 예외 없이 끝났다
	 * <li>경계 관측 — 녹색. 1차도 한 트랜잭션이었다
	 * </ul>
	 *
	 * <h2>고친 뒤의 변이 — 어느 단언이 무엇을 잡나</h2>
	 *
	 * <p>B-17 이 운영 코드를 하나씩 어긋내고 {@code CommentServiceTest} 전체를 돌린 결과다. 잡은
	 * 테스트만 적는다.
	 * <ul>
	 * <li>전환 문장에서 {@code and c.deleted = false} 를 뺀다 — 동시 삭제(집계 3 기대, 1 관측),
	 * 0행 경로(예외 없음), 전환 모양. H2 에서도 조건이 일한다
	 * <li>전환을 감소보다 먼저 둔다 — 순서, 0행 경로(감소가 발행되지 않음). <b>동시 삭제는
	 * 녹색이다</b> — 조건부 전환만으로 이중 감소는 막히므로 순서는 순서 테스트가 따로 본다
	 * <li>전환의 0행을 무시한다 — 동시 삭제(3 기대, 1), 0행 경로(예외 없음)
	 * <li>{@code noRollbackFor = BusinessException.class} (sp-docs/process/review-policy.md §4.1 의
	 * 변이 B) — 동시 삭제(3 기대, 1), 0행 경로(2 기대, 1)
	 * <li>두 쓰기를 각각 자기 트랜잭션으로 (변이 C) — 경계 관측(커밋 2), 동시 삭제, 0행 경로
	 * <li>{@code @Transactional} 제거 (변이 A) — {@code flushAutomatically} 의 flush 가 활성
	 * 트랜잭션을 요구해 삭제마다 예외로 터진다
	 * <li>감소의 0행을 실패로 다룬다 — {@link CommentServiceTest#집계는_음수가_되지_않는다()},
	 * {@link CommentServiceTest#수정과_삭제는_member_를_호출하지_않는다()} (둘 다 집계 0 픽스처)
	 * </ul>
	 *
	 * <h2>끼워넣기를 강제하는 지점 — 그 요청의 첫 쓰기 직전</h2>
	 *
	 * <p>{@link StatementRecorder} 가 {@code update} 문장을 보면 그 스레드를 집결지에 세운다. 고친
	 * 구현의 첫 쓰기는 {@code update post} 이고 1차 구현의 첫 쓰기는 {@code update comment} 다 —
	 * 어느 쪽이든 모두 모인 시점에 모든 읽기가 끝나 있고 아직 아무 쓰기도 없다. 그래서 같은
	 * 테스트를 두 구현에 그대로 돌려 비교할 수 있다. {@link 집계_갱신의_모양과_동시성} 처럼
	 * {@code update post} 에 세우면 1차 구현에서는 댓글 행의 쓰기가 집결 전에 일어나므로 「읽기가
	 * 어느 쓰기보다 먼저」 를 강제하지 못한다.
	 *
	 * <p>픽스처의 {@code writerId} 는 971~979 를 쓴다. 바깥 클래스의 {@code @AfterEach} 는
	 * 801~829 만 지우므로 이 대역은 여기서 지운다. {@code comment.post_id} 에 FK 가 있으므로
	 * 댓글을 먼저 지운다.
	 */
	@Nested
	@DisplayName("댓글 삭제는 post 감소가 먼저이고 댓글 행의 전환이 조건부 문장 하나다")
	@ContextConfiguration(classes = {CommentServiceTest.StatementRecorderConfig.class,
			PhysicalCommitRecorder.Config.class})
	class 삭제의_순서와_조건부_전환 {

		/** 동시 요청 수. 둘 이상이어야 같은 댓글을 여러 요청이 활성으로 읽을 수 있다. */
		private static final int 동시_요청 = 3;

		private static final long 동시_삭제_대역 = 971L;

		private static final long 순서_대역 = 972L;

		private static final long 전환_모양_대역 = 973L;

		private static final long 영행_전환_대역 = 974L;

		private static final long 경계_대역 = 975L;

		/** 집결지·끼워넣은 쓰기를 기다리는 한도. 넘으면 강제가 일어나지 않은 것이므로 터진다. */
		private static final int 대기_한도_초 = 10;

		/** <b>경계를 관측하는 지점이다.</b> 세는 법은 {@link PhysicalCommitRecorder} 다. */
		@Autowired
		private PhysicalCommitRecorder 커밋_기록기;

		@Autowired
		private CommentService commentService;

		@Autowired
		private CommentRepository commentRepository;

		@Autowired
		private PostRepository postRepository;

		/** 바깥 클래스의 {@code @MockitoBean} 이 이 컨텍스트에도 상속된다 (위 중첩 클래스와 같다). */
		@Autowired
		private MemberClient memberClient;

		@Autowired
		private JdbcTemplate jdbcTemplate;

		@Autowired
		private StatementRecorder 발행된_문장;

		@BeforeEach
		void member_응답을_둔다() {
			given(this.memberClient.findActiveNickname(anyLong())).willReturn(MEMBER_NICKNAME);
		}

		@AfterEach
		void 픽스처_정리() {
			this.발행된_문장.비운다();
			this.jdbcTemplate.update("DELETE FROM comment WHERE post_id IN "
					+ "(SELECT id FROM post WHERE writer_id BETWEEN 971 AND 979)");
			this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 971 AND 979");
		}

		/**
		 * 검증표 <b>「같은 댓글의 동시 삭제 N건 — 두 읽기가 어느 쓰기보다 먼저 일어나게 강제한다」
		 * → 성공 1건, 나머지 404 {@code CM001}, {@code comment_count} 가 1 만 준다</b>.
		 *
		 * <p>남는 댓글을 {@code 동시_요청} 개 더 두어 시작 집계를 {@code 동시_요청 + 1} 로 만든다.
		 * 시작 집계가 동시 요청 수 이상이어야 요청마다의 감소가 {@code comment_count > 0} 가드에
		 * 막히지 않고 모두 집계에 드러난다.
		 */
		@Test
		@DisplayName("같은 댓글의 동시 삭제 3건이면 하나만 성공하고 comment_count 가 한 번만 준다")
		void 같은_댓글의_동시_삭제는_집계를_한_번만_줄인다() throws InterruptedException {
			Post post = 게시글을_만든다(동시_삭제_대역);
			Comment 지울_댓글 = 서비스로_작성한_댓글(post, 동시_삭제_대역, "동시에 지울 댓글");
			for (int i = 0; i < 동시_요청; i++) {
				서비스로_작성한_댓글(post, 동시_삭제_대역, "남는 댓글 " + i);
			}
			int 시작_집계 = 커밋된_집계(post.getId());
			assertThat(시작_집계)
					.as("시작 집계가 동시 요청 수 이상이어야 요청마다의 감소가 0 의 가드에 막히지 않는다")
					.isEqualTo(동시_요청 + 1);
			모든_스레드를_첫_쓰기_직전에_모은다();

			List<Throwable> 실패 = 동시에_실행한다(
					i -> this.commentService.delete(지울_댓글.getId(), user(동시_삭제_대역)));

			int 활성_댓글 = 활성_댓글_수(post.getId());
			assertThat(활성_댓글).as("지울 댓글만 지워지고 남는 댓글은 그대로다").isEqualTo(동시_요청);
			int 끝_집계 = 커밋된_집계(post.getId());
			assertThat(끝_집계)
					.as("같은 댓글의 동시 삭제가 comment_count 를 한 번만 줄인다 "
							+ "(sp-docs/domain-model.md §6)")
					.isEqualTo(시작_집계 - 1);
			assertThat(끝_집계).as("집계가 활성 댓글 수와 같다 (sp-docs/domain-model.md §5)")
					.isEqualTo(활성_댓글);
			assertThat(실패)
					.as("하나만 성공하고 나머지는 404 CM001 이다 — 전환이 0행인 요청")
					.hasSize(동시_요청 - 1)
					.allSatisfy(실패한_요청 -> assertThat(실패한_요청)
							.isInstanceOf(BusinessException.class)
							.extracting(e -> ((BusinessException) e).getErrorCode())
							.isEqualTo(ErrorCode.COMMENT_NOT_FOUND));
		}

		/** 검증표 <b>「발행된 문장의 순서」 → {@code update post} 가 {@code update comment} 보다 먼저다</b>. */
		@Test
		@DisplayName("삭제는 post 감소를 댓글 전환보다 먼저 발행한다")
		void post_감소가_댓글_전환보다_먼저다() {
			Post post = 게시글을_만든다(순서_대역);
			Comment comment = 서비스로_작성한_댓글(post, 순서_대역, "순서를 볼 댓글");
			this.발행된_문장.비운다();

			this.commentService.delete(comment.getId(), user(순서_대역));

			assertThat(this.발행된_문장.발행된_UPDATE())
					.as("post 감소 → 댓글 전환 순서다. 거꾸로면 답글 작성(post → 부모 행의 FK 공유 락)과 "
							+ "락 순서가 반대가 된다 (sp-docs/domain-model.md §6 의 블록인용). "
							+ "수집기가 꽂히지 않았으면 빈 목록이다")
					.extracting(StatementRecorder::대상_테이블)
					.containsExactly("post", "comment");
		}

		/**
		 * 완료 기준 <b>「댓글 행의 전환이 조건부 문장 하나다 — 엔티티 더티 체킹의 UPDATE 가
		 * 아니다」</b>.
		 *
		 * <p>더티 체킹의 UPDATE 는 매핑된 컬럼을 모두 싣고 {@code where} 에 {@code id} 만 건다.
		 * 조건부 전환은 {@code deleted} 만 바꾸고 {@code where} 에 {@code deleted} 를 건다 — 판정과
		 * 전환이 한 문장이어야 락 전에 읽은 상태로 쓰지 않는다 (sp-docs/domain-model.md §6 의 표).
		 */
		@Test
		@DisplayName("댓글 행의 전환은 deleted 를 조건으로 거는 UPDATE 하나이고 더티 체킹의 UPDATE 가 아니다")
		void 댓글_전환은_조건부_문장_하나다() {
			Post post = 게시글을_만든다(전환_모양_대역);
			Comment comment = 서비스로_작성한_댓글(post, 전환_모양_대역, "전환을 볼 댓글");
			this.발행된_문장.비운다();

			this.commentService.delete(comment.getId(), user(전환_모양_대역));

			List<String> comment_UPDATE = this.발행된_문장.발행된_UPDATE().stream()
					.filter(sql -> "comment".equals(StatementRecorder.대상_테이블(sql)))
					.toList();
			assertThat(comment_UPDATE)
					.as("댓글 행을 바꾸는 문장은 하나다. 수집기가 꽂히지 않았으면 빈 목록이다")
					.hasSize(1);
			assertThat(comment_UPDATE.get(0))
					.as("엔티티 더티 체킹의 전체 컬럼 UPDATE 가 아니다")
					.doesNotContain("content", "writer_", "updated_at")
					.as("활성일 때만 바꾸는 조건이 그 문장의 where 에 있다")
					.containsPattern(" where .*deleted");
		}

		/**
		 * 검증표 <b>「전환이 0행인 경로」 → 그 요청의 감소가 되돌아간다 — 커밋된
		 * {@code comment_count} 불변</b>. 완료 기준 「전환이 0행이면 트랜잭션이 되돌아가고 404
		 * {@code CM001} 이다」 가 여기 있다.
		 *
		 * <p><b>0행을 실제로 만든다.</b> 이 요청의 첫 쓰기 직전에 다른 트랜잭션이 같은 댓글을 지우고
		 * 커밋한다 — 이 요청은 그 전에 댓글을 활성으로 읽었으므로 404·403 판정을 지나 쓰기에 닿고,
		 * 감소를 발행한 뒤 전환에서 0행을 본다. 스파이로 전환의 반환값만 0 으로 바꾸면 조건이
		 * 문장에 있는지는 보지 않는다.
		 *
		 * <p>시작 집계를 2 로 둔다. 0 이면 감소 자체가 0행이라 되돌릴 것이 없어 마지막 단언이
		 * 반증 불가능해진다.
		 */
		@Test
		@DisplayName("전환이 0행이면 그 요청의 감소가 되돌아가고 404 CM001 이다")
		void 전환이_0행이면_감소가_되돌아간다() {
			Post post = 게시글을_만든다(영행_전환_대역);
			Comment 지울_댓글 = 서비스로_작성한_댓글(post, 영행_전환_대역, "먼저 지워질 댓글");
			서비스로_작성한_댓글(post, 영행_전환_대역, "남는 댓글");
			int 시작_집계 = 커밋된_집계(post.getId());
			assertThat(시작_집계).as("감소가 1행이어야 되돌아가는지 볼 수 있다").isEqualTo(2);
			this.발행된_문장.비운다();
			AtomicInteger 끼워넣은_전환 = new AtomicInteger(-1);
			this.발행된_문장.훅을_건다(sql -> sql.startsWith("update "),
					() -> 끼워넣은_전환.set(다른_트랜잭션에서_지운다(지울_댓글.getId())));

			assertThatThrownBy(() -> this.commentService.delete(지울_댓글.getId(), user(영행_전환_대역)))
					.isInstanceOf(BusinessException.class)
					.extracting(e -> ((BusinessException) e).getErrorCode())
					.isEqualTo(ErrorCode.COMMENT_NOT_FOUND);

			assertThat(끼워넣은_전환.get())
					.as("다른 트랜잭션이 그 댓글을 먼저 지우고 커밋해야 이 요청의 전환이 0행이 된다")
					.isEqualTo(1);
			assertThat(this.발행된_문장.발행된_post_UPDATE())
					.as("이 요청이 감소를 실제로 발행해야 그것이 되돌아가는지 볼 수 있다")
					.hasSize(1);
			assertThat(커밋된_집계(post.getId()))
					.as("그 요청의 감소가 남지 않는다 — 커밋된 comment_count 불변")
					.isEqualTo(시작_집계);
		}

		/**
		 * 완료 기준 「한 로컬 트랜잭션이 1차 그대로다」 의 경계 관측이다
		 * (sp-docs/process/review-policy.md §4.1 — 완료 기준에 한 로컬 트랜잭션이 있으면 경계를
		 * 관측한다). 세는 법은 {@link PhysicalCommitRecorder} 다.
		 */
		@Test
		@DisplayName("한 번의 삭제에 물리 트랜잭션이 정확히 하나 커밋된다")
		void 삭제의_커밋이_정확히_한_번이다() {
			Post post = 게시글을_만든다(경계_대역);
			Comment comment = 서비스로_작성한_댓글(post, 경계_대역, "경계를 볼 댓글");

			// 준비가 끝난 뒤에 센다. 세는 구간을 delete 한 번으로 좁혀 둔다
			List<String> 커밋된_물리_트랜잭션 = this.커밋_기록기.물리_커밋(
					() -> this.commentService.delete(comment.getId(), user(경계_대역)));

			assertThat(활성_댓글_수(post.getId()))
					.as("삭제가 일어나지 않았다면 커밋 수는 아무것도 말하지 않는다")
					.isZero();
			assertThat(커밋된_물리_트랜잭션)
					.as("post 감소와 댓글 전환이 한 로컬 트랜잭션이다 (sp-docs/domain-model.md §6)")
					.hasSize(1);
		}

		/**
		 * <b>끼워넣기를 강제한다.</b> 요청마다 첫 {@code update} 가 발행되기 직전에 스레드를 세워,
		 * {@code 동시_요청} 개가 모두 모인 뒤에야 첫 쓰기가 실행되게 한다. 한도 안에 모이지 않으면
		 * {@link IllegalStateException} 으로 터진다.
		 */
		private void 모든_스레드를_첫_쓰기_직전에_모은다() {
			CyclicBarrier 집결지 = new CyclicBarrier(동시_요청);
			this.발행된_문장.훅을_건다(sql -> sql.startsWith("update "), () -> {
				try {
					집결지.await(대기_한도_초, TimeUnit.SECONDS);
				}
				catch (InterruptedException | BrokenBarrierException | TimeoutException ex) {
					throw new IllegalStateException(
							"첫 쓰기 직전에 모든 스레드가 모이지 않았다 — 읽기가 어느 쓰기보다 먼저라는 "
									+ "끼워넣기가 강제되지 않았으므로 이 측정은 이중 감소를 볼 수 없다", ex);
				}
			});
		}

		/**
		 * 다른 커넥션의 자동 커밋으로 댓글을 지우고 영향 행 수를 돌려준다. <b>다른 스레드에서
		 * 돌린다</b> — 부르는 스레드에는 서비스의 트랜잭션이 묶여 있어 같은 스레드의
		 * {@code JdbcTemplate} 은 그 트랜잭션에 참여한다.
		 */
		private int 다른_트랜잭션에서_지운다(Long commentId) {
			ExecutorService 다른_스레드 = Executors.newSingleThreadExecutor();
			try {
				return 다른_스레드.submit(() -> this.jdbcTemplate.update(
								"UPDATE comment SET deleted = TRUE WHERE id = ? AND deleted = FALSE",
								commentId))
						.get(대기_한도_초, TimeUnit.SECONDS);
			}
			catch (InterruptedException | ExecutionException | TimeoutException ex) {
				throw new IllegalStateException(
						"끼워넣은 삭제가 커밋되지 않았다 — 이 측정은 0행 전환을 볼 수 없다", ex);
			}
			finally {
				다른_스레드.shutdownNow();
			}
		}

		/** 스레드 번호를 받는 작업을 {@code 동시_요청} 개 스레드로 동시에 돌리고 실패를 모은다. */
		private List<Throwable> 동시에_실행한다(IntConsumer 작업) throws InterruptedException {
			ExecutorService 풀 = Executors.newFixedThreadPool(동시_요청);
			try {
				List<Future<?>> 결과 = new ArrayList<>();
				for (int i = 0; i < 동시_요청; i++) {
					int index = i;
					결과.add(풀.submit(() -> 작업.accept(index)));
				}

				List<Throwable> 실패 = new ArrayList<>();
				for (Future<?> future : 결과) {
					try {
						future.get(대기_한도_초 * 3L, TimeUnit.SECONDS);
					}
					catch (ExecutionException ex) {
						실패.add(ex.getCause());
					}
					catch (TimeoutException ex) {
						실패.add(ex);
					}
				}
				return 실패;
			}
			finally {
				풀.shutdownNow();
			}
		}

		private Post 게시글을_만든다(long writerId) {
			return this.postRepository.saveAndFlush(Post.builder()
					.writerId(writerId)
					.writerNickname("글작성자")
					.title("삭제 순서 대상")
					.content("본문")
					.build());
		}

		private Comment 서비스로_작성한_댓글(Post post, long writerId, String content) {
			CommentResponse created = this.commentService.create(
					post.getId(), writerId, new CommentCreateRequest(content, null));
			return this.commentRepository.findById(created.id()).orElseThrow();
		}

		/** JPA 를 거치지 않고 커밋된 값을 읽는다. 다른 스레드가 커밋한 것을 봐야 한다. */
		private int 커밋된_집계(Long postId) {
			Integer count = this.jdbcTemplate.queryForObject(
					"SELECT comment_count FROM post WHERE id = ?", Integer.class, postId);
			return count == null ? 0 : count;
		}

		private int 활성_댓글_수(Long postId) {
			Integer count = this.jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM comment WHERE post_id = ? AND deleted = FALSE",
					Integer.class, postId);
			return count == null ? 0 : count;
		}

	}

	/**
	 * sp-docs/plan/phase2-board.md <b>B-22</b>(답글, C-05) 의 검증표 중 쓰기 순서·목록·집계 쪽이다.
	 * HTTP 상태·에러 코드·응답 키 집합과 삭제된 최상위 자리의 JSON 모양은 {@code CommentControllerTest}
	 * 의 B-22 중첩 클래스가, 글 삭제의 연쇄는 {@code CommentRepositoryTest} 가 본다.
	 *
	 * <h2>창에 끼워 넣는 측정 — H2 에서 본 것과 넘긴 것</h2>
	 *
	 * <p>같은 부모에 대한 동시 답글과, 답글의 부모 확인 뒤에 커밋되는 부모 삭제를 H2 에서 끼워 넣어
	 * 잰다. 실 MySQL 의 측정은 sp-docs/plan/phase2-frontend-integration.md 의 I-07 이다 —
	 * sp-docs/plan/phase2-board.md §4.1 의 「동시 답글, 같은 부모에 대한 답글 작성과 부모 삭제 —
	 * 교착과 집계」 줄이 넘겼다. <b>교착은 여기서 재지 않는다.</b> sp-docs/domain-model.md §6 의
	 * 블록인용은 「답글 작성({@code post} → 부모 행의 FK 공유 락)」 과 반대 순서가 교착할 것으로
	 * 보았다(추정). H2 가 FK 검사에 같은 락을 거는지 확인하지 않았으므로, 여기서 교착이 나지 않는
	 * 것은 MySQL 에 대해 말해 주는 것이 없다.
	 *
	 * <p>픽스처 글의 {@code writerId} 는 1311~1339 를 쓴다. 2026-11-17 에 테스트 소스 전체에서 그
	 * 대역을 쓰는 곳이 없는 것을 확인하고 골랐다. 바깥 클래스의 {@code @AfterEach} 가 지우는 대역이
	 * 아니므로 여기서 지운다. 답글이 부모를 참조하므로({@code fk_comment_parent_id}) 그 참조를 먼저
	 * 끊고, 댓글이 글을 참조하므로 댓글을 먼저 지운다. <b>답글부터 지우는 순서로 두지 않는다</b> — 그
	 * 순서는 깊이가 1 이라는 것에 기대야 FK 를 지난다. 깊이 판정을 뺀 변이에서 그 순서의 정리가 FK
	 * 위반으로 멈춰, 남은 행이 다른 테스트 클래스까지 빨갛게 만들었다 (2026-11-17 B-22 실측,
	 * {@code CommentControllerTest} 의 B-22 중첩 클래스의 정리).
	 */
	@Nested
	@DisplayName("답글 — 쓰기 순서, 최상위 페이징과 답글, 삭제된 최상위의 자리, 창에 끼운 삭제")
	@ContextConfiguration(classes = CommentServiceTest.StatementRecorderConfig.class)
	class 답글 {

		/** 댓글·답글을 쓰는 계정. 글의 {@code writerId} 가 대역이고 이 값은 정리 조건이 아니다. */
		private static final long 댓글_작성자 = 1339L;

		private static final long 발행_순서_대역 = 1311L;

		private static final long 페이징_대역 = 1312L;

		private static final long 자리_건수_대역 = 1313L;

		private static final long 자리_소멸_대역 = 1314L;

		private static final long 답글_없는_삭제_대역 = 1315L;

		private static final long 지운_답글_대역 = 1316L;

		private static final long 동률_대역 = 1317L;

		private static final long 쿼리_수_대역 = 1318L;

		private static final long 동시_답글_대역 = 1319L;

		private static final long 창_대역 = 1320L;

		private static final long 다른_글_대역 = 1321L;

		/** 동시 요청 수. 둘 이상이어야 읽기들이 모두 쓰기보다 먼저인 끼워넣기가 생긴다. */
		private static final int 동시_요청 = 3;

		/** 집결지·끼워 넣은 삭제를 기다리는 한도. 넘으면 강제가 일어나지 않은 것이므로 터진다. */
		private static final int 대기_한도_초 = 10;

		@Autowired
		private CommentService commentService;

		@Autowired
		private CommentRepository commentRepository;

		@Autowired
		private PostRepository postRepository;

		/**
		 * 바깥 클래스의 {@code @MockitoBean} 이 이 컨텍스트에도 상속된다. 바깥 인스턴스의 필드가 아니라
		 * 이것에 스텁을 거는 이유는 {@link 작성_트랜잭션_경계} 의 같은 필드에 있다.
		 */
		@Autowired
		private MemberClient memberClient;

		@Autowired
		private JdbcTemplate jdbcTemplate;

		@Autowired
		private StatementRecorder 발행된_문장;

		@BeforeEach
		void member_응답을_둔다() {
			given(this.memberClient.findActiveNickname(anyLong())).willReturn(MEMBER_NICKNAME);
		}

		@AfterEach
		void 픽스처_정리() {
			this.발행된_문장.비운다();
			this.jdbcTemplate.update("UPDATE comment SET parent_id = NULL WHERE post_id IN "
					+ "(SELECT id FROM post WHERE writer_id BETWEEN 1311 AND 1339)");
			this.jdbcTemplate.update("DELETE FROM comment WHERE post_id IN "
					+ "(SELECT id FROM post WHERE writer_id BETWEEN 1311 AND 1339)");
			this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 1311 AND 1339");
		}

		// --- 작성 ---

		/**
		 * 검증표 <b>「발행 순서」 → {@code update post} 가 {@code insert into comment} 보다 먼저다</b>.
		 * 완료 기준 「답글 작성도 {@code comment_count} +1 이고 {@code post} 의 좁은 UPDATE 를 INSERT
		 * 보다 먼저 한다 …」 의 순서 쪽이다 — +1 은 {@code CommentControllerTest} 의 답글 작성이 본다.
		 *
		 * <p>쓰기 문장의 목록을 통째로 대조하므로 다른 쓰기가 끼면 빨개진다 — 부모를 엔티티로 고치는
		 * 구현이면 더티 체킹의 {@code update comment} 가 붙는다. 수집기가 꽂히지 않았으면 빈 목록에서
		 * 빨개진다.
		 */
		@Test
		@DisplayName("답글 작성은 post UPDATE 를 댓글 INSERT 보다 먼저 발행하고 다른 쓰기를 내지 않는다")
		void 답글_작성은_post_UPDATE_가_먼저다() {
			Post post = 게시글을_만든다(발행_순서_대역);
			Comment 부모 = 서비스로_작성한_댓글(post, "부모");
			this.발행된_문장.비운다();

			this.commentService.create(post.getId(), 댓글_작성자,
					new CommentCreateRequest("순서를 볼 답글", 부모.getId()));

			assertThat(this.발행된_문장.발행된_쓰기())
					.as("post 의 좁은 UPDATE 가 INSERT 보다 먼저다 (sp-docs/plan/phase2-board.md B-22). "
							+ "수집기가 꽂히지 않았으면 빈 목록이다")
					.extracting(StatementRecorder::쓰기의_머리)
					.containsExactly("update post", "insert into comment");
		}

		// --- 목록 ---

		/**
		 * 검증표 <b>「최상위 25개와 각 답글」 → 첫 페이지 최상위 20, {@code totalElements} 25, 답글
		 * 전부</b>. 페이징의 단위가 최상위 항목이고 답글은 {@code size} 에 세지 않는다
		 * (sp-docs/api-contract.md §4.5). 첫 페이지가 꽉 차므로 {@code totalElements} 단언이 건수
		 * 쿼리를 본다 (sp-docs/process/review-policy.md §4.1 의 {@code PageImpl} 블록인용).
		 *
		 * <p><b>답글 수를 최상위마다 다르게 두고, 답글의 시각을 등록의 역순으로 박는다.</b> 답글이
		 * 엉뚱한 최상위에 붙거나 일부만 실리면 최상위별 목록이 어긋나고, 답글을 {@code id} 로만
		 * 정렬하면 등록순({@code created_at ASC, id ASC})과 갈린다. 둘째 페이지의 최상위에도 답글이
		 * 있다.
		 */
		@Test
		@DisplayName("최상위 25개면 첫 페이지에 최상위 20개와 그 답글 전부가 등록순으로 실리고 totalElements 는 25 다")
		void 최상위를_페이징하고_답글을_전부_싣는다() {
			Post post = 게시글을_만든다(페이징_대역);
			Map<Long, List<Long>> 최상위별_기대_답글 = new LinkedHashMap<>();
			for (int i = 0; i < 25; i++) {
				Comment 최상위 = 댓글을_둔다(post, "최상위 " + i);
				LocalDateTime 최상위_시각 = FIXED_TIME.plusMinutes(i);
				setCreatedAt(최상위.getId(), 최상위_시각);
				int 답글_수 = 1 + i % 3;
				List<Long> 기대_답글 = new ArrayList<>();
				for (int k = 0; k < 답글_수; k++) {
					Comment 답 = 답글을_둔다(post, 최상위, "답글 " + i + "-" + k);
					// 나중에 둔 답글일수록 이른 시각이다. 그래서 기대 순서는 둔 순서의 역순이다
					setCreatedAt(답.getId(), 최상위_시각.plusSeconds(답글_수 - k));
					기대_답글.add(0, 답.getId());
				}
				최상위별_기대_답글.put(최상위.getId(), 기대_답글);
			}

			PageResponse<TopLevelCommentResponse> page = this.commentService.getList(
					post.getId(), CommentPageCondition.of(null, null));

			assertThat(page.content()).extracting(TopLevelCommentResponse::id)
					.as("페이징 단위는 최상위 항목이고 기본 크기는 20 이다 (sp-docs/api-contract.md §4.5)")
					.containsExactlyElementsOf(
							new ArrayList<>(최상위별_기대_답글.keySet()).subList(0, 20));
			assertThat(page.totalElements())
					.as("totalElements 는 최상위 항목의 수다 (sp-docs/api-contract.md §4.5)")
					.isEqualTo(25);
			assertThat(page.totalPages()).isEqualTo(2);
			assertThat(page.content())
					.as("각 최상위 아래에 활성 답글이 전부 등록순으로 실린다 "
							+ "(sp-docs/requirements/board.md §12)")
					.allSatisfy(item -> assertThat(item.replies())
							.extracting(CommentResponse::id)
							.containsExactlyElementsOf(최상위별_기대_답글.get(item.id())));
		}

		/**
		 * 검증표 <b>「활성 최상위 2·활성 답글이 있는 삭제된 최상위 1·답글 없는 삭제된 최상위 1,
		 * {@code size=2} 의 첫 페이지」 → {@code totalElements} 3</b> — 페이지가 꽉 차 건수 쿼리를
		 * 본다 (sp-docs/process/review-policy.md §4.1 의 {@code PageImpl} 블록인용).
		 *
		 * <p><b>3 이 다른 셈과 겹치지 않게 둔다.</b> 이 글에서 활성 최상위만 세면 2, 최상위를 모두
		 * 세면 4, 답글을 포함한 활성 댓글을 세면({@code comment_count} 의 셈) 5 다 — 삭제된 최상위에
		 * 답글을 둘, 활성 최상위 하나에 답글을 하나 둔 까닭이다. 다른 글에도 최상위와 답글을 둔다 —
		 * 건수의 글 조건이 빠지면 그것까지 센다.
		 */
		@Test
		@DisplayName("활성 답글이 있는 삭제된 최상위는 자리로 남아 건수에 들고 답글 없는 삭제된 최상위는 빠진다")
		void 활성_답글이_있는_삭제된_최상위만_자리로_센다() {
			자리_픽스처 픽스처 = 자리_픽스처를_둔다(자리_건수_대역);

			PageResponse<TopLevelCommentResponse> 첫_페이지 = this.commentService.getList(
					픽스처.post().getId(), CommentPageCondition.of(0, 2));

			assertThat(첫_페이지.content()).extracting(TopLevelCommentResponse::id)
					.as("자리는 그 댓글의 등록순 자리에 온다")
					.containsExactly(픽스처.첫_활성().getId(), 픽스처.답글_있는_삭제().getId());
			assertThat(첫_페이지.totalElements())
					.as("최상위 항목 — 활성 최상위와 활성 답글이 있는 삭제된 최상위 (sp-docs/api-contract.md §4.5)")
					.isEqualTo(3);
			assertThat(첫_페이지.totalPages()).isEqualTo(2);
		}

		/**
		 * 검증표 <b>「위에서 그 삭제된 최상위의 답글까지 삭제, 같은 {@code size}」 → 자리가 사라지고
		 * {@code totalElements} 2</b>.
		 *
		 * <p>그 최상위에 답글이 둘이다. 하나를 지운 뒤에는 활성 답글이 남아 있으므로 자리가 그대로이고,
		 * 둘 다 지운 뒤에 사라진다 (sp-docs/requirements/board.md §12 — 「활성 답글이 없어지면 그 자리도
		 * 사라진다」). 마지막 페이지도 꽉 차므로 건수 쿼리를 본다.
		 */
		@Test
		@DisplayName("자리의 활성 답글이 하나라도 남으면 자리가 남고 모두 지우면 자리가 사라져 totalElements 가 2 다")
		void 자리의_답글을_모두_지우면_자리가_사라진다() {
			자리_픽스처 픽스처 = 자리_픽스처를_둔다(자리_소멸_대역);
			Long postId = 픽스처.post().getId();

			지운다(픽스처.첫_답글());
			PageResponse<TopLevelCommentResponse> 하나_남음 = this.commentService.getList(
					postId, CommentPageCondition.of(0, 2));
			지운다(픽스처.둘째_답글());
			PageResponse<TopLevelCommentResponse> 모두_지움 = this.commentService.getList(
					postId, CommentPageCondition.of(0, 2));

			assertThat(하나_남음.content()).extracting(TopLevelCommentResponse::id)
					.as("활성 답글이 하나라도 남아 있으면 자리가 남는다")
					.containsExactly(픽스처.첫_활성().getId(), 픽스처.답글_있는_삭제().getId());
			assertThat(하나_남음.totalElements()).isEqualTo(3);
			assertThat(모두_지움.content()).extracting(TopLevelCommentResponse::id)
					.as("활성 답글이 없어지면 자리도 사라진다 (sp-docs/requirements/board.md §12)")
					.containsExactly(픽스처.첫_활성().getId(), 픽스처.둘째_활성().getId());
			assertThat(모두_지움.totalElements()).isEqualTo(2);
		}

		/**
		 * 검증표 <b>「답글 없는 최상위 삭제」 → 목록에서 사라진다</b>.
		 *
		 * <p>같은 글의 다른 최상위에 활성 답글을 둔다. 자리를 남기는 조건이 그 최상위 <b>자신의</b>
		 * 답글이 아니라 글의 답글을 보면 지운 최상위가 자리로 남는다.
		 */
		@Test
		@DisplayName("답글이 없는 최상위를 지우면 목록에서 사라진다 — 같은 글의 다른 최상위에 답글이 있어도")
		void 답글_없는_최상위를_지우면_목록에서_사라진다() {
			Post post = 게시글을_만든다(답글_없는_삭제_대역);
			Comment 답글_있는_최상위 = 서비스로_작성한_댓글(post, "답글이 있는 최상위");
			서비스로_작성한_답글(post, 답글_있는_최상위, "다른 최상위의 답글");
			Comment 지울_최상위 = 서비스로_작성한_댓글(post, "답글이 없는 최상위");

			지운다(지울_최상위);

			assertThat(목록(post)).extracting(TopLevelCommentResponse::id)
					.as("활성 답글이 없는 삭제된 최상위는 자리로도 남지 않는다 "
							+ "(sp-docs/requirements/board.md §12)")
					.containsExactly(답글_있는_최상위.getId());
		}

		/**
		 * 검증표 <b>「지운 답글」 → 싣지 않는다</b>. 지운 그 하나만 {@code comment_count} −1 인 것을
		 * 함께 본다 — 답글도 댓글이다 (sp-docs/domain-model.md §5).
		 */
		@Test
		@DisplayName("지운 답글은 목록에 실리지 않고 comment_count 는 그 하나만 준다")
		void 지운_답글은_싣지_않는다() {
			Post post = 게시글을_만든다(지운_답글_대역);
			Comment 최상위 = 서비스로_작성한_댓글(post, "최상위");
			Comment 첫째 = 서비스로_작성한_답글(post, 최상위, "첫째 답글");
			Comment 둘째 = 서비스로_작성한_답글(post, 최상위, "둘째 답글");
			Comment 셋째 = 서비스로_작성한_답글(post, 최상위, "셋째 답글");
			assertThat(커밋된_집계(post.getId()))
					.as("답글 작성도 +1 이어야 삭제의 −1 이 무엇과 비교하는지 정해진다")
					.isEqualTo(4);

			지운다(둘째);

			assertThat(목록(post)).singleElement()
					.satisfies(item -> assertThat(item.replies())
							.extracting(CommentResponse::id)
							.as("지운 답글은 목록에 싣지 않는다 (sp-docs/api-contract.md §4.5)")
							.containsExactly(첫째.getId(), 셋째.getId()));
			assertThat(커밋된_집계(post.getId()))
					.as("지운 그 하나만 −1 (sp-docs/domain-model.md §5)")
					.isEqualTo(3);
		}

		/**
		 * 검증표 <b>「같은 {@code created_at} 의 최상위·답글」 → {@code id} 로 순서가 정해진다</b>
		 * (sp-docs/api-contract.md §4.5 — 「최상위와 답글 모두 {@code created_at ASC, id ASC} 다」).
		 *
		 * <p>최상위와 답글의 시각을 모두 같게 박는다. 1차 키가 전부 동률이므로 남는 순서는 마지막
		 * 정렬 키뿐이다. 두 최상위에 답글을 번갈아 두어 부모별로 나눈 뒤의 순서도 본다.
		 *
		 * <p><b>이 테스트는 두 조회의 {@code id DESC} 를 잡지만 {@code id} 를 뺀 구현은 잡지 못한다</b>
		 * (2026-11-17 B-22 실측 — 사본에서 두 조회의 마지막 정렬 키를 하나씩 바꿔 전체 스위트를
		 * 돌렸다). H2 는 동률이 한 페이지에 다 들어가는 조회에서 {@code id} 오름차순으로 돌려줬고,
		 * 페이지가 동률 사이를 자르는 조회에서는 그러지 않았다 — 이유는 재지 않았다. 그래서 최상위
		 * 조회에서 뺀 변이는 페이지가 동률을 가르는 테스트들
		 * ({@link #활성_답글이_있는_삭제된_최상위만_자리로_센다()},
		 * {@link #자리의_답글을_모두_지우면_자리가_사라진다()},
		 * {@link CommentServiceTest#created_at_동률의_페이지_경계()}) 이 잡았다. <b>답글 조회에서 뺀 변이는
		 * 전체 스위트가 녹색이었다</b> — 답글 조회는 페이징하지 않는다.
		 */
		@Test
		@DisplayName("created_at 이 같으면 최상위도 답글도 id 오름차순이다")
		void 같은_created_at_이면_최상위도_답글도_id_순이다() {
			Post post = 게시글을_만든다(동률_대역);
			List<Long> 최상위 = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				최상위.add(댓글을_둔다(post, "동률 최상위 " + i).getId());
			}
			Comment 첫_최상위 = this.commentRepository.findById(최상위.get(0)).orElseThrow();
			Comment 둘째_최상위 = this.commentRepository.findById(최상위.get(1)).orElseThrow();
			List<Long> 첫_최상위의_답글 = new ArrayList<>();
			List<Long> 둘째_최상위의_답글 = new ArrayList<>();
			for (int k = 0; k < 3; k++) {
				첫_최상위의_답글.add(답글을_둔다(post, 첫_최상위, "동률 답글 " + k).getId());
				둘째_최상위의_답글.add(답글을_둔다(post, 둘째_최상위, "동률 답글 " + k).getId());
			}
			this.jdbcTemplate.update("UPDATE comment SET created_at = ? WHERE post_id = ?",
					FIXED_TIME, post.getId());

			List<TopLevelCommentResponse> 목록 = 목록(post);

			assertThat(목록).extracting(TopLevelCommentResponse::id)
					.as("최상위의 1차 키가 동률이면 id ASC 다")
					.containsExactlyElementsOf(최상위);
			assertThat(목록.get(0).replies()).extracting(CommentResponse::id)
					.as("답글의 1차 키가 동률이면 id ASC 다")
					.containsExactlyElementsOf(첫_최상위의_답글);
			assertThat(목록.get(1).replies()).extracting(CommentResponse::id)
					.containsExactlyElementsOf(둘째_최상위의_답글);
			assertThat(목록.get(2).replies()).isEmpty();
		}

		/**
		 * 검증표 <b>「목록 쿼리 수」 → 최상위·답글 수를 바꾼 두 측정이 같다</b>. 완료 기준 「목록의
		 * 쿼리 수가 최상위 항목 수·답글 수와 무관하다 — 행 수만 바꾼 두 측정이 같은지 본다 …」 이다.
		 * 단언은 행 수가 실제로 달라졌는지, 수집기가 살아 있는지, 두 집계가 같은지를 본다 — 앞의 둘이
		 * 없으면 마지막 단언이 아무것도 보지 못한다.
		 *
		 * <p><b>두 측정 모두 첫 페이지가 꽉 차지 않게 둔다.</b> 꽉 차면 건수 쿼리가 더 나가므로 한쪽만
		 * 꽉 차면 행 수와 무관한 이유로 두 집계가 갈린다. 크기를 같게 둔 채 둘 다 꽉 채우면 페이지의
		 * 최상위 수가 같아져 최상위마다 내는 쿼리를 볼 수 없다. <b>삭제된 최상위의 자리 수도 두 측정이
		 * 다르다</b> — 자리마다 쿼리를 내는 구현도 갈린다.
		 */
		@Test
		@DisplayName("목록의 statement 수가 최상위 항목 수·답글 수와 무관하게 같다")
		void 목록의_쿼리_수가_최상위와_답글_수와_무관하다() {
			Post 적은_쪽 = 게시글을_만든다(쿼리_수_대역);
			Post 많은_쪽 = 게시글을_만든다(쿼리_수_대역);
			자리와_최상위들을_둔다(적은_쪽, 2, 1, 1);
			자리와_최상위들을_둔다(많은_쪽, 5, 2, 3);

			목록_측정 적게 = 목록을_잰다(적은_쪽);
			목록_측정 많이 = 목록을_잰다(많은_쪽);

			assertThat(적게.최상위()).as("행 수가 달라지지 않으면 집계가 같은 것이 아무것도 뜻하지 않는다")
					.isEqualTo(2);
			assertThat(적게.자리()).isEqualTo(1);
			assertThat(적게.답글()).isEqualTo(2);
			assertThat(많이.최상위()).isEqualTo(5);
			assertThat(많이.자리()).isEqualTo(2);
			assertThat(많이.답글()).isEqualTo(15);
			assertThat(적게.문장())
					.as("수집기가 꽂히지 않았으면 두 집계가 모두 0 이고 같다는 단언이 무의미해진다")
					.isPositive();
			assertThat(많이.문장())
					.as("최상위·답글이 늘었는데 statement 가 늘면 항목마다 쿼리를 낸 것이다 "
							+ "(sp-docs/plan/phase2-board.md B-22)")
					.isEqualTo(적게.문장());
		}

		// --- 창에 끼워 넣는 측정 ---

		/**
		 * 같은 부모에 동시 답글 {@code 동시_요청} 건 — 모든 읽기(글·부모)가 어느 쓰기보다 먼저 일어나게
		 * {@code update post} 직전에 모은다. 모두 성공하고 {@code comment_count} 가 답글을 포함한 활성
		 * 댓글 수와 같다 (sp-docs/domain-model.md §5·§6). 실 MySQL 의 측정은
		 * sp-docs/plan/phase2-frontend-integration.md 의 I-07 이다.
		 */
		@Test
		@DisplayName("같은 부모에 동시 답글을 달면 모두 성공하고 comment_count 가 활성 댓글 수와 같다")
		void 같은_부모에_동시_답글() throws InterruptedException {
			Post post = 게시글을_만든다(동시_답글_대역);
			Comment 부모 = 서비스로_작성한_댓글(post, "동시 답글의 부모");
			모든_스레드를_post_UPDATE_직전에_모은다();

			List<Throwable> 실패 = 동시에_실행한다(i -> this.commentService.create(post.getId(),
					댓글_작성자, new CommentCreateRequest("동시 답글 " + i, 부모.getId())));

			assertThat(실패).as("동시 답글이 실패하면 집계 단언은 동시 작성을 보는 것이 아니다").isEmpty();
			assertThat(답글_수(부모.getId())).isEqualTo(동시_요청);
			int 활성_댓글 = 활성_댓글_수(post.getId());
			assertThat(활성_댓글).isEqualTo(동시_요청 + 1);
			assertThat(커밋된_집계(post.getId()))
					.as("comment_count 는 답글을 포함한 활성 댓글 수다 (sp-docs/domain-model.md §5)")
					.isEqualTo(활성_댓글);
		}

		/**
		 * 완료 기준 <b>「부모 확인과 커밋 사이의 창은 닫지 않는다」</b>. 답글이 부모를 활성으로 확인한
		 * 뒤, 첫 쓰기 직전에 다른 트랜잭션이 그 부모를 지우고 커밋한다. 답글은 생기고, 부모는 지워졌지만
		 * 활성 답글이 있어 목록에 자리로 남는다 — sp-docs/requirements/board.md §12 가 「삭제된 댓글에
		 * 답글 불가」 를 보장이 아니라 통상 동작으로 둔 결과다. 집계는 답글을 포함한 활성 댓글 수와 같다.
		 *
		 * <p>끼우는 삭제는 실제 삭제 경로({@code CommentService.delete})다. 그 삭제도 {@code update
		 * post} 를 내므로 훅이 그 스레드에서도 불린다 — 답글을 쓰는 스레드에서만 끼운다.
		 *
		 * <p><b>부모 확인을 집계 증가 뒤로 옮기면 이 테스트가 빨개진다</b> — 사본에서 그렇게 옮기자
		 * 답글이 404 {@code CM001} 로 끝났다 (2026-11-17 B-22 실측). 그래서 이 테스트는 확인이 첫
		 * 쓰기보다 앞이라는 것도 지킨다 — 그 순서의 이유는 {@code CommentService.create} 가 적는다.
		 */
		@Test
		@DisplayName("부모 확인 뒤 커밋 전에 부모가 지워지면 답글이 생기고 부모는 자리로 남으며 집계가 맞다")
		void 부모_확인_뒤에_지워진_부모_아래에_답글이_생긴다() {
			Post post = 게시글을_만든다(창_대역);
			Comment 부모 = 서비스로_작성한_댓글(post, "곧 지워질 부모");
			Thread 답글_스레드 = Thread.currentThread();
			AtomicBoolean 끼운_삭제가_끝났다 = new AtomicBoolean();
			this.발행된_문장.훅을_건다(sql -> sql.startsWith("update post"), () -> {
				if (Thread.currentThread() == 답글_스레드) {
					다른_스레드에서_지운다(부모);
					끼운_삭제가_끝났다.set(true);
				}
			});

			CommentResponse 답 = this.commentService.create(post.getId(), 댓글_작성자,
					new CommentCreateRequest("창 안의 답글", 부모.getId()));

			assertThat(끼운_삭제가_끝났다)
					.as("부모 삭제가 답글의 부모 확인 뒤, 첫 쓰기 전에 커밋돼야 이 측정이 창을 본다")
					.isTrue();
			assertThat(삭제됐다(부모.getId())).as("끼운 삭제가 커밋됐다").isTrue();
			assertThat(답.parentId()).isEqualTo(부모.getId());
			int 활성_댓글 = 활성_댓글_수(post.getId());
			assertThat(활성_댓글).as("활성 댓글은 그 답글 하나다").isEqualTo(1);
			assertThat(커밋된_집계(post.getId()))
					.as("부모의 −1 과 답글의 +1 이 둘 다 남는다 (sp-docs/domain-model.md §5)")
					.isEqualTo(활성_댓글);
			assertThat(목록(post)).singleElement()
					.satisfies(item -> {
						assertThat(item.id()).isEqualTo(부모.getId());
						assertThat(item.deleted()).as("지운 부모는 자리로 남는다").isTrue();
						assertThat(item.replies()).extracting(CommentResponse::id)
								.containsExactly(답.id());
					});
		}

		/**
		 * {@code 동시_요청} 개 스레드를 {@code update post} 가 발행되기 직전에 모은다. 한도 안에 모이지
		 * 않으면 {@link IllegalStateException} 으로 터진다 — 강제가 일어나지 않은 측정이 통과하지 않게
		 * 한다.
		 */
		private void 모든_스레드를_post_UPDATE_직전에_모은다() {
			CyclicBarrier 집결지 = new CyclicBarrier(동시_요청);
			this.발행된_문장.훅을_건다(sql -> sql.startsWith("update post"), () -> {
				try {
					집결지.await(대기_한도_초, TimeUnit.SECONDS);
				}
				catch (InterruptedException | BrokenBarrierException | TimeoutException ex) {
					throw new IllegalStateException(
							"post UPDATE 직전에 모든 스레드가 모이지 않았다 — 읽기가 모두 쓰기보다 먼저라는 "
									+ "끼워넣기가 강제되지 않았다", ex);
				}
			});
		}

		/** 스레드 번호를 받는 작업을 {@code 동시_요청} 개 스레드로 동시에 돌리고 실패를 모은다. */
		private List<Throwable> 동시에_실행한다(IntConsumer 작업) throws InterruptedException {
			ExecutorService 풀 = Executors.newFixedThreadPool(동시_요청);
			try {
				List<Future<?>> 결과 = new ArrayList<>();
				for (int i = 0; i < 동시_요청; i++) {
					int index = i;
					결과.add(풀.submit(() -> 작업.accept(index)));
				}

				List<Throwable> 실패 = new ArrayList<>();
				for (Future<?> future : 결과) {
					try {
						future.get(대기_한도_초 * 3L, TimeUnit.SECONDS);
					}
					catch (ExecutionException ex) {
						실패.add(ex.getCause());
					}
					catch (TimeoutException ex) {
						실패.add(ex);
					}
				}
				return 실패;
			}
			finally {
				풀.shutdownNow();
			}
		}

		/**
		 * 다른 스레드에서 서비스의 삭제로 댓글을 지우고 커밋까지 기다린다. <b>다른 스레드여야 한다</b> —
		 * 부르는 스레드에는 답글의 트랜잭션이 묶여 있어 같은 스레드의 호출은 그 트랜잭션에 참여한다.
		 * 끝나지 않거나 실패하면 {@link IllegalStateException} 으로 터진다.
		 */
		private void 다른_스레드에서_지운다(Comment comment) {
			ExecutorService 다른_스레드 = Executors.newSingleThreadExecutor();
			try {
				다른_스레드.submit(() -> this.commentService.delete(comment.getId(), user(댓글_작성자)))
						.get(대기_한도_초, TimeUnit.SECONDS);
			}
			catch (InterruptedException | ExecutionException | TimeoutException ex) {
				throw new IllegalStateException(
						"끼운 부모 삭제가 커밋되지 않았다 — 이 측정은 창을 볼 수 없다", ex);
			}
			finally {
				다른_스레드.shutdownNow();
			}
		}

		// --- 픽스처 ---

		/**
		 * 검증표 「활성 최상위 2·활성 답글이 있는 삭제된 최상위 1·답글 없는 삭제된 최상위 1, …」 의
		 * 픽스처다. 삭제된 최상위에 활성 답글을 둘, 활성 최상위 하나에 답글을 하나 둔다 — 그 까닭은
		 * {@link #활성_답글이_있는_삭제된_최상위만_자리로_센다()} 와
		 * {@link #자리의_답글을_모두_지우면_자리가_사라진다()} 에 있다. 다른 글에 활성 최상위와 답글을
		 * 하나씩 둔다. 모두 서비스로 쓰고 지우므로 집계가 활성 댓글 수와 같다.
		 *
		 * <p>둔 순서가 등록순이다 — 첫 활성, 답글 있는 삭제, 답글 없는 삭제, 둘째 활성. 자리를 활성 최상위
 * 사이에 둔다 — 자리를 맨 앞이나 맨 뒤에 두는 정렬이 첫 페이지에서 갈린다(2026-11-17 B-22 리뷰
 * 1라운드: 자리를 맨 앞에 둔 픽스처에서는 {@code deleted desc} 를 앞세운 변이가 살았다).
		 */
		private 자리_픽스처 자리_픽스처를_둔다(long 대역) {
			Post post = 게시글을_만든다(대역);
			Comment 첫_활성 = 서비스로_작성한_댓글(post, "첫 활성 최상위");
			서비스로_작성한_답글(post, 첫_활성, "활성 최상위의 답글");
			Comment 답글_있는_삭제 = 서비스로_작성한_댓글(post, "답글이 있어 자리로 남을 최상위");
			Comment 첫_답글 = 서비스로_작성한_답글(post, 답글_있는_삭제, "자리의 첫 답글");
			Comment 둘째_답글 = 서비스로_작성한_답글(post, 답글_있는_삭제, "자리의 둘째 답글");
			Comment 답글_없는_삭제 = 서비스로_작성한_댓글(post, "답글 없이 지울 최상위");
			Comment 둘째_활성 = 서비스로_작성한_댓글(post, "둘째 활성 최상위");
			지운다(답글_있는_삭제);
			지운다(답글_없는_삭제);

			Post 다른_글 = 게시글을_만든다(다른_글_대역);
			서비스로_작성한_답글(다른_글, 서비스로_작성한_댓글(다른_글, "다른 글의 최상위"), "다른 글의 답글");

			return new 자리_픽스처(post, 답글_있는_삭제, 첫_답글, 둘째_답글, 첫_활성, 둘째_활성);
		}

		/**
		 * 최상위 {@code 최상위_수} 개를 두고 그 가운데 앞의 {@code 자리_수} 개를 지운다. 최상위마다
		 * 활성 답글을 {@code 답글_수} 개 두므로 지운 최상위는 자리로 남는다. 집계를 거치지 않는다.
		 */
		private void 자리와_최상위들을_둔다(Post post, int 최상위_수, int 자리_수, int 답글_수) {
			for (int i = 0; i < 최상위_수; i++) {
				Comment 최상위 = 댓글을_둔다(post, "쿼리 수 최상위 " + i);
				for (int k = 0; k < 답글_수; k++) {
					답글을_둔다(post, 최상위, "쿼리 수 답글 " + i + "-" + k);
				}
				if (i < 자리_수) {
					this.jdbcTemplate.update(
							"UPDATE comment SET deleted = TRUE WHERE id = ?", 최상위.getId());
				}
			}
		}

		/**
		 * 기본 크기로 목록을 한 번 조회하고 그 조회가 낸 statement 수를 센다. 세는 구간은 조회
		 * 한 번이다 — 픽스처의 INSERT 가 섞이지 않는다.
		 */
		private 목록_측정 목록을_잰다(Post post) {
			this.발행된_문장.비운다();
			PageResponse<TopLevelCommentResponse> page = this.commentService.getList(
					post.getId(), CommentPageCondition.of(null, null));
			int 문장 = this.발행된_문장.발행된_문장_수();
			int 자리 = (int) page.content().stream().filter(TopLevelCommentResponse::deleted).count();
			int 답글 = page.content().stream().mapToInt(item -> item.replies().size()).sum();
			return new 목록_측정(page.content().size(), 자리, 답글, 문장);
		}

		private List<TopLevelCommentResponse> 목록(Post post) {
			return this.commentService.getList(post.getId(), CommentPageCondition.of(null, null))
					.content();
		}

		private Post 게시글을_만든다(long writerId) {
			return this.postRepository.saveAndFlush(Post.builder()
					.writerId(writerId)
					.writerNickname("글작성자")
					.title("답글 대상")
					.content("본문")
					.build());
		}

		/** 최상위 댓글을 서비스로 쓴다 — 집계가 오른다. */
		private Comment 서비스로_작성한_댓글(Post post, String content) {
			CommentResponse created = this.commentService.create(
					post.getId(), 댓글_작성자, new CommentCreateRequest(content, null));
			return this.commentRepository.findById(created.id()).orElseThrow();
		}

		/** 답글을 서비스로 쓴다 — 집계가 오른다. */
		private Comment 서비스로_작성한_답글(Post post, Comment parent, String content) {
			CommentResponse created = this.commentService.create(
					post.getId(), 댓글_작성자, new CommentCreateRequest(content, parent.getId()));
			return this.commentRepository.findById(created.id()).orElseThrow();
		}

		/** 집계를 거치지 않는 최상위 댓글. 집계를 보지 않는 테스트가 쓴다. */
		private Comment 댓글을_둔다(Post post, String content) {
			return this.commentRepository.saveAndFlush(Comment.builder()
					.post(post)
					.writerId(댓글_작성자)
					.writerNickname("댓글작성자")
					.content(content)
					.build());
		}

		/** 집계를 거치지 않는 답글. */
		private Comment 답글을_둔다(Post post, Comment parent, String content) {
			return this.commentRepository.saveAndFlush(Comment.builder()
					.post(post)
					.parent(parent)
					.writerId(댓글_작성자)
					.writerNickname("댓글작성자")
					.content(content)
					.build());
		}

		/** 서비스의 삭제로 지운다 — 집계가 준다. */
		private void 지운다(Comment comment) {
			this.commentService.delete(comment.getId(), user(댓글_작성자));
		}

		private void setCreatedAt(Long commentId, LocalDateTime createdAt) {
			this.jdbcTemplate.update(
					"UPDATE comment SET created_at = ? WHERE id = ?", createdAt, commentId);
		}

		/** JPA 를 거치지 않고 커밋된 값을 읽는다. 다른 스레드가 커밋한 것을 봐야 한다. */
		private int 커밋된_집계(Long postId) {
			Integer count = this.jdbcTemplate.queryForObject(
					"SELECT comment_count FROM post WHERE id = ?", Integer.class, postId);
			return count == null ? 0 : count;
		}

		private int 활성_댓글_수(Long postId) {
			Integer count = this.jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM comment WHERE post_id = ? AND deleted = FALSE",
					Integer.class, postId);
			return count == null ? 0 : count;
		}

		private int 답글_수(Long parentId) {
			Integer count = this.jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM comment WHERE parent_id = ? AND deleted = FALSE",
					Integer.class, parentId);
			return count == null ? 0 : count;
		}

		private boolean 삭제됐다(Long commentId) {
			Boolean deleted = this.jdbcTemplate.queryForObject(
					"SELECT deleted FROM comment WHERE id = ?", Boolean.class, commentId);
			return Boolean.TRUE.equals(deleted);
		}

	}

	/** {@link 답글} 의 자리 픽스처. 둔 것은 {@code 자리_픽스처를_둔다} 의 주석에 있다. */
	private record 자리_픽스처(
			Post post,
			Comment 답글_있는_삭제,
			Comment 첫_답글,
			Comment 둘째_답글,
			Comment 첫_활성,
			Comment 둘째_활성) {
	}

	/** 목록 조회 한 번의 최상위 항목 수·그중 자리의 수·답글 수와 그 조회가 낸 statement 수. */
	private record 목록_측정(int 최상위, int 자리, int 답글, int 문장) {
	}

	/**
	 * 발행된 SQL 을 모으고, 지정한 문장이 <b>발행되기 직전에</b> 훅을 돌린다. Hibernate 의
	 * {@code StatementInspector} 라 문장이 JDBC 로 넘어가기 전에 불린다.
	 *
	 * <p><b>로그·통계 설정에 기대지 않는다.</b> {@code org.hibernate.SQL} 로거나
	 * {@code show-sql}·{@code generate_statistics} 를 읽는 수단은 그 설정이 꺼지면 조용히 빈
	 * 결과를 돌려준다. 이 수집기는 테스트가 직접 꽂고, 꽂히지 않으면 단언이 빈 목록에서
	 * 터진다 (sp-docs/process/review-policy.md §4.1 "실패할 수 없는 단언을 쓰지 않는다").
	 *
	 * <p><b>스케줄러 스레드의 문장은 모으지 않고 훅도 돌리지 않는다.</b> 판정 행의 정리 작업
	 * ({@code PostViewCleanup}, B-21)이 컨텍스트가 뜬 직후 그 스레드에서 {@code delete from post_view}
	 * 를 낸다 — 테스트가 낸 문장이 아니다. 정리를 수집 창 안에서 스케줄러 스레드로 돌린 프로브에서
	 * {@code LikeServiceTest} 의 수집기가 그 문장을 모았고 첫 문장 단언이 빨갰다 (2026-11-17 B-21
	 * 실측). 테스트가 띄운 스레드의 문장은 그대로 모은다 — 동시 요청의 훅이 그 스레드에서 돈다. 스케줄러
	 * 스레드는 이름의 접두어로 가르고, 접두어는 컨텍스트의 스케줄러에서 받는다.
	 */
	static class StatementRecorder implements StatementInspector {

		private final List<String> 발행된_문장 = new CopyOnWriteArrayList<>();

		private final String 스케줄러_스레드_접두어;

		private volatile Predicate<String> 훅_대상 = sql -> false;

		private volatile Runnable 훅 = () -> {
		};

		/** 스레드마다 한 번만 돈다. 한 스레드가 두 번 모이면 집결지가 다시 닫혀 열리지 않는다. */
		private final ThreadLocal<Boolean> 훅_소진 = ThreadLocal.withInitial(() -> false);

		StatementRecorder(String 스케줄러_스레드_접두어) {
			this.스케줄러_스레드_접두어 = 스케줄러_스레드_접두어;
		}

		@Override
		public String inspect(String sql) {
			if (Thread.currentThread().getName().startsWith(this.스케줄러_스레드_접두어)) {
				return sql;
			}
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

		/** {@code post} 를 바꾸는 UPDATE 만. 소문자·공백이 정규화된 문장이다. */
		List<String> 발행된_post_UPDATE() {
			return this.발행된_문장.stream()
					.map(StatementRecorder::정규화)
					.filter(sql -> sql.startsWith("update post"))
					.toList();
		}

		/** 모든 UPDATE. 수집된 순서 그대로이고 소문자·공백이 정규화된 문장이다. */
		List<String> 발행된_UPDATE() {
			return this.발행된_문장.stream()
					.map(StatementRecorder::정규화)
					.filter(sql -> sql.startsWith("update "))
					.toList();
		}

		/** 쓰기 문장(UPDATE·INSERT·DELETE)만. 수집된 순서 그대로이고 정규화된 문장이다. */
		List<String> 발행된_쓰기() {
			return this.발행된_문장.stream()
					.map(StatementRecorder::정규화)
					.filter(sql -> sql.startsWith("update ") || sql.startsWith("insert ")
							|| sql.startsWith("delete "))
					.toList();
		}

		/** 수집된 모든 문장의 수. */
		int 발행된_문장_수() {
			return this.발행된_문장.size();
		}

		/** 정규화된 {@code update <테이블> …} 문장의 테이블 이름. */
		static String 대상_테이블(String 정규화된_UPDATE) {
			return 정규화된_UPDATE.split(" ")[1];
		}

		/**
		 * 정규화된 쓰기 문장의 머리 — {@code update <테이블>}, {@code insert into <테이블>},
		 * {@code delete from <테이블>}.
		 */
		static String 쓰기의_머리(String 정규화된_쓰기) {
			String[] 낱말 = 정규화된_쓰기.split(" ");
			return "update".equals(낱말[0])
					? 낱말[0] + " " + 낱말[1]
					: 낱말[0] + " " + 낱말[1] + " " + 낱말[2];
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
		StatementRecorder statementRecorder(ThreadPoolTaskScheduler taskScheduler) {
			return new StatementRecorder(taskScheduler.getThreadNamePrefix());
		}

		@Bean
		HibernatePropertiesCustomizer statementRecorderRegistration(StatementRecorder recorder) {
			return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, recorder);
		}

	}

	/** sp-docs/api-contract.md §6 의 Claim 으로 만든 주체. {@code nickname} 은 없다. */
	private static LoginMember user(long accountId) {
		return new LoginMember(accountId, ROLE_USER);
	}

	private Post savedPost(long writerId) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname("글작성자")
				.title("댓글이 달릴 글")
				.content("본문")
				.build());
	}

	/** 집계를 건드리지 않는 픽스처. 집계가 필요한 테스트는 {@link #서비스로_작성한_댓글} 을 쓴다. */
	private Comment savedComment(Post post, long writerId, String content) {
		return this.commentRepository.saveAndFlush(Comment.builder()
				.post(post)
				.writerId(writerId)
				.writerNickname("댓글작성자")
				.content(content)
				.build());
	}

	/** 집계까지 서비스가 올린 픽스처. 삭제 쪽 단언은 이 경로로 만든 값을 본다. */
	private Comment 서비스로_작성한_댓글(Post post, long writerId, String content) {
		given(this.memberClient.findActiveNickname(writerId)).willReturn("댓글작성자");
		CommentResponse created = this.commentService.create(
				post.getId(), writerId, new CommentCreateRequest(content, null));
		return this.commentRepository.findById(created.id()).orElseThrow();
	}

	/**
	 * {@code created_at} 을 직접 박는다. {@code @Column(updatable = false)} 이고 감사가
	 * 채우므로 JPA 로는 바꿀 수 없다 (sp-docs/domain-model.md §1.1).
	 */
	private void setCreatedAt(Long commentId, LocalDateTime createdAt) {
		this.jdbcTemplate.update(
				"UPDATE comment SET created_at = ? WHERE id = ?", createdAt, commentId);
	}

	private int 커밋된_집계(Long postId) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT comment_count FROM post WHERE id = ?", Integer.class, postId);
		return count == null ? 0 : count;
	}

	private int 내용으로_센_댓글_수(String content) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM comment WHERE content = ?", Integer.class, content);
		return count == null ? 0 : count;
	}

}
