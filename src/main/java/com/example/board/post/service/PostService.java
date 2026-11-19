package com.example.board.post.service;

import com.example.board.attachment.dto.AttachmentResponse;
import com.example.board.attachment.repository.AttachmentRepository;
import com.example.board.client.MemberClient;
import com.example.board.comment.repository.CommentRepository;
import com.example.board.global.common.PageResponse;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.global.security.ContractRole;
import com.example.board.global.security.LoginMember;
import com.example.board.like.repository.PostLikeRepository;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostDetailResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostSearchCondition;
import com.example.board.post.dto.PostUpdateRequest;
import com.example.board.post.entity.Post;
import com.example.board.post.entity.PostCategory;
import com.example.board.post.repository.PostQueryRepository;
import com.example.board.post.repository.PostRepository;
import com.example.board.post.support.PostReader;
import com.example.board.post.support.ViewCountJudge;
import com.example.board.post.support.ViewerKeyFactory;
import java.util.List;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 게시글 쓰기·조회. 정본: sp-docs/requirements/board.md §1 §3 §3.0 §5 §6 §7 §8,
 * sp-docs/api-contract.md §4 §4.1 §4.2 §4.3 §4.4 §5.1, sp-docs/security.md §5.
 *
 * <p>트랜잭션 경계는 서비스에 둔다 (sp-docs/conventions.md §5).
 *
 * <p><b>소유자 검증도 여기다</b> (sp-docs/security.md §5.1,
 * sp-docs/requirements/board.md §5 규칙 5). Controller 나 Security 설정에 두지 않는다 —
 * 리소스를 조회해야 판정할 수 있다.
 */
@Service
public class PostService {

	private final PostRepository postRepository;

	private final PostQueryRepository postQueryRepository;

	private final PostReader postReader;

	private final MemberClient memberClient;

	/**
	 * 게시글 삭제 시 하위 댓글을 함께 논리 삭제한다 (sp-docs/requirements/board.md §5 규칙 3).
	 * {@code softDeleteByPostId} 는 B-03 산출물이다 — B-08 을 기다리지 않는다
	 * (sp-docs/plan/phase1.md §2.5).
	 */
	private final CommentRepository commentRepository;

	/**
	 * 작성의 DB 트랜잭션. <b>{@code @Transactional} 로 메서드 전체를 감쌀 수 없어서</b> 템플릿을
	 * 쓴다 — 감싸면 원격 호출이 그 안으로 들어간다. 메서드를 둘로 쪼개 한쪽에만 애노테이션을
	 * 붙이는 방법은 자기 호출이라 프록시를 지나지 않아 <b>경계가 아예 생기지 않는다.</b>
	 */
	private final TransactionTemplate insertTransaction;

	/** 상세 조회의 조회수 판정 (P-05). 자기 트랜잭션을 연다 — {@link #getDetail} 의 주석. */
	private final ViewCountJudge viewCountJudge;

	private final ViewerKeyFactory viewerKeyFactory;

	/** 상세·수정 응답의 {@code liked} (sp-docs/api-contract.md §4.3, sp-docs/conventions.md §1.3). */
	private final PostLikeRepository postLikeRepository;

	/** 상세·수정 응답의 {@code attachments} (sp-docs/api-contract.md §4.3, sp-docs/conventions.md §1.3). */
	private final AttachmentRepository attachmentRepository;

	/**
	 * 상세 조회의 읽기 트랜잭션. <b>{@code @Transactional} 로 메서드 전체를 감쌀 수 없어서</b>
	 * 템플릿을 쓴다 — 감싸면 판정이 그 트랜잭션에 참여한다 ({@link #getDetail}).
	 */
	private final TransactionTemplate readTransaction;

	public PostService(
			PostRepository postRepository,
			PostQueryRepository postQueryRepository,
			PostReader postReader,
			MemberClient memberClient,
			CommentRepository commentRepository,
			ViewCountJudge viewCountJudge,
			ViewerKeyFactory viewerKeyFactory,
			PostLikeRepository postLikeRepository,
			AttachmentRepository attachmentRepository,
			PlatformTransactionManager transactionManager) {

		this.postRepository = postRepository;
		this.postQueryRepository = postQueryRepository;
		this.postReader = postReader;
		this.memberClient = memberClient;
		this.commentRepository = commentRepository;
		this.viewCountJudge = viewCountJudge;
		this.viewerKeyFactory = viewerKeyFactory;
		this.postLikeRepository = postLikeRepository;
		this.attachmentRepository = attachmentRepository;
		this.insertTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setReadOnly(true);
	}

	/**
	 * 게시글 작성 (P-01).
	 *
	 * <p><b>순서가 전부다 — 조회 → 성공 확인 → BEGIN → INSERT → COMMIT</b>
	 * (sp-docs/requirements/board.md §3 규칙 8). sp-docs/architecture.md §5 가 트랜잭션 안의
	 * 원격 호출을 금지한다: DB 커넥션이 member 의 네트워크 지연만큼 점유된다.
	 *
	 * <p><b>공지 권한(403 {@code P003})은 그 조회보다 먼저 판정한다</b>
	 * (sp-docs/requirements/board.md §8 의 판정 순서). 「Claim 만으로 정해지는 거부에 원격
	 * 호출을 쓰지 않는다」 — 그래서 프로필이 없는 USER 가 공지로 쓰면 {@code S002} 가 아니라
	 * {@code P003} 이고 {@code MemberClient} 를 부르지 않는다. 카테고리를 생략하면
	 * {@code FREE} 이므로 판정할 것이 없다 — 그 기본값은 {@code Post} 의 생성자가 채운다.
	 *
	 * <p>{@code writer_id} 는 <b>검증된 JWT 의 {@code sub}</b>({@code loginMember.accountId()}),
	 * {@code writer_nickname} 은 <b>{@code MemberClient} 응답</b>에서만 온다 (§3 규칙 1·2).
	 * JWT Claim 에 {@code nickname} 이 없기 때문이다 (sp-docs/adr/0012 §3). <b>조회 키도 그
	 * {@code sub} 다</b> — 요청 본문의 {@code writerId} 로 조회하지 않는다 (§3 규칙 3).
	 *
	 * <p>{@code MemberClient} 가 내는 {@code S002}(403, 프로필 없음·탈퇴)와
	 * {@code S001}(503, 호출 실패)을 <b>여기서 다시 감싸지 않는다.</b>
	 * {@code GlobalExceptionHandler} 가 그대로 변환하고, 둘 다 저장 전에 터지므로 글이
	 * 저장되지 않는다 (sp-docs/api-contract.md §5.1).
	 *
	 * <p><b>확인과 커밋 사이의 창은 닫을 수 없다.</b> 프로필 확인 뒤 커밋 전에 탈퇴가 커밋되면
	 * 탈퇴한 프로필의 글이 생성될 수 있다. 원격 호출을 트랜잭션 안에 넣을 수 없으므로 구조적인
	 * 것이고, "탈퇴 후 작성 불가" 는 보장이 아니라 통상 동작이다
	 * (sp-docs/requirements/board.md §3.0).
	 *
	 * @param loginMember 검증된 JWT 의 {@code sub}·{@code role}
	 */
	public PostDetailResponse create(LoginMember loginMember, PostCreateRequest request) {
		// 0) Claim 만으로 정해지는 거부라 원격 호출보다 먼저다 (§8)
		if (request.category() == PostCategory.NOTICE) {
			validateNoticeRole(loginMember);
		}

		// 1) 트랜잭션 밖. 실패하면 아래로 내려가지 않으므로 BEGIN 자체가 일어나지 않는다
		String writerNickname = this.memberClient.findActiveNickname(loginMember.accountId());

		// 2) BEGIN -> INSERT -> COMMIT
		Post saved = this.insertTransaction.execute(status -> this.postRepository.saveAndFlush(
				Post.builder()
						.writerId(loginMember.accountId())
						.writerNickname(writerNickname)
						.title(request.title())
						.content(request.content())
						.category(request.category())
						.build()));

		// 방금 만든 글이라 누른 사람도 첨부도 없다 (sp-docs/api-contract.md §4.4) — 첨부는 글을 만든 뒤
		// 올리므로 조회하지 않는다
		return PostDetailResponse.from(saved, false, List.of());
	}

	/**
	 * 게시글 상세 조회 (P-04) 와 조회수 판정 (P-05). <b>인증은 선택이다</b>
	 * (sp-docs/api-contract.md §4.8).
	 *
	 * <p><b>{@code MemberClient} 를 호출하지 않는다.</b> 작성자 닉네임은 작성 시점 스냅샷이
	 * 게시글에 있고, 조회 경로의 board → member 호출은 금지되어 있다
	 * (sp-docs/api-contract.md §4.2, sp-docs/architecture.md §5).
	 *
	 * <p><b>트랜잭션을 나눠 차례로 끝낸다</b> — 404 판정의 읽기, 조회수 판정, 응답의 읽기다.
	 * <ol>
	 * <li><b>404 판정이 먼저다</b> — 없는·삭제된 글은 {@code P001} 이고 판정 행이 생기지 않는다
	 * (sp-docs/domain-model.md §4.5). {@link PostReader#requireActive} 는 엔티티를 읽지 않는다 —
	 * 그 이유는 그 메서드에 있다.
	 * <li><b>조회수 판정은 이 메서드의 트랜잭션이 아니다</b> (sp-docs/requirements/board.md §9.3).
	 * 판정 INSERT 의 UNIQUE 충돌이 그 트랜잭션을 되돌림 전용으로 만들므로, 응답을 만드는 트랜잭션과
	 * 같으면 상세 조회가 500 이 된다. {@link ViewCountJudge} 가 자기 트랜잭션을 열고 커밋까지 끝낸다.
	 * <li><b>응답은 판정 커밋 뒤에 시작된 읽기에서 만든다</b> (sp-docs/requirements/board.md
	 * §9.3, sp-docs/api-contract.md §4.8). MySQL 의 반복 읽기에서는 트랜잭션의 첫 읽기가 스냅샷을
	 * 정하므로, 판정 전에 연 트랜잭션에서 읽으면 판정이 커밋한 +1 을 보지 못한다
	 * (sp-docs/requirements/board.md §9.3).
	 * </ol>
	 *
	 * <p><b>그래서 이 메서드에 {@code @Transactional} 을 붙이지 않는다.</b> 붙이면 판정과 읽기의
	 * 템플릿이 그 트랜잭션에 참여한다 — 판정이 응답의 트랜잭션과 갈라지지 않고, 응답의 읽기가 판정
	 * 전에 시작된 트랜잭션 안이 된다. 읽기는 {@code readOnly} 다 (sp-docs/conventions.md §5).
	 *
	 * <p><b>404 판정과 응답의 읽기 사이에 창이 있다.</b> 그 사이에 글 삭제가 커밋되면 판정 행과 +1
	 * 이 삭제된 글에 남을 수 있고 응답은 404 {@code P001} 이다 (코드 읽기). 판정은 글의 삭제 여부를
	 * 다시 보지 않는다. 삭제된 글은 보이지 않으므로 드러나지 않는다.
	 *
	 * @param loginMember 유효한 토큰의 계정. 토큰이 없으면 {@code null} — 유효하지 않은 토큰은
	 *        여기 오지 않고 401 이다 (sp-docs/api-contract.md §4.8)
	 * @param remoteAddr TCP 연결의 상대 주소. 비로그인 조회자의 판정 키에만 쓴다
	 *        (sp-docs/security.md §9)
	 */
	public PostDetailResponse getDetail(Long id, @Nullable LoginMember loginMember, String remoteAddr) {
		// 1) 404 — 판정보다 먼저다
		this.readTransaction.executeWithoutResult(status -> this.postReader.requireActive(id));

		// 2) 판정 — 자기 트랜잭션에서 커밋까지 끝난다
		this.viewCountJudge.judge(id, this.viewerKeyFactory.keyOf(loginMember, remoteAddr));

		// 3) 판정 커밋 뒤에 시작한 읽기
		return this.readTransaction.execute(status -> PostDetailResponse.from(
				this.postReader.getActiveById(id), likedBy(id, loginMember), attachmentsOf(id)));
	}

	/**
	 * 상세·수정 응답의 {@code liked}. 토큰이 없으면 {@code null} 이고, 있으면 그 계정이 눌렀는지다
	 * (sp-docs/api-contract.md §4.3 §4.4 §4.8).
	 */
	private Boolean likedBy(Long postId, @Nullable LoginMember loginMember) {
		if (loginMember == null) {
			return null;
		}
		return this.postLikeRepository.existsByPostIdAndAccountId(postId, loginMember.accountId());
	}

	/**
	 * 상세·수정 응답의 {@code attachments}. 그 글의 활성 첨부를 올린 순서({@code id} 오름차순)로
	 * 싣는다 (sp-docs/api-contract.md §4.3, sp-docs/requirements/board.md §11 의 「상세 응답」 줄).
	 *
	 * <p><b>{@code attachment} 를 읽는 문장은 하나다</b> — 그 글의 활성 첨부를 한 번에 읽는다. 첨부 하나의
	 * 모양(§4.7)에 글의 값이 없어 첨부의 {@code post} 연관을 건드리지 않는다. 순서는 조회의
	 * {@code ORDER BY} 가 정한다 — 그것이 없으면 MySQL 은 순서를 보장하지 않는다.
	 */
	private List<AttachmentResponse> attachmentsOf(Long postId) {
		return this.attachmentRepository.findByPostIdAndDeletedFalseOrderByIdAsc(postId).stream()
				.map(AttachmentResponse::from)
				.toList();
	}

	/**
	 * 게시글 목록·검색 (P-02, P-03). <b>비로그인 허용이다</b> (sp-docs/api-contract.md §4).
	 *
	 * <p><b>{@code MemberClient} 를 호출하지 않는다.</b> 목록에 실리는 작성자 닉네임은 작성
	 * 시점 스냅샷이고, 조회 경로의 원격 호출은 0회가 기준이다 (sp-docs/nfr.md §1,
	 * sp-docs/api-contract.md §4.2). {@code commentCount} 도 {@code post} 에 비정규화되어
	 * 있어 집계 쿼리가 없다 (sp-docs/domain-model.md §4.1).
	 *
	 * <p>목록 항목은 {@link PostResponse} 다 — 본문을 싣지 않는다
	 * (sp-docs/api-contract.md §4.3). 한 페이지가 {@link PostSearchCondition#MAX_SIZE} 건이면
	 * 본문만 500,000자가 된다.
	 *
	 * <p>삭제된 게시글 제외·검색 조건·정렬은 {@link PostQueryRepository} 가 쿼리로 처리한다.
	 */
	@Transactional(readOnly = true)
	public PageResponse<PostResponse> getList(PostSearchCondition condition) {
		return PageResponse.of(this.postQueryRepository.search(condition).map(PostResponse::from));
	}

	/**
	 * 내가 쓴 글 목록 (P-08). <b>인증이 필요하다</b> (sp-docs/api-contract.md §4).
	 *
	 * <p><b>조회 키는 검증된 JWT 의 {@code sub} 다</b> ({@code accountId} 파라미터) —
	 * 쿼리 파라미터의 {@code writerId} 로 조회하지 않는다
	 * (sp-docs/requirements/board.md §3 규칙 3, sp-docs/api-contract.md §4.1). 받으면 아무
	 * 토큰으로나 남의 글 목록을 꺼낼 수 있다.
	 *
	 * <p>응답 항목은 {@link #getList} 와 <b>같은 모양</b>이다 (sp-docs/api-contract.md §4.3) —
	 * 목록 모양은 하나이고 이 경로가 두 번째를 만들지 않는다.
	 *
	 * <p><b>{@code MemberClient} 를 호출하지 않는다</b> (§4.2). {@link #getList} 와 같은
	 * 이유다 — 조회 경로의 원격 호출은 0회가 기준이다 (sp-docs/nfr.md §1).
	 *
	 * <p>삭제된 글 제외와 정렬은 {@link PostQueryRepository} 가 쿼리로 처리한다. 마지막 정렬
	 * 키가 {@code id} 라 동률이 있어도 페이지 경계가 안정적이다 (§7.1).
	 *
	 * @param accountId 검증된 JWT 의 {@code sub}
	 */
	@Transactional(readOnly = true)
	public PageResponse<PostResponse> getMyPosts(Long accountId, PostSearchCondition condition) {
		return PageResponse.of(this.postQueryRepository
				.searchByWriter(accountId, condition)
				.map(PostResponse::from));
	}

	/**
	 * 게시글 수정 (P-06). <b>작성자 본인만 할 수 있다</b> — ADMIN 도 못 한다
	 * (sp-docs/security.md §5.2, sp-docs/requirements/board.md §5 규칙 6).
	 *
	 * <p><b>{@code MemberClient} 를 호출하지 않는다.</b> 수정 시 작성자 스냅샷을 갱신하지
	 * 않으므로 닉네임을 다시 얻을 이유가 없다 (sp-docs/requirements/board.md §3 규칙 7,
	 * sp-docs/api-contract.md §4.2). 활성 프로필도 요구하지 않는다 — 생성에만 필요하다
	 * (§5 규칙 8). 그래서 member 장애가 수정을 막지 않는다.
	 *
	 * <p>응답은 <b>상세와 같은 모양</b>이다 (sp-docs/api-contract.md §4.3). 모양은 둘뿐이고
	 * 쓰기 응답이 세 번째를 만들지 않는다.
	 *
	 * <p>없는 id·삭제된 게시글은 {@link PostReader} 가 404 {@code P001} 로 만든다 —
	 * <b>조회가 권한 판정보다 먼저다</b> (sp-docs/api-contract.md §4.4). 타인의 삭제된 글도
	 * {@code P001} 이고 {@code P002} 가 아니다. 정보 노출이 아니다: 상세 조회가 비로그인에
	 * 열려 있어 존재 여부는 이미 공개 정보이고 (§4.2), 403 을 먼저 내보내면 공개 조회와
	 * 어긋난 두 답이 나간다. <b>비공개 자원이라면 반대가 맞다</b> — 1차의 board 에는 그런
	 * 자원이 없다 (§4.4).
	 *
	 * <p><b>공지 권한(403 {@code P003})은 소유권 다음이다</b> — 404 {@code P001} → 403
	 * {@code P002} → 403 {@code P003} (sp-docs/requirements/board.md §8 의 판정 순서). 그래서
	 * 남의 글을 공지로 바꾸려는 요청은 ADMIN 이어도 {@code P002} 다 (§8 의 "ADMIN 과 남의 글").
	 * 판정은 카테고리가 {@code NOTICE} 로 바뀌거나 {@code NOTICE} 에서 바뀔 때만 하고, 바뀌지
	 * 않는 수정(생략하거나 같은 값)은 거치지 않는다 (§8) — 그 조건이 {@link #movesNotice} 다.
	 * 생략하면 지금 값을 유지하는 것은 {@code Post.changeCategory} 가 집행한다.
	 */
	@Transactional
	public PostDetailResponse update(Long id, LoginMember loginMember, PostUpdateRequest request) {
		Post post = this.postReader.getActiveById(id);
		validateWriter(post, loginMember);
		if (movesNotice(post.getCategory(), request.category())) {
			validateNoticeRole(loginMember);
		}

		post.update(request.title(), request.content());
		post.changeCategory(request.category());

		// 요청한 계정의 실제 상태다 (sp-docs/api-contract.md §4.4)
		return PostDetailResponse.from(post, likedBy(id, loginMember), attachmentsOf(id));
	}

	/**
	 * 게시글 삭제 (P-07). <b>작성자 본인과 ADMIN 이 할 수 있다</b>
	 * (sp-docs/security.md §5.2). Soft Delete 이므로 행이 남는다.
	 *
	 * <p><b>하위 댓글도 함께 {@code deleted = true} 가 된다</b>
	 * (sp-docs/requirements/board.md §5 규칙 3). {@code comment_count} 는 그대로 둔다
	 * (sp-docs/domain-model.md §5 의 표 셋째 줄).
	 *
	 * <p><b>그 두 효과가 한 로컬 트랜잭션이다</b> (sp-docs/domain-model.md §5). 따로 커밋되면
	 * 둘 중 하나만 반영된 상태가 남는다 — 게시글은 사라졌는데 댓글이 살아 있거나, 댓글만
	 * 지워지고 게시글이 목록에 남는다. 경계는 이 메서드의 {@code @Transactional} 하나이고,
	 * <b>연산마다 자기 트랜잭션을 열지 않는다</b> — 그 변이는 행위 단언으로 잡히지 않으므로
	 * {@code PostServiceTest} 가 커밋 횟수를 직접 센다
	 * (sp-docs/process/review-policy.md §4.1).
	 *
	 * <p>{@code post.deleted} 를 먼저 세우고 댓글을 지운다. {@code softDeleteByPostId} 의
	 * {@code flushAutomatically} 가 그 변경을 같은 트랜잭션 안에서 내보낸다.
	 *
	 * <p><b>{@code MemberClient} 를 호출하지 않는다</b> (sp-docs/api-contract.md §4.2).
	 * {@link #update} 와 같은 이유다.
	 *
	 * <p><b>조회가 권한 판정보다 먼저인 것도 {@link #update} 와 같다</b> — §4.4 는 수정과
	 * 삭제를 함께 못박는다. 타인의 삭제된 글은 404 {@code P001} 이고 403 {@code P002} 가
	 * 아니며, <b>조용한 204 도 아니다.</b>
	 */
	@Transactional
	public void delete(Long id, LoginMember loginMember) {
		Post post = this.postReader.getActiveById(id);
		validateWriterOrAdmin(post, loginMember);

		post.softDelete();
		this.commentRepository.softDeleteByPostId(id);
	}

	/**
	 * 수정의 권한 판정. <b>작성자 본인뿐이다.</b>
	 *
	 * <p><b>{@link #validateWriterOrAdmin} 와 한 메서드로 합치지 않는다.</b> 수정과 삭제의
	 * 권한이 다르다 — ADMIN 은 삭제만 할 수 있다 (sp-docs/security.md §5.2,
	 * sp-docs/requirements/board.md §5 규칙 6). 타인 글의 내용 변조를 막기 위한 것이고,
	 * 부적절 게시물은 삭제로만 처리한다. 한쪽에 플래그를 받는 메서드로 합치면 호출처가 그
	 * 플래그를 잘못 넘기는 순간 ADMIN 이 타인 글을 고칠 수 있게 된다.
	 */
	private static void validateWriter(Post post, LoginMember loginMember) {
		if (!post.getWriterId().equals(loginMember.accountId())) {
			throw new BusinessException(ErrorCode.POST_FORBIDDEN);
		}
	}

	/**
	 * 삭제의 권한 판정. <b>작성자 본인 또는 ADMIN 이다</b> (sp-docs/security.md §5.2).
	 *
	 * <p>판정은 검증된 JWT 의 {@code sub}·{@code role} 만으로 한다 — 권한을 다른 서비스에
	 * 되묻지 않는다 (sp-docs/requirements/board.md §7). 오프라인 검증이므로 권한 박탈이 기존
	 * 토큰에 즉시 반영되지 않고, ADMIN 토큰의 잔여 권한에는 타인 글 삭제가 포함된다
	 * (sp-docs/security.md §5.3) — 1차가 비용으로 수용한 것이다.
	 */
	private static void validateWriterOrAdmin(Post post, LoginMember loginMember) {
		if (ContractRole.ADMIN.equals(loginMember.role())) {
			return;
		}
		validateWriter(post, loginMember);
	}

	/**
	 * 공지 권한. <b>{@code role} 이 {@code ADMIN} 과 같을 때만 허용한다</b> — 새 ADMIN 판정을
	 * 그 모양으로 적는다 (sp-docs/security.md §5.1). 권한은 §5.2 매트릭스의 공지 줄이 정하고
	 * 규칙은 sp-docs/requirements/board.md §8 이다.
	 *
	 * <p>판정은 검증된 JWT 의 {@code role} 만으로 한다 — {@link #validateWriterOrAdmin} 와 같이
	 * 권한을 다른 서비스에 되묻지 않는다 (sp-docs/requirements/board.md §7).
	 * {@code P002}("남의 글")와 다른 것을 말한다 — {@code P003} 은 "자기 글이지만 공지를 다룰
	 * 역할이 아니다" 다 (§8).
	 */
	private static void validateNoticeRole(LoginMember loginMember) {
		if (!ContractRole.ADMIN.equals(loginMember.role())) {
			throw new BusinessException(ErrorCode.NOTICE_FORBIDDEN);
		}
	}

	/**
	 * 수정이 공지를 다루는가 — 카테고리가 {@code NOTICE} 로 바뀌거나 {@code NOTICE} 에서 다른
	 * 값으로 바뀌는가 (sp-docs/requirements/board.md §8 의 "공지 권한 — 수정").
	 *
	 * <p>생략({@code null})과 같은 값은 바뀌지 않는 수정이라 {@code false} 다. 공지와 무관한 변경
	 * ({@code FREE}·{@code QNA} 사이)도 {@code false} 다 — USER 가 할 수 있다.
	 */
	private static boolean movesNotice(PostCategory current, PostCategory requested) {
		if (requested == null || requested == current) {
			return false;
		}
		return requested == PostCategory.NOTICE || current == PostCategory.NOTICE;
	}

}
