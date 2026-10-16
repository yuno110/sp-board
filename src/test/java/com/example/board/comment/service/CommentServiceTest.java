package com.example.board.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.board.client.MemberClient;
import com.example.board.comment.dto.CommentCreateRequest;
import com.example.board.comment.dto.CommentPageCondition;
import com.example.board.comment.dto.CommentResponse;
import com.example.board.comment.dto.CommentUpdateRequest;
import com.example.board.comment.entity.Comment;
import com.example.board.comment.repository.CommentRepository;
import com.example.board.global.common.PageResponse;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.global.security.LoginMember;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
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
 * <p>픽스처의 {@code writerId} 는 801~829 를 쓴다. 같은 JVM 의 모든 테스트가 하나의 H2 DB 를
 * 보므로 (sp-docs/tech-stack.md §5.3) 이미 쓰이는 대역과 겹치지 않게 둔다. 이 클래스는
 * 트랜잭션 롤백에 기대지 않고 — 작성 경로가 스스로 커밋한다 — {@code @AfterEach} 가 자기
 * 대역만 지운다. <b>테이블 전체를 지우지 않는다</b> — 다른 클래스의 픽스처를 없애면 그쪽이
 * 간헐 실패한다. {@code comment.post_id} 에 FK 가 있으므로 ({@code fk_comment_post_id})
 * 댓글을 먼저 지운다.
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
	 * <p>완료 기준 "댓글 작성 시 {@code post.comment_count} 가 1 증가한다
	 * ({@code Post.increaseCommentCount()} 사용)" 가 여기 있다.
	 */
	@Test
	@DisplayName("정상 작성이면 댓글이 저장되고 comment_count 가 1 증가한다")
	void 정상_작성() {
		given(this.memberClient.findActiveNickname(801L)).willReturn(MEMBER_NICKNAME);
		Post post = savedPost(801L);

		CommentResponse created = this.commentService.create(
				post.getId(), 801L, new CommentCreateRequest("첫 댓글"));

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
				post.getId(), 802L, new CommentCreateRequest("작성자 확인"));

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
				post.getId(), 803L, new CommentCreateRequest("닉네임 확인"));

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

		this.commentService.create(post.getId(), 804L, new CommentCreateRequest("순서"));

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
				987654323L, 805L, new CommentCreateRequest("없는 글에 쓰는 댓글")))
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
	 * <p><b>가드는 {@link Post#decreaseCommentCount()} 에 있고 그 메서드 자체는 B-03 의
	 * {@code PostRepositoryTest} 가 본다.</b> 여기서 보는 것은 <b>서비스가 그 가드를
	 * 지나는가</b>다 — 집계를 벌크 UPDATE({@code comment_count = comment_count - 1})로 깎는
	 * 구현은 가드를 지나지 않고 {@code -1} 을 커밋한다.
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

		PageResponse<CommentResponse> page = this.commentService.getList(
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
	 * <p><b>저장 순서와 시각 순서를 어긋나게 둔다.</b> 연속 저장만 하면 H2 에서
	 * {@code created_at} 오름차순과 {@code id} 오름차순이 같은 순서가 되어, {@code id} 로만
	 * 정렬하는 구현과 구별되지 않는다. 시각을 저장 순서의 <b>역순</b>으로 박으면 기대 순서가
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

		PageResponse<CommentResponse> page = this.commentService.getList(
				post.getId(), CommentPageCondition.of(null, null));

		assertThat(page.size())
				.as("기본 크기는 20 이다 (sp-docs/requirements/board.md §2 의 C-02)")
				.isEqualTo(20);
		assertThat(page.content()).extracting(CommentResponse::id)
				.as("등록순은 created_at ASC, id ASC 다 (sp-docs/api-contract.md §7.1)")
				.containsExactlyElementsOf(기대_순서.subList(0, 20));
		assertThat(page.totalElements()).isEqualTo(21);
		assertThat(page.totalPages()).isEqualTo(2);
	}

	/**
	 * 검증표 "삭제된 댓글 포함 상태 → 목록에서 제외. <b>건수 쿼리도 제외한다</b>"
	 * (sp-docs/requirements/board.md §5 규칙 1).
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
	@DisplayName("삭제된 댓글은 목록과 건수에서 모두 제외된다")
	void 삭제된_댓글은_목록에서_제외된다() {
		Post post = savedPost(819L);
		Long first = savedComment(post, 819L, "남는 댓글 하나").getId();
		Long second = savedComment(post, 819L, "남는 댓글 둘").getId();
		Long third = savedComment(post, 819L, "남는 댓글 셋").getId();
		Comment removed = savedComment(post, 819L, "삭제된 댓글");
		removed.softDelete();
		this.commentRepository.saveAndFlush(removed);
		savedComment(savedPost(820L), 820L, "다른 글의 댓글");

		PageResponse<CommentResponse> 전체 = this.commentService.getList(
				post.getId(), CommentPageCondition.of(null, null));
		PageResponse<CommentResponse> 꽉_찬_페이지 = this.commentService.getList(
				post.getId(), CommentPageCondition.of(0, 2));

		assertThat(전체.content()).extracting(CommentResponse::id)
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
	 * <p><b>동률을 만들려면 시각을 직접 박아야 한다.</b> H2 는 마이크로초를 유지하므로 연속
	 * 저장만으로는 동률이 재현되지 않는다 — MySQL {@code DATETIME} 은 초 단위라 같은 초에
	 * 등록된 댓글이 그냥 동률이다 (§7.1, sp-docs/tech-stack.md §6).
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
					.content().stream().map(CommentResponse::id).toList());
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
	 * <p>빈 둘을 스파이로 바꿔야 하므로 중첩 클래스이고, 바깥 클래스의 {@code commentService}
	 * 에는 스파이가 아닌 원래 빈이 들어 있으므로 여기서 다시 주입받는다.
	 *
	 * <table border="1">
	 * <caption>{@code create} 변이별 실패 (이 항목에서 전체 스위트로 측정)</caption>
	 * <tr><th>변이</th><th>무엇이 깨지나</th><th>실측 결과</th></tr>
	 * <tr><td><b>A</b> — 트랜잭션 템플릿 제거 (본문을 그대로 실행)</td>
	 * <td>경계가 아예 없다</td>
	 * <td><b>4개 실패.</b> {@link #둘_다_롤백된다()} 에서 <b>댓글 행 단언</b>이 빨개진다
	 * (0 기대, 1 관측 — 주입한 INSERT 가 그 자리에서 커밋됐다). 집계 단언은 통과한다 —
	 * 게시글이 분리 상태라 증가가 아예 쓰이지 않는다. 그래서 정상 경로의
	 * {@link CommentServiceTest#정상_작성()} 등 셋이 집계 0 으로 빨개진다.
	 * <b>{@link #커밋이_정확히_한_번이다()} 는 통과한다</b> — {@code saveAndFlush} 가 자기
	 * 트랜잭션을 열어 집계가 1 그대로다</td></tr>
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
	 * <p><b>두 단언이 각각 반증 가능하다</b>(§4.1) — A 가 댓글 행 단언을, B 가 집계 단언을
	 * 따로 빨갛게 만들었다. 그래서 {@link #둘_다_롤백된다()} 는 집계 단언을 앞에 둔다. 통과하는
	 * 것으로는 부족하고 깨뜨려 봐야 알 수 있는 부분이다.
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
	class 작성_트랜잭션_경계 {

		/** 댓글 저장 지점에 실패를 주입한다. 나머지 메서드는 실제 빈에 위임된다. */
		@MockitoSpyBean
		private CommentRepository spyCommentRepository;

		/**
		 * <b>경계를 관측하는 지점이다.</b> {@code TransactionTemplate} 과
		 * {@code TransactionInterceptor} 가 이 빈으로 트랜잭션을 열고 닫으므로 커밋이 전부
		 * 여기를 지난다.
		 */
		@MockitoSpyBean
		private PlatformTransactionManager spyTransactionManager;

		/** 위 스파이들을 주입받은 쪽이다. 바깥 클래스의 것과 다른 인스턴스다. */
		@Autowired
		private CommentService commentService;

		@Autowired
		private PostRepository postRepository;

		/**
		 * <b>바깥 클래스의 목 필드를 쓰지 않고 다시 주입받는다.</b> 스파이 둘이 더해져 이
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
		 * <p><b>{@code isNewTransaction()} 이 참인 커밋만 센다.</b> 참여 트랜잭션도 매니저의
		 * {@code commit} 을 지나지만 그것은 물리 트랜잭션의 커밋이 아니므로, 빼고 남는 수가
		 * 실제로 커밋된 트랜잭션의 수다. 이 경로의 {@code saveAndFlush} 는
		 * {@code SimpleJpaRepository} 의 {@code @Transactional} 을 지나 템플릿의 트랜잭션에
		 * 참여하므로 그 커밋이 필터에 걸러진다.
		 *
		 * <p><b>단언은 "정확히 한 번" 에만 걸고 다른 수치를 예측값으로 쓰지 않는다</b>(§4.1).
		 */
		@Test
		@DisplayName("한 번의 작성에 물리 트랜잭션이 정확히 하나 커밋된다")
		void 커밋이_정확히_한_번이다() {
			given(this.memberClient.findActiveNickname(822L)).willReturn(MEMBER_NICKNAME);
			Post post = 게시글을_만든다(822L);

			// 준비가 끝난 뒤에 센다. 세는 구간을 create 한 번으로 좁혀 둔다
			List<TransactionStatus> 커밋된_물리_트랜잭션 = new ArrayList<>();
			willAnswer(invocation -> {
				TransactionStatus status = invocation.getArgument(0);
				if (status.isNewTransaction()) {
					커밋된_물리_트랜잭션.add(status);
				}
				return invocation.callRealMethod();
			}).given(this.spyTransactionManager).commit(any(TransactionStatus.class));

			this.commentService.create(post.getId(), 822L, new CommentCreateRequest("경계 관측"));

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
					post.getId(), 823L, new CommentCreateRequest("롤백될 댓글")))
					.isInstanceOf(DataIntegrityViolationException.class);

			// 두 단언의 순서가 측정에서 중요하다. 집계를 먼저 보면 변이 A 에서 집계는
			// 통과하고 댓글 단언이 빨개져 그 단언이 반증 가능함이 드러나고, 변이 B 에서는
			// 집계 단언 자체가 빨개진다 — 두 단언이 각각 반증 가능해야 한다
			// (sp-docs/process/review-policy.md §4.1). 거꾸로 두면 둘 다 댓글 단언에서
			// 멈춰 집계 단언이 일하는지 보이지 않는다
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
				post.getId(), writerId, new CommentCreateRequest(content));
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
