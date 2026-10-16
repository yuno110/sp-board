package com.example.board.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.board.client.MemberClient;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostDetailResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * <p>픽스처의 {@code writerId} 는 501~519 를 쓴다. 같은 JVM 의 모든 테스트가 하나의 H2 DB 를
 * 보므로 (sp-docs/tech-stack.md §5.3) 이미 쓰이는 대역(201~210, 301~317, 401~402,
 * {@code PostControllerTest} 의 521~539)과 겹치지 않게 둔다. 이 클래스는 트랜잭션 롤백에
 * 기대지 않고 — 작성 경로가 스스로 커밋한다 — {@code @AfterEach} 가 자기 대역을 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PostServiceTest {

	private static final long WRITER_ID = 501L;

	/** member 의 내부 API 가 돌려주는 닉네임. 스냅샷에 저장될 유일한 출처다 (§3 규칙 2). */
	private static final String MEMBER_NICKNAME = "member가준닉네임";

	@Autowired
	private PostService postService;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private MemberClient memberClient;

	@AfterEach
	void 픽스처_정리() {
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 501 AND 519");
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

	private Post savedPost(long writerId, String nickname, String title, String content) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname(nickname)
				.title(title)
				.content(content)
				.build());
	}

	private int rowCount(long writerId) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM post WHERE writer_id = ?", Integer.class, writerId);
		return count == null ? 0 : count;
	}

}
