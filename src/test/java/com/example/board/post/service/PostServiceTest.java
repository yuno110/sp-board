package com.example.board.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.board.client.MemberClient;
import com.example.board.comment.dto.CommentCreateRequest;
import com.example.board.comment.entity.Comment;
import com.example.board.comment.repository.CommentRepository;
import com.example.board.comment.service.CommentService;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.global.security.LoginMember;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostDetailResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostUpdateRequest;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import javax.sql.DataSource;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * sp-docs/plan/phase1.md §6 B-05 의 "검증" 표 중 저장·조회 쪽을 테스트로 옮긴 것이다. HTTP
 * 상태·응답 본문·인가·입력 검증은 {@link com.example.board.post.controller.PostControllerTest}
 * 가 본다.
 *
 * <p>정본은 sp-docs/requirements/board.md §3 §3.0 (작성자 스냅샷의 취득 경로),
 * sp-docs/api-contract.md §4.2 §4.3 §5.1, sp-docs/architecture.md §5 (트랜잭션 안의 원격 호출
 * 금지) 다.
 *
 * <p><b>{@link MemberClient} 를 목으로 바꾼다.</b> member 응답과 {@code S001}·{@code S002} 의
 * 대응은 B-04 의 {@code MemberClientTest} 가 스텁 서버로 이미 덮었고, 여기서 볼 것은 그 결과를
 * 받은 <b>board 쪽</b>이다 — 조회 키가 무엇인지, 호출이 트랜잭션 밖인지, 실패했을 때 행이
 * 남는지. 실패를 HTTP 응답으로 끝까지 끌고 가는 쪽은 {@code PostControllerTest} 가 실제
 * {@code MemberClient} 와 스텁 서버로 본다.
 *
 * <p>리포지터리는 스텁으로 바꾸지 않고 H2 를 그대로 쓴다. "글이 저장되지 않는다" 와 "조회수가
 * 1 증가한다" 는 저장된 행을 봐야 확인된다.
 *
 * <p>픽스처의 {@code writerId} 는 작성·조회(B-05)가 501~519, 수정·삭제(B-07)가 701~729,
 * B-11 의 중첩 클래스({@link 조회수_증가의_모양과_동시성})가 931~939 를 쓴다. 같은 JVM 의 모든
 * 테스트가 하나의 H2 DB 를 보므로 (sp-docs/tech-stack.md §5.3) 이미 쓰이는 대역(201~210,
 * 301~317, 401~402, {@code PostControllerTest} 의 521~539·741~759,
 * {@code PostQueryRepositoryTest} 의 601~619, {@code CommentServiceTest} 의 801~829·901~929)과
 * 겹치지 않게 둔다. 이 클래스는 트랜잭션 롤백에 기대지 않고 — 작성 경로가 스스로 커밋한다 —
 * {@code @AfterEach} 가 자기 대역을 지운다.
 * <b>테이블 전체를 지우지 않는다</b> — 다른 클래스의 픽스처를 없애면 그쪽이 간헐 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PostServiceTest {

	private static final long WRITER_ID = 501L;

	/** member 의 내부 API 가 돌려주는 닉네임. 스냅샷에 저장될 유일한 출처다 (§3 규칙 2). */
	private static final String MEMBER_NICKNAME = "member가준닉네임";

	/** sp-docs/api-contract.md §6 의 {@code role} claim 값. */
	private static final String ROLE_USER = "USER";

	private static final String ROLE_ADMIN = "ADMIN";

	@Autowired
	private PostService postService;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private CommentRepository commentRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private MemberClient memberClient;

	@AfterEach
	void 픽스처_정리() {
		// comment.post_id 에 FK 가 있으므로 (fk_comment_post_id) 댓글을 먼저 지운다
		this.jdbcTemplate.update("DELETE FROM comment WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 701 AND 729)");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 501 AND 519");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 701 AND 729");
	}

	// --- 작성 (P-01, sp-docs/requirements/board.md §3) ---

	@Test
	@DisplayName("정상 작성이면 행이 저장되고 스냅샷·기본값이 들어간다")
	void 정상_작성() {
		given(this.memberClient.findActiveNickname(WRITER_ID)).willReturn(MEMBER_NICKNAME);

		PostDetailResponse created = this.postService.create(
				WRITER_ID, new PostCreateRequest("첫 글", "첫 글 본문"));

		Post saved = this.postRepository.findById(created.id()).orElseThrow();
		assertThat(saved.getTitle()).isEqualTo("첫 글");
		assertThat(saved.getContent()).isEqualTo("첫 글 본문");
		assertThat(saved.getViewCount()).isZero();
		assertThat(saved.getCommentCount()).isZero();
		assertThat(saved.isDeleted()).isFalse();
		assertThat(saved.getCreatedAt()).isNotNull();
	}

	/**
	 * {@code writer_id} 는 검증된 JWT 의 {@code sub} 에서만 온다 (§3 규칙 1). 서비스에 그 값이
	 * 파라미터로 들어오고 요청 본문에는 자리가 없다 — 본문에 {@code writerId} 를 실었을 때
	 * 무시되는 것은 {@code PostControllerTest} 가 본다.
	 */
	@Test
	@DisplayName("저장된 writer_id 는 전달된 accountId(JWT sub)다")
	void 저장된_writer_id_는_sub_다() {
		given(this.memberClient.findActiveNickname(WRITER_ID)).willReturn(MEMBER_NICKNAME);

		PostDetailResponse created = this.postService.create(
				WRITER_ID, new PostCreateRequest("작성자 확인", "본문"));

		assertThat(this.postRepository.findById(created.id()).orElseThrow().getWriterId())
				.isEqualTo(WRITER_ID);
		assertThat(created.writerId()).isEqualTo(WRITER_ID);
	}

	/**
	 * {@code writer_nickname} 은 내부 API 응답에서만 온다 (§3 규칙 2). JWT Claim 에
	 * {@code nickname} 이 없으므로 다른 취득 경로가 없다 (sp-docs/adr/0012 §3).
	 */
	@Test
	@DisplayName("저장된 writer_nickname 은 MemberClient 응답값이다")
	void 저장된_닉네임은_member_응답값이다() {
		given(this.memberClient.findActiveNickname(WRITER_ID)).willReturn(MEMBER_NICKNAME);

		PostDetailResponse created = this.postService.create(
				WRITER_ID, new PostCreateRequest("닉네임 확인", "본문"));

		assertThat(this.postRepository.findById(created.id()).orElseThrow().getWriterNickname())
				.isEqualTo(MEMBER_NICKNAME);
		assertThat(created.writerNickname()).isEqualTo(MEMBER_NICKNAME);
	}

	/** <b>조회 키도 JWT 의 {@code sub} 다</b> (§3 규칙 3). 다른 값으로 조회하지 않는다. */
	@Test
	@DisplayName("MemberClient 조회 키는 전달된 accountId 하나뿐이다")
	void 조회_키는_sub_다() {
		given(this.memberClient.findActiveNickname(WRITER_ID)).willReturn(MEMBER_NICKNAME);

		this.postService.create(WRITER_ID, new PostCreateRequest("조회 키", "본문"));

		verify(this.memberClient).findActiveNickname(WRITER_ID);
		verify(this.memberClient, never()).findActiveNickname(999L);
	}

	/**
	 * <b>원격 호출이 DB 트랜잭션 밖에서 일어난다</b> (sp-docs/architecture.md §5,
	 * sp-docs/requirements/board.md §3 규칙 8).
	 *
	 * <p><b>행위가 아니라 경계를 관측한다</b> (sp-docs/process/review-policy.md §4.1). 호출된
	 * 스레드에서 실제 트랜잭션이 활성인지를 호출 시점에 읽는다 — 호출을 트랜잭션 안으로 옮기는
	 * 변이에서 그 값이 {@code true} 가 되어 이 단언이 빨개진다. 저장된 행만 보는 단언으로는
	 * 호출 위치를 가를 수 없다: 트랜잭션 안에서 호출해도 행은 똑같이 저장된다.
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
		AtomicReference<Boolean> transactionActiveAtCall = new AtomicReference<>();
		willAnswer(invocation -> {
			transactionActiveAtCall.set(TransactionSynchronizationManager.isActualTransactionActive());
			return MEMBER_NICKNAME;
		}).given(this.memberClient).findActiveNickname(WRITER_ID);

		this.postService.create(WRITER_ID, new PostCreateRequest("순서", "본문"));

		assertThat(transactionActiveAtCall.get())
				.as("MemberClient 가 호출되지 않았다면 이 단언은 아무것도 보지 못한다")
				.isNotNull()
				.as("원격 호출이 트랜잭션 안에 있으면 DB 커넥션이 member 의 지연만큼 점유된다 "
						+ "(sp-docs/architecture.md §5)")
				.isFalse();
	}

	// --- 생성 실패 (sp-docs/api-contract.md §5.1, requirements/board.md §3.0) ---

	/**
	 * {@code S002} 를 다시 감싸지 않는다. {@code MemberClient} 가 낸 그대로 올라가고
	 * {@code GlobalExceptionHandler} 가 403 으로 바꾼다 (sp-docs/api-contract.md §8.3).
	 */
	@Test
	@DisplayName("프로필이 없거나 탈퇴면 S002 가 그대로 올라가고 행이 저장되지 않는다")
	void S002_면_저장되지_않는다() {
		willThrow(new BusinessException(ErrorCode.PROFILE_REQUIRED))
				.given(this.memberClient).findActiveNickname(WRITER_ID);

		assertThatThrownBy(() -> this.postService.create(
				WRITER_ID, new PostCreateRequest("거부될 글", "본문")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.PROFILE_REQUIRED);

		assertThat(rowCount(WRITER_ID)).isZero();
	}

	@Test
	@DisplayName("member 호출이 실패하면 S001 이 그대로 올라가고 행이 저장되지 않는다")
	void S001_면_저장되지_않는다() {
		willThrow(new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR))
				.given(this.memberClient).findActiveNickname(WRITER_ID);

		assertThatThrownBy(() -> this.postService.create(
				WRITER_ID, new PostCreateRequest("거부될 글", "본문")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR);

		assertThat(rowCount(WRITER_ID)).isZero();
	}

	// --- 상세 조회 (P-04, P-05) ---

	/**
	 * <b>조회 경로는 {@code MemberClient} 를 호출하지 않는다</b> (sp-docs/api-contract.md §4.2,
	 * sp-docs/architecture.md §5). 작성자 표시는 게시글에 복제된 스냅샷으로 끝난다 — 그것이
	 * 스냅샷의 목적이다 (sp-docs/adr/0003).
	 */
	@Test
	@DisplayName("상세 조회는 스냅샷을 그대로 돌려주고 MemberClient 를 호출하지 않는다")
	void 상세_조회는_member_를_호출하지_않는다() {
		Post post = savedPost(502L, "저장된닉네임", "상세 대상", "상세 본문");

		PostDetailResponse detail = this.postService.getDetail(post.getId());

		assertThat(detail.id()).isEqualTo(post.getId());
		assertThat(detail.title()).isEqualTo("상세 대상");
		assertThat(detail.content()).isEqualTo("상세 본문");
		assertThat(detail.writerId()).isEqualTo(502L);
		assertThat(detail.writerNickname()).isEqualTo("저장된닉네임");
		verifyNoInteractions(this.memberClient);
	}

	@Test
	@DisplayName("상세 조회 2회면 view_count 가 2다")
	void 조회수는_조회마다_1_증가한다() {
		Post post = savedPost(503L, "조회수작성자", "조회수", "본문");

		this.postService.getDetail(post.getId());
		this.postService.getDetail(post.getId());

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().getViewCount())
				.isEqualTo(2L);
	}

	/** 응답에도 증가된 값이 실린다 — 저장만 하고 돌려주는 값이 옛 값이면 클라이언트가 못 본다. */
	@Test
	@DisplayName("상세 응답의 viewCount 는 증가 후 값이다")
	void 응답의_조회수는_증가_후_값이다() {
		Post post = savedPost(504L, "조회수작성자", "조회수 응답", "본문");

		assertThat(this.postService.getDetail(post.getId()).viewCount()).isEqualTo(1L);
	}

	@Test
	@DisplayName("없는 id 는 P001 이다")
	void 없는_id_는_P001() {
		assertThatThrownBy(() -> this.postService.getDetail(987654321L))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POST_NOT_FOUND);
	}

	/**
	 * 삭제된 게시글은 없는 것으로 본다 (sp-docs/requirements/board.md §5 규칙 1). 판정은
	 * {@code PostReader} 가 하고 서비스는 {@code deleted} 조건을 다시 쓰지 않는다.
	 */
	@Test
	@DisplayName("삭제된 게시글은 P001 이고 조회수도 늘지 않는다")
	void 삭제된_게시글은_P001() {
		Post post = savedPost(505L, "삭제된글작성자", "삭제된 글", "본문");
		post.softDelete();
		this.postRepository.saveAndFlush(post);

		assertThatThrownBy(() -> this.postService.getDetail(post.getId()))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POST_NOT_FOUND);

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().getViewCount()).isZero();
	}

	/** 조회 경로 전체에서 {@code MemberClient} 를 쓰지 않는다 — 실패 경로도 그렇다. */
	@Test
	@DisplayName("조회 실패 경로도 MemberClient 를 호출하지 않는다")
	void 조회_실패_경로도_member_를_호출하지_않는다() {
		assertThatThrownBy(() -> this.postService.getDetail(987654322L))
				.isInstanceOf(BusinessException.class);

		verify(this.memberClient, never()).findActiveNickname(anyLong());
	}

	// --- 목록 항목의 모양 (sp-docs/api-contract.md §4.3 §9.3) ---

	/**
	 * {@link PostResponse} 의 키 집합이 <b>정확히 일곱</b>이다 (§4.3, §9.3의 예시).
	 *
	 * <p>이 모양은 B-06(목록)·B-09(내가 쓴 글)이 함께 쓴다 (§4.3). 그 두 항목이 생기기 전에
	 * 어긋나면 모양이 바뀐 채로 두 엔드포인트가 올라가므로 여기서 핀한다. <b>{@code content}
	 * 가 없다</b> — 본문이 10,000자까지 허용되므로 한 페이지 50건이면 500,000자가 된다.
	 *
	 * <p>기대값은 <b>리터럴</b>이다. DTO 의 레코드 컴포넌트에서 뽑으면 필드가 늘거나 줄 때
	 * 기대값이 함께 움직여 단언이 아무것도 보지 못한다.
	 */
	@Test
	@DisplayName("목록 항목(PostResponse) 키는 정확히 일곱이고 content·deleted·updatedAt 이 없다")
	void 목록_항목_키_집합() throws Exception {
		Post post = savedPost(506L, "목록작성자", "목록 항목", "본문");

		Map<String, Object> serialized = this.objectMapper.readValue(
				this.objectMapper.writeValueAsString(PostResponse.from(post)),
				new TypeReference<Map<String, Object>>() { });

		assertThat(serialized.keySet()).isEqualTo(Set.of(
				"id", "title", "writerId", "writerNickname",
				"viewCount", "commentCount", "createdAt"));
	}

	// --- 수정 (P-06, sp-docs/security.md §5.2) ---

	/**
	 * B-07 검증표 "본인 글 수정 → 200, 반영됨" 중 <b>반영됨</b> 쪽이다. HTTP 상태와 응답
	 * 모양은 {@code PostControllerTest} 가 본다.
	 */
	@Test
	@DisplayName("본인 글 수정이면 제목·내용이 바뀐다")
	void 본인_글_수정이_반영된다() {
		Post post = savedPost(701L, "작성자닉네임", "원래 제목", "원래 본문");

		PostDetailResponse updated = this.postService.update(
				post.getId(), user(701L), new PostUpdateRequest("바뀐 제목", "바뀐 본문"));

		assertThat(updated.title()).isEqualTo("바뀐 제목");
		assertThat(updated.content()).isEqualTo("바뀐 본문");

		Post saved = this.postRepository.findById(post.getId()).orElseThrow();
		assertThat(saved.getTitle()).isEqualTo("바뀐 제목");
		assertThat(saved.getContent()).isEqualTo("바뀐 본문");
	}

	/**
	 * 검증표 "수정 후 {@code writer_id} → 변경 없음". <b>{@link #수정_후_닉네임은_그대로다()} 와
	 * 한 테스트로 합치지 않는다</b> — 검증표가 두 행으로 적었고, 둘은 취득 경로가 다른 값이다
	 * ({@code writer_id} 는 JWT Claim, {@code writer_nickname} 은 member 의 내부 API —
	 * sp-docs/requirements/board.md §3 규칙 1·2).
	 *
	 * <p><b>"반영됨" 을 함께 단언한다.</b> {@code update} 가 아무것도 하지 않는 변이에서도
	 * {@code writer_id} 는 그대로이므로, 수정이 실제로 일어났는지를 보지 않으면 이 단언이 그
	 * 변이를 통과시킨다.
	 */
	@Test
	@DisplayName("수정해도 writer_id 는 그대로다")
	void 수정_후_writer_id_는_그대로다() {
		Post post = savedPost(702L, "작성자닉네임", "원래 제목", "원래 본문");

		this.postService.update(
				post.getId(), user(702L), new PostUpdateRequest("바뀐 제목", "바뀐 본문"));

		Post saved = this.postRepository.findById(post.getId()).orElseThrow();
		assertThat(saved.getTitle())
				.as("수정이 일어나지 않았다면 아래 단언은 아무것도 보지 못한다")
				.isEqualTo("바뀐 제목");
		assertThat(saved.getWriterId())
				.as("수정 시 작성자 스냅샷을 갱신하지 않는다 "
						+ "(sp-docs/requirements/board.md §3 규칙 7)")
				.isEqualTo(702L);
	}

	/** 검증표 "수정 후 {@code writer_nickname} → 변경 없음". 위와 별개의 행이다. */
	@Test
	@DisplayName("수정해도 writer_nickname 은 그대로다")
	void 수정_후_닉네임은_그대로다() {
		Post post = savedPost(703L, "작성당시닉네임", "원래 제목", "원래 본문");

		this.postService.update(
				post.getId(), user(703L), new PostUpdateRequest("바뀐 제목", "바뀐 본문"));

		Post saved = this.postRepository.findById(post.getId()).orElseThrow();
		assertThat(saved.getContent())
				.as("수정이 일어나지 않았다면 아래 단언은 아무것도 보지 못한다")
				.isEqualTo("바뀐 본문");
		assertThat(saved.getWriterNickname())
				.as("닉네임 변경이 과거 글에 반영되지 않는 것은 의도된 동작이다 (sp-docs/adr/0003)")
				.isEqualTo("작성당시닉네임");
	}

	/**
	 * 검증표 "타인 글 수정 → 403 {@code P002}". <b>이 테스트가 소유자 검증의 위치를 고정한다</b>
	 * — 서비스를 직접 불러 {@code P002} 가 나오므로 판정이 Controller 나 Security 설정이 아니라
	 * Service 계층에 있다 (sp-docs/security.md §5.1,
	 * sp-docs/requirements/board.md §5 규칙 5).
	 */
	@Test
	@DisplayName("타인 글 수정은 P002 이고 내용이 바뀌지 않는다")
	void 타인_글_수정은_P002() {
		Post post = savedPost(704L, "작성자닉네임", "원래 제목", "원래 본문");

		assertThatThrownBy(() -> this.postService.update(
				post.getId(), user(705L), new PostUpdateRequest("탈취 제목", "탈취 본문")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POST_FORBIDDEN);

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().getTitle())
				.isEqualTo("원래 제목");
	}

	/**
	 * 검증표 "ADMIN 이 타인 글 수정 → 403 {@code P002}". <b>삭제와 권한이 다르다</b> — ADMIN 은
	 * 삭제만 할 수 있다 (sp-docs/security.md §5.2,
	 * sp-docs/requirements/board.md §5 규칙 6). 타인 글의 내용 변조를 막기 위한 것이고,
	 * 부적절 게시물은 삭제(블라인드)로만 처리한다.
	 */
	@Test
	@DisplayName("ADMIN 도 타인 글을 수정할 수 없다 — P002")
	void ADMIN_도_타인_글을_수정할_수_없다() {
		Post post = savedPost(706L, "작성자닉네임", "원래 제목", "원래 본문");

		assertThatThrownBy(() -> this.postService.update(
				post.getId(), admin(707L), new PostUpdateRequest("관리자 수정", "관리자 본문")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POST_FORBIDDEN);

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().getTitle())
				.isEqualTo("원래 제목");
	}

	/** 검증표 "없는 글 수정 → 404 {@code P001}". 판정은 {@code PostReader} 가 한다. */
	@Test
	@DisplayName("없는 글 수정은 P001 이다")
	void 없는_글_수정은_P001() {
		assertThatThrownBy(() -> this.postService.update(
				987654323L, user(708L), new PostUpdateRequest("제목", "본문")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POST_NOT_FOUND);
	}

	/**
	 * 검증표 <b>"타인의 삭제된 글 수정 → 404 {@code P001}. 403 {@code P002} 가 아니다"</b>
	 * (sp-docs/api-contract.md §4.4). <b>없는 글이 먼저다</b> — 조회를 권한 판정보다 앞에
	 * 둔다. 삭제 경로는 {@link #타인의_삭제된_글_삭제는_P001()} 이 본다.
	 *
	 * <p><b>삭제된 글을 타인이 수정하는 경우로 만든다.</b> 행이 아예 없는 id 로는 선후가
	 * 관측되지 않는다 — 비교할 {@code writer_id} 가 없으므로 권한 판정을 앞에 둔 구현도 404
	 * 밖에 낼 수 없다. 행이 남아 있고 보이지만 않는 상태에서만 두 순서가 서로 다른 답을
	 * 낸다: 조회가 먼저면 404 {@code P001}, 권한이 먼저면 403 {@code P002} 다.
	 *
	 * <p><b>{@link #없는_글_수정은_P001()} 과 같은 결함을 보는 것이 아니다.</b> 그쪽은
	 * "행이 없으면 404" 이고 {@code PostReader} 의 {@code orElseThrow} 를 겨눈다 — 호출자가
	 * 작성자인지와 무관하게 성립한다. 이쪽은 <b>두 판정의 순서</b>를 겨누고, {@code deleted}
	 * 판정을 권한 판정 뒤로 옮기는 변이에서만 빨개진다. 그 변이에서 저쪽은 녹색으로 남는
	 * 것을 실측했다.
	 *
	 * <p>{@code ErrorCode.POST_NOT_FOUND} 가 404 와 {@code P001} 을 함께 들고 있고 그 대응은
	 * {@code ErrorCodeTest} 가 핀하므로, 여기서는 코드만 단언하면 HTTP 답까지 정해진다.
	 *
	 * <p><b>정보 노출이 아니다.</b> {@code GET /posts/{id}} 가 비로그인에 열려 있어 존재
	 * 여부는 이미 공개 정보다 (§4.2 §4.4). 비공개 자원이라면 반대가 맞고, 1차의 board 에는
	 * 그런 자원이 없다.
	 */
	@Test
	@DisplayName("타인의 삭제된 글을 수정하면 P001 이다 — P002 가 아니다")
	void 타인의_삭제된_글_수정은_P001() {
		Post post = savedPost(720L, "작성자닉네임", "삭제된 남의 글", "본문");
		post.softDelete();
		this.postRepository.saveAndFlush(post);

		assertThatThrownBy(() -> this.postService.update(
				post.getId(), user(721L), new PostUpdateRequest("탈취 제목", "탈취 본문")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.as("없는 글이 먼저다 — 조회를 권한 판정보다 앞에 둔다 "
						+ "(sp-docs/api-contract.md §4.4)")
				.isEqualTo(ErrorCode.POST_NOT_FOUND);
	}

	// --- 삭제 (P-07, sp-docs/requirements/board.md §5 규칙 3) ---

	/**
	 * 검증표 "본인 글 삭제 → 204, {@code deleted = true}, 행 존재". 상태 코드는
	 * {@code PostControllerTest} 가 보고, 여기서는 <b>Soft Delete 라서 행이 남는 것</b>을 본다
	 * ({@code Post.softDelete()}).
	 */
	@Test
	@DisplayName("본인 글 삭제는 deleted = true 이고 행이 남는다")
	void 본인_글_삭제는_soft_delete_다() {
		Post post = savedPost(709L, "작성자닉네임", "삭제될 글", "본문");

		this.postService.delete(post.getId(), user(709L));

		assertThat(this.postRepository.findById(post.getId()))
				.as("Soft Delete 다 — 행이 사라지면 \"삭제된 댓글입니다\" 표시의 근거도 사라진다")
				.isPresent()
				.get()
				.extracting(Post::isDeleted)
				.isEqualTo(true);
	}

	/** 검증표 "ADMIN 이 타인 글 삭제 → 204". 수정과 달리 허용된다 (sp-docs/security.md §5.2). */
	@Test
	@DisplayName("ADMIN 은 타인 글을 삭제할 수 있다")
	void ADMIN_은_타인_글을_삭제할_수_있다() {
		Post post = savedPost(710L, "작성자닉네임", "관리자가 지울 글", "본문");

		this.postService.delete(post.getId(), admin(711L));

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().isDeleted()).isTrue();
	}

	/** 검증표 "타인(일반)이 삭제 → 403 {@code P002}". */
	@Test
	@DisplayName("타인(일반 USER)의 삭제는 P002 이고 글이 남는다")
	void 타인_일반_사용자의_삭제는_P002() {
		Post post = savedPost(712L, "작성자닉네임", "남의 글", "본문");

		assertThatThrownBy(() -> this.postService.delete(post.getId(), user(713L)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POST_FORBIDDEN);

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().isDeleted()).isFalse();
	}

	/**
	 * 검증표 <b>"타인의 삭제된 글 삭제 → 404 {@code P001}"</b>. §4.4 는 수정과 <b>삭제</b>를
	 * 함께 못박는다 (sp-docs/api-contract.md §4.4) — {@link #타인의_삭제된_글_수정은_P001()}
	 * 과 같은 규칙이고 경로만 다르다.
	 *
	 * <p><b>이 한 행이 변이 둘을 함께 잡는다</b>(실측). 둘 다 이 테스트가 없으면 199개 전부를
	 * 통과한다 — B-07 리뷰가 그것을 측정해 검증표에 행을 추가했다.
	 *
	 * <table border="1">
	 * <caption>이 테스트가 잡는 변이 (실측)</caption>
	 * <tr><th>변이</th><th>관측값</th></tr>
	 * <tr><td>권한 판정을 {@code deleted} 판정보다 앞으로 (조회를
	 * {@code findById} 로 바꾸고 삭제 여부를 뒤에서 본다)</td>
	 * <td>{@code POST_FORBIDDEN} — 403 이 된다</td></tr>
	 * <tr><td>없는·삭제된 글에 조용히 성공 (예외를 던지지 않고 빠져나간다)</td>
	 * <td>예외가 아예 나지 않는다 — HTTP 로는 <b>204</b> 다</td></tr>
	 * </table>
	 *
	 * <p><b>호출자를 일반 {@code USER} 로 둔다.</b> ADMIN 이면 순서를 뒤집은 변이에서도 권한
	 * 판정을 통과해 {@code P001} 이 나오므로 그 변이가 보이지 않는다 — 삭제는 ADMIN 에게
	 * 열려 있기 때문이다 (sp-docs/security.md §5.2).
	 *
	 * <p>수정 쪽과 같은 이유로 <b>삭제된 행</b>을 쓴다. 행이 아예 없는 id 로는 선후가
	 * 관측되지 않는다 — 비교할 {@code writer_id} 가 없다.
	 */
	@Test
	@DisplayName("타인의 삭제된 글을 삭제하면 P001 이다 — P002 도 아니고 조용한 성공도 아니다")
	void 타인의_삭제된_글_삭제는_P001() {
		Post post = savedPost(722L, "작성자닉네임", "삭제된 남의 글", "본문");
		post.softDelete();
		this.postRepository.saveAndFlush(post);

		assertThatThrownBy(() -> this.postService.delete(post.getId(), user(723L)))
				.as("조용히 성공하면 없는 글에 204 가 나간다")
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.as("없는 글이 먼저다 — 조회를 권한 판정보다 앞에 둔다 "
						+ "(sp-docs/api-contract.md §4.4)")
				.isEqualTo(ErrorCode.POST_NOT_FOUND);
	}

	/**
	 * 검증표 "게시글 삭제 후 하위 댓글 → 전부 {@code deleted = true}"
	 * (sp-docs/requirements/board.md §5 규칙 3).
	 *
	 * <p><b>{@code comment_count} 는 그대로다</b> (sp-docs/domain-model.md §5 의 표 셋째 줄) —
	 * 감소시키는 변이를 이 단언이 잡는다. 다른 게시글의 댓글은 건드리지 않는 것도 함께 본다:
	 * {@code where c.post.id = :postId} 를 빼는 변이가 거기서 빨개진다.
	 */
	@Test
	@DisplayName("게시글 삭제 시 하위 댓글이 전부 deleted = true 가 되고 comment_count 는 그대로다")
	void 삭제_시_하위_댓글도_deleted_가_된다() {
		Post target = savedPost(714L, "작성자닉네임", "삭제될 글", "본문");
		savedComment(target, 715L, "댓글1");
		savedComment(target, 716L, "댓글2");
		target.increaseCommentCount();
		target.increaseCommentCount();
		this.postRepository.saveAndFlush(target);

		Post other = savedPost(717L, "다른작성자", "남는 글", "본문");
		savedComment(other, 715L, "남는 댓글");

		this.postService.delete(target.getId(), user(714L));

		assertThat(활성_댓글_수(target.getId()))
				.as("하위 댓글이 전부 deleted = true 가 된다 "
						+ "(sp-docs/requirements/board.md §5 규칙 3)")
				.isZero();
		assertThat(this.postRepository.findById(target.getId()).orElseThrow().getCommentCount())
				.as("게시글 삭제에서는 comment_count 를 그대로 둔다 (sp-docs/domain-model.md §5)")
				.isEqualTo(2);
		assertThat(활성_댓글_수(other.getId()))
				.as("다른 게시글의 댓글은 건드리지 않는다")
				.isEqualTo(1);
	}

	/**
	 * <b>수정·삭제 경로는 {@code MemberClient} 를 호출하지 않는다</b>
	 * (sp-docs/api-contract.md §4.2, sp-docs/requirements/board.md §7). 수정 시 스냅샷을
	 * 갱신하지 않으므로 닉네임을 다시 얻을 이유가 없고, 활성 프로필은 생성에만 필요하다
	 * (§5 규칙 8). 그래서 <b>member 장애가 수정·삭제를 막지 않는다.</b>
	 */
	@Test
	@DisplayName("수정·삭제는 MemberClient 를 호출하지 않는다")
	void 수정과_삭제는_member_를_호출하지_않는다() {
		Post post = savedPost(718L, "작성자닉네임", "원래 제목", "원래 본문");

		this.postService.update(
				post.getId(), user(718L), new PostUpdateRequest("바뀐 제목", "바뀐 본문"));
		this.postService.delete(post.getId(), user(718L));

		verifyNoInteractions(this.memberClient);
	}

	/**
	 * 완료 기준 <b>"게시글 삭제의 두 효과가 한 로컬 트랜잭션이다"</b>
	 * (sp-docs/domain-model.md §5, sp-docs/plan/phase1.md §6 B-07). 검증표의 두 줄 — "삭제 중
	 * 예외 발생 → 둘 다 롤백, 두 단언이 각각 반증 가능해야 한다" 와 "한 번의 삭제에 커밋이
	 * 정확히 한 번 — 변이 셋(A·B·C)을 돌린다" — 이 여기 있다.
	 *
	 * <p>구조는 AU-08·AU-09 의 {@code 트랜잭션_경계} 와 같다. 빈 둘을 스파이로 바꿔야 하므로
	 * 중첩 클래스이고, 바깥 클래스의 {@code postService} 에는 스파이가 아닌 원래 빈이 들어
	 * 있으므로 여기서 다시 주입받는다.
	 *
	 * <table border="1">
	 * <caption>{@code delete} 변이별 실패 (실측, 전체 198개 기준)</caption>
	 * <tr><th>변이</th><th>무엇이 깨지나</th><th>실측 결과</th></tr>
	 * <tr><td><b>A</b> — {@code @Transactional} 제거</td><td>트랜잭션이 아예 없다</td>
	 * <td><b>9개 실패.</b> {@link #커밋이_정확히_한_번이다()} 는 집계 전에
	 * {@code InvalidDataAccessApiUsageException} 이 난다 —
	 * {@code softDeleteByPostId} 의 {@code flushAutomatically} 가 트랜잭션 없이
	 * {@code flush} 를 부른다. {@link #둘_다_롤백된다()} 는 <b>댓글 단언</b>이 빨개진다
	 * (활성 댓글 2 기대, 0 관측 — 주입한 UPDATE 가 그 자리에서 커밋됐다)</td></tr>
	 * <tr><td><b>B</b> — {@code @Transactional(noRollbackFor = …)}</td>
	 * <td>트랜잭션은 있고 원자성만 깨진다</td>
	 * <td><b>1개 실패 — {@link #둘_다_롤백된다()} 하나뿐.</b> 거기서
	 * <b>{@code post.deleted} 단언</b>이 빨개진다</td></tr>
	 * <tr><td><b>C</b> — 연산마다 자기 트랜잭션 ({@code TransactionTemplate} 둘)</td>
	 * <td><b>독립적으로 커밋되는 트랜잭션 둘</b></td>
	 * <td><b>2개 실패, 둘 다 진짜 단언 실패.</b>
	 * {@link #커밋이_정확히_한_번이다()} 의 집계가 <b>2</b> 였다</td></tr>
	 * </table>
	 *
	 * <p><b>두 단언이 각각 반증 가능하다</b>(§4.1) — A 가 댓글 단언을, B 가
	 * {@code post.deleted} 단언을 따로 빨갛게 만들었다. 통과하는 것으로는 부족하고 깨뜨려
	 * 봐야 알 수 있는 부분이다.
	 *
	 * <p><b>집계 2 는 측정값이고 예측값이 아니다</b>(§4.1 — 그 절의 앞 판이 숫자를 두 번
	 * 틀렸다). AU-09 의 같은 변이는 3이었다. 여기서 하나 적은 이유는 {@code PostReader} 가
	 * 쓰는 파생 쿼리({@code findByIdAndDeletedFalse})에 {@code SimpleJpaRepository} 의 기본
	 * {@code @Transactional} 이 적용되지 않아 그 조회가 트랜잭션을 열지 않기 때문이다
	 * (§4.1 의 마지막 블록인용이 같은 사실을 변이 A 에 대해 적는다). <b>단언은 "정확히 한
	 * 번" 에만 걸고 이 수치에 걸지 않는다.</b>
	 *
	 * <p><b>C 가 핵심이고 행위 단언으로 잡히지 않는다</b>
	 * (sp-docs/process/review-policy.md §4.1). 정상 경로는 두 효과가 여전히 둘 다 일어나므로
	 * 통과하고, 실패를 주입해도 "한 트랜잭션" 과 "첫 트랜잭션이 롤백된 두 트랜잭션" 이 관측상
	 * 구별되지 않는다. 그래서 커밋 횟수를 직접 센다.
	 *
	 * <p>위 실측에서 {@link #둘_다_롤백된다()} 가 C 에서도 빨개졌지만 <b>거기에 기대지
	 * 않는다.</b> 이 변이 형태는 첫 쓰기가 스파이 바깥({@code post.deleted} 쪽)에 있어 그것이
	 * 먼저 커밋되지만, 스파이가 가로채는 연산이 첫 쓰기가 되는 형태에서는 아무것도 커밋되지
	 * 않아 두 단언이 <b>"한 트랜잭션이어서" 가 아니라 "애초에 아무것도 쓰이지 않아서"</b>
	 * 통과한다(§4.1). <b>C 를 잡는 것은 경계 관측이다.</b>
	 *
	 * <p><b>이 셋이 잡힌다는 것은 아무것도 증명하지 않는다</b>(§4.1). 변이 테스트는 반증
	 * 도구다 — 잡히지 않은 변이가 발견이다.
	 *
	 * <p>C 의 손상이 이 항목에서 뜻하는 것: <b>게시글은 사라졌는데 댓글이 살아 있거나, 댓글만
	 * 지워지고 게시글이 목록에 남는다</b> (sp-docs/domain-model.md §5).
	 */
	@Nested
	@DisplayName("게시글 삭제와 하위 댓글 삭제가 한 로컬 트랜잭션이다")
	class 삭제_트랜잭션_경계 {

		/** 댓글 연쇄 삭제 지점에 실패를 주입한다. 나머지 메서드는 실제 빈에 위임된다. */
		@MockitoSpyBean
		private CommentRepository spyCommentRepository;

		/**
		 * <b>경계를 관측하는 지점이다.</b> {@code TransactionInterceptor} 가 이 빈으로
		 * 트랜잭션을 열고 닫으므로 커밋이 전부 여기를 지난다.
		 */
		@MockitoSpyBean
		private PlatformTransactionManager spyTransactionManager;

		/** 위 스파이들을 주입받은 쪽이다. 바깥 클래스의 것과 다른 인스턴스다. */
		@Autowired
		private PostService postService;

		@Autowired
		private PostRepository postRepository;

		/**
		 * <b>JPA 를 거치지 않고 읽는다.</b> 롤백 여부를 보는 것이므로 영속성 컨텍스트가 아니라
		 * 커밋된 행을 봐야 한다. {@link #둘_다_롤백된다()} 는 쓰기에도 쓴다 — 거기에 이유가
		 * 적혀 있다.
		 */
		@Autowired
		private JdbcTemplate jdbcTemplate;

		/**
		 * 검증표 <b>"한 번의 삭제에 커밋이 정확히 한 번"</b>. 경계 자체를 관측하므로 행위
		 * 단언으로 대체할 수 없다 (sp-docs/process/review-policy.md §4.1).
		 *
		 * <p><b>{@code isNewTransaction()} 이 참인 커밋만 센다.</b> 참여 트랜잭션도 매니저의
		 * {@code commit} 을 지나지만 그것은 물리 트랜잭션의 커밋이 아니므로, 빼고 남는 수가
		 * 실제로 커밋된 트랜잭션의 수다.
		 *
		 * <p><b>"필터가 없으면 집계가 1을 넘는다" 를 이 자리의 근거로 적지 않는다.</b>
		 * {@code delete} 는 {@code SimpleJpaRepository} 상속 메서드를 하나도 부르지 않고
		 * 파생 쿼리와 {@code @Query} 만 지나므로 <b>필터를 지워도 올바른 구현의 집계가 1
		 * 그대로다</b> (B-07 리뷰 실측). 다른 항목에서 성립하는 이유를 여기 옮겨 적으면
		 * 측정하지 않은 주장이 된다 (sp-docs/conventions.md §9.5).
		 *
		 * <p><b>그래도 필터는 둔다.</b> sp-docs/process/review-policy.md §4.1 이 요구하는
		 * 형태이고, 조회가 상속 메서드로 바뀌는 순간 필터 없는 테스트는 <b>올바른 구현에서</b>
		 * 빨개진다.
		 *
		 * <p><b>단언은 "정확히 한 번" 에만 걸고 다른 수치를 예측값으로 쓰지 않는다</b>(§4.1).
		 */
		@Test
		@DisplayName("한 번의 삭제에 물리 트랜잭션이 정확히 하나 커밋된다")
		void 커밋이_정확히_한_번이다() {
			Post post = 댓글_둘인_게시글을_만든다(719L);

			// 준비가 끝난 뒤에 센다. 세는 구간을 delete 한 번으로 좁혀 둔다
			List<TransactionStatus> 커밋된_물리_트랜잭션 = new ArrayList<>();
			willAnswer(invocation -> {
				TransactionStatus status = invocation.getArgument(0);
				if (status.isNewTransaction()) {
					커밋된_물리_트랜잭션.add(status);
				}
				return invocation.callRealMethod();
			}).given(this.spyTransactionManager).commit(any(TransactionStatus.class));

			this.postService.delete(post.getId(), new LoginMember(719L, ROLE_USER));

			assertThat(커밋된_물리_트랜잭션)
					.as("post.deleted 와 하위 댓글의 deleted 가 한 로컬 트랜잭션이다 "
							+ "(sp-docs/domain-model.md §5)")
					.hasSize(1);
		}

		/**
		 * 검증표 <b>"삭제 중 예외 발생 → {@code post.deleted} 와 댓글 {@code deleted} 둘 다
		 * 롤백. 두 단언이 각각 반증 가능해야 한다"</b>.
		 *
		 * <p><b>스텁이 실제 UPDATE 를 돌린 뒤에 throw 한다.</b> 그냥 {@code willThrow} 로
		 * 가로채면 UPDATE 가 발행될 자리가 사라져 "댓글도 롤백된다" 가 <b>어떤 구현에서도
		 * 참</b>이 된다 — {@code @Transactional} 이 아예 없어도 통과하는 반증 불가능한 단언이다
		 * (sp-docs/process/review-policy.md §4.1). 되돌릴 효과를 실제로 만들어야 단언이
		 * 일한다.
		 *
		 * <p>{@code post.deleted} 쪽은 반대로 <b>미반영이 정상</b>이다. 관리 엔티티의 변경이
		 * 커밋 시점에 flush 되므로, 롤백되면 쓰이지 않고 커밋되면(변이 B) {@code true} 가
		 * 쓰인다.
		 *
		 * <p><b>UPDATE 를 {@code JdbcTemplate} 으로 발행한다.</b> 리포지터리는 JDK 프록시이고
		 * 스파이가 그것을 {@code delegatesTo} 로 감싸므로 {@code callRealMethod()} 를 쓸 수
		 * 없다("Cannot call abstract real method"). {@code JpaTransactionManager} 가 같은
		 * {@code DataSource} 의 커넥션을 트랜잭션에 묶으므로 이 UPDATE 는 <b>서비스의
		 * 트랜잭션에 참여한다</b> — 경계가 없으면 그 자리에서 커밋되고, 있으면 함께 롤백된다.
		 */
		@Test
		@DisplayName("댓글 연쇄 삭제가 실패하면 post.deleted 도 댓글 deleted 도 커밋되지 않는다")
		void 둘_다_롤백된다() {
			Post post = 댓글_둘인_게시글을_만든다(719L);

			willAnswer(invocation -> {
				// 이 문장의 짝은 CommentRepository.softDeleteByPostId 다. 둘이 어긋나면 아래
				// 단언이 그 자리에서 터진다 — 영향 행 수를 버리면 0행이 되어도 조용하고,
				// 되돌릴 효과가 없으므로 댓글 단언이 반증 불가능해진다
				// (sp-docs/process/review-policy.md §4.1 "실패할 수 없는 단언을 쓰지 않는다")
				int updated = this.jdbcTemplate.update(
						"UPDATE comment SET deleted = TRUE WHERE post_id = ? AND deleted = FALSE",
						(Long) invocation.getArgument(0));
				assertThat(updated)
						.as("주입한 UPDATE 가 실제로 행을 바꿔야 이 테스트가 일한다")
						.isEqualTo(2);
				throw new DataIntegrityViolationException("댓글 연쇄 삭제 실패");
			}).given(this.spyCommentRepository).softDeleteByPostId(anyLong());

			assertThatThrownBy(() ->
					this.postService.delete(post.getId(), new LoginMember(719L, ROLE_USER)))
					.isInstanceOf(DataIntegrityViolationException.class);

			assertThat(커밋된_삭제_여부(post.getId()))
					.as("두 효과가 한 로컬 트랜잭션이다 (sp-docs/domain-model.md §5)")
					.isFalse();
			assertThat(커밋된_활성_댓글_수(post.getId()))
					.as("발행된 UPDATE 도 함께 되돌아간다 — 댓글이 활성으로 남아 있어야 한다")
					.isEqualTo(2);
		}

		private Post 댓글_둘인_게시글을_만든다(long writerId) {
			Post post = this.postRepository.saveAndFlush(Post.builder()
					.writerId(writerId)
					.writerNickname("작성자닉네임")
					.title("경계 관측 대상")
					.content("본문")
					.build());
			this.spyCommentRepository.saveAllAndFlush(List.of(
					Comment.builder().post(post).writerId(writerId)
							.writerNickname("댓글작성자").content("댓글1").build(),
					Comment.builder().post(post).writerId(writerId)
							.writerNickname("댓글작성자").content("댓글2").build()));
			return post;
		}

		private boolean 커밋된_삭제_여부(Long postId) {
			return Boolean.TRUE.equals(this.jdbcTemplate.queryForObject(
					"SELECT deleted FROM post WHERE id = ?", Boolean.class, postId));
		}

		private long 커밋된_활성_댓글_수(Long postId) {
			Long count = this.jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM comment WHERE post_id = ? AND deleted = FALSE",
					Long.class, postId);
			return count == null ? 0 : count;
		}

	}

	/**
	 * sp-docs/plan/phase1.md §6 B-11 의 "검증" 표다. <b>조회수 증가가 좁은 UPDATE 이고, 그
	 * 조회가 {@code comment_count} 를 덮어쓰지 않는다.</b>
	 *
	 * <h2>무엇이 결함이었나</h2>
	 *
	 * <p>{@code getDetail} 이 관리 엔티티의 조회수를 메모리에서 올리면 더티 체킹이 변경되지
	 * 않은 컬럼까지 전부 UPDATE 에 싣고, <b>그 트랜잭션이 읽은 {@code comment_count} 를 그대로
	 * 다시 쓴다</b> — SELECT 와 flush 사이에 커밋된 댓글 작성의 증가가 조회 하나로 소실된다
	 * (sp-docs/api-contract.md §4.5 가 {@code comment_count} 와 댓글 목록의
	 * {@code totalElements} 가 같다고 정한 값이 어긋난다). 조회수 자체도 같은 성질이어서 동시
	 * 조회가 같은 값을 읽으면 하나가 소실된다.
	 *
	 * <p><b>소실은 DB 가 아니라 애플리케이션에서 일어나므로 H2 에서 재현된다</b> — 실제 MySQL
	 * 이 필요하지 않다. 다만 <b>읽기가 쓰기보다 먼저 일어나도록 끼워넣기를 강제한다</b>
	 * (sp-docs/plan/phase1.md §6 B-11 의 블록인용). <b>강제는 결정성의 보장이고, 강제가 없으면
	 * 공허해진다는 뜻이 아니다</b> — 강제 없이도 잡히는지는 이 자리에서 측정하지 않았다.
	 *
	 * <h2>강제하는 지점 — {@code post} 를 바꾸는 문장이 발행되기 직전</h2>
	 *
	 * <p>{@link StatementRecorder} 가 {@code update post …} 를 보면 그 스레드에서 훅을 돌린다.
	 * Hibernate 의 {@code StatementInspector} 라 문장이 JDBC 로 넘어가기 <b>전에</b> 불리므로,
	 * 그 시점에 조회 트랜잭션의 읽기는 끝나 있고 아직 아무 쓰기도 없다.
	 *
	 * <ul>
	 * <li><b>동시 조회</b>는 집결지에 세운다 — 모두 모일 때까지 어느 스레드도 쓰지 않으므로,
	 * 엔티티 증가 구현이면 그 읽기들이 모두 같은 조회수를 보았다는 뜻이 된다
	 * <li><b>댓글 끼워넣기</b>는 그 자리에서 댓글 작성을 <b>다른 스레드로 끝까지(커밋까지)</b>
	 * 돌린다. 같은 스레드로 부르면 조회의 트랜잭션에 참여해 따로 커밋되지 않아 "그 사이에
	 * 커밋된 증가" 가 만들어지지 않는다
	 * </ul>
	 *
	 * <p><b>커밋 시점에 모이게 하면 올바른 구현이 교착한다.</b> 좁은 UPDATE 는 실행 시점에
	 * {@code post} 행 락을 잡으므로, 먼저 실행한 스레드가 집결지를 기다리는 동안 나머지가 그
	 * 락을 기다린다. 발행 직전에 세우면 모두 모인 뒤에 실행이 시작되어 락이 차례로 풀린다.
	 *
	 * <p>이 중첩 클래스의 {@code writerId} 는 931~939 를 쓴다. 바깥 클래스의
	 * {@code @AfterEach} 는 그 대역을 지우지 않으므로 여기서 지운다.
	 * {@code comment.post_id} 에 FK 가 있으므로 ({@code fk_comment_post_id}) 댓글을 먼저 지운다.
	 */
	@Nested
	@DisplayName("조회수 증가가 좁은 UPDATE 이고 조회가 comment_count 를 덮어쓰지 않는다")
	@ContextConfiguration(classes = PostServiceTest.StatementRecorderConfig.class)
	class 조회수_증가의_모양과_동시성 {

		/**
		 * 동시 조회 수. <b>2 이상이어야 소실이 일어날 자리가 있다</b> — 하나뿐이면 덮어쓸
		 * 다른 읽기가 없다.
		 */
		private static final int 동시_요청 = 3;

		private static final long 끼워넣기_대역 = 931L;

		private static final long 동시_조회_대역 = 932L;

		private static final long 조회_문장_대역 = 933L;

		private static final long 응답_모양_대역 = 934L;

		/** 집결지·끼워넣기에서 기다리는 한도. 넘으면 강제가 일어나지 않은 것이므로 시끄럽게 터진다. */
		private static final int 집결_한도_초 = 10;

		@Autowired
		private PostService postService;

		@Autowired
		private PostRepository postRepository;

		/** 끼워넣는 댓글 작성의 주체다. 조회 트랜잭션 밖에서 스스로 커밋한다. */
		@Autowired
		private CommentService commentService;

		/** 바깥 클래스의 {@code @MockitoBean} 이 이 컨텍스트에도 상속된다. */
		@Autowired
		private MemberClient memberClient;

		@Autowired
		private JdbcTemplate jdbcTemplate;

		/**
		 * <b>조회 트랜잭션에 묶이지 않은 커넥션</b>을 얻는 자리다. {@code JdbcTemplate} 으로는
		 * 그것을 얻을 수 없다 — {@code DataSourceUtils} 가 현재 트랜잭션의 커넥션을 돌려준다.
		 */
		@Autowired
		private DataSource dataSource;

		@Autowired
		private ObjectMapper objectMapper;

		@Autowired
		private StatementRecorder 발행된_문장;

		@AfterEach
		void 픽스처_정리() {
			this.발행된_문장.비운다();
			// comment.post_id 에 FK 가 있으므로 (fk_comment_post_id) 댓글을 먼저 지운다
			this.jdbcTemplate.update("DELETE FROM comment WHERE post_id IN "
					+ "(SELECT id FROM post WHERE writer_id BETWEEN 931 AND 939)");
			this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 931 AND 939");
		}

		/**
		 * 검증표 <b>"댓글 작성과 상세 조회를 끼워넣기 → {@code comment_count} 가 덮어써지지
		 * 않는다. 조회수 증가를 엔티티 변경으로 되돌리면 깨진다"</b>.
		 *
		 * <p>조회 트랜잭션이 게시글을 읽은 <b>뒤</b>, 그 트랜잭션이 {@code post} 를 바꾸기
		 * <b>전</b>에 댓글 작성을 끼워 커밋시킨다. 엔티티 증가 구현이면 뒤따르는 전체 컬럼
		 * UPDATE 가 읽어 둔 {@code comment_count} 를 다시 써서 그 증가를 지운다.
		 *
		 * <p><b>전제를 못박는 단언은 훅 안에서 읽은 값이다.</b> 조회의 쓰기가 나가기 전에
		 * {@link DataSource} 에서 직접 얻은 커넥션으로 {@code comment_count} 를 읽어 그 값이
		 * 이미 1 인 것을 본다 — 그래야 그 증가가 <b>다른 트랜잭션이 따로 커밋한 것</b>임이
		 * 관측된다. {@code JdbcTemplate} 으로는 안 된다: {@code DataSourceUtils} 를 지나 그
		 * 트랜잭션의 커넥션을 쓰므로 아직 커밋되지 않은 변경까지 보인다.
		 *
		 * <p><b>커밋된 뒤에 읽는 단언들로는 그 구별이 되지 않는다</b>(실측). 끼워넣기를 같은
		 * 스레드로 돌려 댓글 작성이 조회의 트랜잭션에 <b>참여</b>하게 만들면 — {@code Thread}
		 * 를 {@code start} 가 아니라 {@code run} 으로 돌리는 변이 — 그 댓글이 조회와 함께
		 * 커밋되어 테스트가 읽는 시점에는 보이므로 커밋 뒤의 단언이 전부 통과한다. 그때
		 * 빨개지는 것은 훅 안에서 읽은 위 단언 하나다.
		 *
		 * <p>나머지 단언은 마지막 단언이 공허해지는 경우를 막는다. 끼워넣기가 실제로
		 * 일어났는지(훅이 돌지 않으면 아무것도 보지 못한다), 끼워넣은 댓글이 실패하지
		 * 않았는지, 그 댓글 행이 활성으로 남아 있는지, 조회가 {@code post} 를 바꾸었는지
		 * (아예 바꾸지 않았다면 덮어쓸 수도 없다).
		 */
		@Test
		@DisplayName("조회 트랜잭션 도중에 댓글이 커밋되어도 comment_count 가 덮어써지지 않는다")
		void 조회가_끼워넣은_댓글의_증가를_덮어쓰지_않는다() {
			given(this.memberClient.findActiveNickname(끼워넣기_대역)).willReturn(MEMBER_NICKNAME);
			Post post = 게시글을_만든다(끼워넣기_대역);
			AtomicBoolean 끼워넣었다 = new AtomicBoolean();
			AtomicReference<Throwable> 끼워넣기_실패 = new AtomicReference<>();
			// -1 은 "훅이 읽지 않았다" 다. 읽지 않았으면 아래 단언이 그 값으로 빨개진다
			AtomicInteger 쓰기_전_집계 = new AtomicInteger(-1);
			// 끼운 댓글 작성도 update post 를 발행하므로 훅을 한 번만 돌린다. 스레드마다 한 번인
			// StatementRecorder 의 소진 표시로는 막히지 않는다 — 그쪽은 다른 스레드다
			this.발행된_문장.훅을_건다(sql -> sql.startsWith("update post"), () -> {
				if (끼워넣었다.compareAndSet(false, true)) {
					끼워넣기_실패.set(다른_스레드에서_댓글을_작성한다(post.getId()));
					쓰기_전_집계.set(조회_트랜잭션_밖에서_읽은_집계(post.getId()));
				}
			});

			this.postService.getDetail(post.getId());

			assertThat(끼워넣었다)
					.as("조회가 post 를 바꾸는 문장을 발행하지 않았으면 끼워넣기가 일어나지 않는다")
					.isTrue();
			assertThat(끼워넣기_실패.get())
					.as("끼워넣은 댓글 작성이 실패하면 아래 단언은 소실을 보는 것이 아니다")
					.isNull();
			assertThat(쓰기_전_집계.get())
					.as("조회의 쓰기가 나가기 전에, 조회 트랜잭션에 묶이지 않은 커넥션에서 이미 "
							+ "1 이다 — 그 증가는 다른 트랜잭션이 따로 커밋한 것이다")
					.isEqualTo(1);
			assertThat(커밋된_활성_댓글_수(post.getId()))
					.as("끼워넣은 댓글 행이 활성으로 남아 있다 — 집계가 이 수와 같아야 한다 "
							+ "(sp-docs/api-contract.md §4.5)")
					.isEqualTo(1);
			assertThat(커밋된_조회수(post.getId()))
					.as("조회수가 오르지 않았으면 조회가 post 를 바꾸지 않은 것이고 "
							+ "아래 단언이 공허해진다")
					.isEqualTo(1L);
			assertThat(커밋된_집계(post.getId()))
					.as("조회 하나가 그 사이에 커밋된 comment_count 증가를 덮어쓰지 않는다 "
							+ "(sp-docs/plan/phase1.md §6 B-11, sp-docs/api-contract.md §4.5)")
					.isEqualTo(1);
		}

		/**
		 * 검증표 <b>"같은 글을 동시 조회 N건 → {@code view_count} = N. 소실 없음"</b>.
		 *
		 * <p>엔티티 증가 구현이면 세 트랜잭션이 모두 0 을 읽어 메모리에서 1 로 올리므로 커밋된
		 * 조회수가 1 에서 멈춘다.
		 */
		@Test
		@DisplayName("같은 글을 동시 조회 3건이면 view_count 가 3 이다")
		void 동시_조회에서_증가가_소실되지_않는다() throws InterruptedException {
			Post post = 게시글을_만든다(동시_조회_대역);
			모든_스레드를_post_UPDATE_직전에_모은다();

			List<Throwable> 실패 = 동시에_실행한다(i -> this.postService.getDetail(post.getId()));

			assertThat(실패).as("동시 조회가 실패하면 아래 단언은 소실을 보는 것이 아니다").isEmpty();
			assertThat(커밋된_조회수(post.getId()))
					.as("동시 조회에서 view_count 증가가 소실되지 않는다 "
							+ "(sp-docs/plan/phase1.md §6 B-11)")
					.isEqualTo(동시_요청);
		}

		/**
		 * 검증표 <b>"발행된 UPDATE 의 모양 → {@code view_count} 만 싣는다.
		 * {@code comment_count}·{@code title}·{@code content} 가 실리지 않는다"</b>.
		 *
		 * <p><b>{@code hasSize(1)} 이 두 가지를 본다.</b> 수집기가 꽂히지 않으면 빈 목록에서
		 * 터지므로 문장을 못 보는 수단이 조용히 통과하지 않고
		 * (sp-docs/process/review-policy.md §4.1), <b>엔티티가 더티로 남으면</b> 전체 컬럼
		 * UPDATE 가 한 문장 더 붙어 그 자리에서 터진다 — 좁은 UPDATE 를 두고 엔티티 메서드를
		 * 함께 부르는 변이가 그것이다.
		 *
		 * <p>{@code writer_} 로 {@code writer_id}·{@code writer_nickname} 을 함께 본다.
		 */
		@Test
		@DisplayName("상세 조회가 발행한 post UPDATE 는 view_count 만 싣는다")
		void 조회의_UPDATE_는_좁다() {
			Post post = 게시글을_만든다(조회_문장_대역);
			this.발행된_문장.비운다();

			this.postService.getDetail(post.getId());

			List<String> post_UPDATE = this.발행된_문장.발행된_post_UPDATE();
			assertThat(post_UPDATE)
					.as("수집기가 꽂히지 않았으면 빈 목록이고, 엔티티가 더티로 남으면 두 문장이다")
					.hasSize(1);
			assertThat(post_UPDATE.get(0))
					.as("엔티티 더티 체킹의 전체 컬럼 UPDATE 가 아니다 "
							+ "(sp-docs/plan/phase1.md §6 B-11)")
					.contains("view_count")
					.doesNotContain("comment_count", "title", "content", "writer_");
		}

		/**
		 * 검증표 <b>"상세 조회 1회 → {@code view_count} 가 1 증가하고 응답이
		 * sp-docs/api-contract.md §4.3 의 여덟 키다"</b>. 수단을 좁은 UPDATE 로 바꾼 뒤에도
		 * P-05 의 동작과 §4.3 의 모양이 그대로인 것을 본다 (B-05 의 완료 기준).
		 *
		 * <p><b>커밋된 값과 응답의 값을 함께 본다.</b> 좁은 UPDATE 는 영속성 컨텍스트의
		 * 엔티티를 지나지 않으므로 둘이 어긋날 수 있다 — DB 는 올랐는데 응답이 증가 전 값을
		 * 싣는 상태가 그것이다.
		 *
		 * <p>기대 키는 <b>리터럴</b>이다. DTO 의 레코드 컴포넌트에서 뽑으면 필드가 늘거나 줄
		 * 때 기대값이 함께 움직여 단언이 아무것도 보지 못한다. HTTP 응답 쪽의 같은 단언은
		 * {@code PostControllerTest} 가 본다.
		 */
		@Test
		@DisplayName("상세 조회 1회면 view_count 가 1 증가하고 응답 키가 여덟이다")
		void 상세_조회_1회의_조회수와_응답_모양() throws Exception {
			Post post = 게시글을_만든다(응답_모양_대역);

			PostDetailResponse detail = this.postService.getDetail(post.getId());

			assertThat(커밋된_조회수(post.getId()))
					.as("상세 조회 시 view_count 가 1 증가한다 (P-05, "
							+ "sp-docs/requirements/board.md §1)")
					.isEqualTo(1L);
			assertThat(detail.viewCount())
					.as("응답에도 증가된 값이 실린다 — 저장만 하고 옛 값을 돌려주면 "
							+ "클라이언트가 증가를 보지 못한다")
					.isEqualTo(1L);
			Map<String, Object> serialized = this.objectMapper.readValue(
					this.objectMapper.writeValueAsString(detail),
					new TypeReference<Map<String, Object>>() { });
			assertThat(serialized.keySet())
					.as("상세는 목록 일곱에 content 를 더한 여덟이다 "
							+ "(sp-docs/api-contract.md §4.3)")
					.isEqualTo(Set.of(
							"id", "title", "content", "writerId", "writerNickname",
							"viewCount", "commentCount", "createdAt"));
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

		/**
		 * 댓글 작성을 <b>다른 스레드에서 커밋까지</b> 돌리고 실패를 돌려준다 (없으면
		 * {@code null}).
		 *
		 * <p>부르는 쪽이 조회 트랜잭션 안이므로 같은 스레드로 부를 수 없다 — 그러면 그
		 * 트랜잭션에 참여해 따로 커밋되지 않는다.
		 */
		private Throwable 다른_스레드에서_댓글을_작성한다(Long postId) {
			AtomicReference<Throwable> 실패 = new AtomicReference<>();
			Thread 작성 = new Thread(() -> {
				try {
					this.commentService.create(postId, 끼워넣기_대역,
							new CommentCreateRequest("끼워넣은 댓글"));
				}
				catch (Throwable ex) {
					실패.set(ex);
				}
			});
			작성.start();
			try {
				작성.join(집결_한도_초 * 1000L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return ex;
			}
			if (작성.isAlive()) {
				작성.interrupt();
				return new IllegalStateException("끼워넣은 댓글 작성이 한도 안에 커밋되지 않았다");
			}
			return 실패.get();
		}

		private Post 게시글을_만든다(long writerId) {
			return this.postRepository.saveAndFlush(Post.builder()
					.writerId(writerId)
					.writerNickname("글작성자")
					.title("조회수 증가 대상")
					.content("본문")
					.build());
		}

		/** JPA 를 거치지 않고 커밋된 값을 읽는다. 다른 스레드가 커밋한 것을 봐야 한다. */
		private long 커밋된_조회수(Long postId) {
			Long count = this.jdbcTemplate.queryForObject(
					"SELECT view_count FROM post WHERE id = ?", Long.class, postId);
			return count == null ? 0 : count;
		}

		/**
		 * {@code comment_count} 를 <b>조회 트랜잭션에 묶이지 않은 커넥션</b>으로 읽는다.
		 *
		 * <p>{@link DataSource#getConnection()} 을 직접 부른다 — {@code JdbcTemplate} 은
		 * {@code DataSourceUtils} 를 지나 현재 트랜잭션의 커넥션을 쓰므로, 훅 안에서 부르면
		 * 아직 커밋되지 않은 변경까지 보인다. 커밋된 값만 보여야 "다른 트랜잭션이 우리 쓰기
		 * 전에 따로 커밋했다" 가 관측된다.
		 */
		private int 조회_트랜잭션_밖에서_읽은_집계(Long postId) {
			try (Connection 묶이지_않은_커넥션 = this.dataSource.getConnection();
					PreparedStatement 문장 = 묶이지_않은_커넥션.prepareStatement(
							"SELECT comment_count FROM post WHERE id = ?")) {
				문장.setLong(1, postId);
				try (ResultSet 결과 = 문장.executeQuery()) {
					if (!결과.next()) {
						throw new IllegalStateException("픽스처 게시글이 없다: " + postId);
					}
					return 결과.getInt(1);
				}
			}
			catch (SQLException ex) {
				throw new IllegalStateException("묶이지 않은 커넥션으로 집계를 읽지 못했다", ex);
			}
		}

		private int 커밋된_집계(Long postId) {
			Integer count = this.jdbcTemplate.queryForObject(
					"SELECT comment_count FROM post WHERE id = ?", Integer.class, postId);
			return count == null ? 0 : count;
		}

		private int 커밋된_활성_댓글_수(Long postId) {
			Integer count = this.jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM comment WHERE post_id = ? AND deleted = FALSE",
					Integer.class, postId);
			return count == null ? 0 : count;
		}

	}

	/**
	 * 발행된 SQL 을 모으고, 지정한 문장이 <b>발행되기 직전에</b> 훅을 돌린다. Hibernate 의
	 * {@code StatementInspector} 라 문장이 JDBC 로 넘어가기 전에 불린다.
	 *
	 * <p><b>로그·통계 설정에 기대지 않는다.</b> {@code org.hibernate.SQL} 로거나
	 * {@code show-sql}·{@code generate_statistics} 를 읽는 수단은 그 설정이 꺼지면 조용히 빈
	 * 결과를 돌려준다. 이 수집기는 테스트가 직접 꽂고, 꽂히지 않으면 단언이 빈 목록에서
	 * 터진다 (sp-docs/process/review-policy.md §4.1).
	 *
	 * <p>{@code CommentServiceTest} 에 같은 모양의 수집기가 있다. 그쪽은 package-private 이고
	 * 다른 패키지라 여기서 쓸 수 없다 — 끌어올리려면 B-10 의 테스트 파일을 함께 고쳐야 한다.
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

		/** {@code post} 를 바꾸는 UPDATE 만. 소문자·공백이 정규화된 문장이다. */
		List<String> 발행된_post_UPDATE() {
			return this.발행된_문장.stream()
					.map(StatementRecorder::정규화)
					.filter(sql -> sql.startsWith("update post"))
					.toList();
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

	/** sp-docs/api-contract.md §6 의 Claim 으로 만든 주체. {@code nickname} 은 없다. */
	private static LoginMember user(long accountId) {
		return new LoginMember(accountId, ROLE_USER);
	}

	private static LoginMember admin(long accountId) {
		return new LoginMember(accountId, ROLE_ADMIN);
	}

	private Post savedPost(long writerId, String nickname, String title, String content) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname(nickname)
				.title(title)
				.content(content)
				.build());
	}

	private Comment savedComment(Post post, long writerId, String content) {
		return this.commentRepository.saveAndFlush(Comment.builder()
				.post(post)
				.writerId(writerId)
				.writerNickname("댓글작성자")
				.content(content)
				.build());
	}

	private long 활성_댓글_수(Long postId) {
		Long count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM comment WHERE post_id = ? AND deleted = FALSE",
				Long.class, postId);
		return count == null ? 0 : count;
	}

	private int rowCount(long writerId) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM post WHERE writer_id = ?", Integer.class, writerId);
		return count == null ? 0 : count;
	}

}
