package com.example.board.post.service;

import com.example.board.client.MemberClient;
import com.example.board.comment.repository.CommentRepository;
import com.example.board.global.common.PageResponse;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.global.security.LoginMember;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostDetailResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostSearchCondition;
import com.example.board.post.dto.PostUpdateRequest;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostQueryRepository;
import com.example.board.post.repository.PostRepository;
import com.example.board.post.support.PostReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 게시글 쓰기·조회. 정본: sp-docs/requirements/board.md §1 (P-01 ~ P-07) §3 §3.0 §5 §6 §7,
 * sp-docs/api-contract.md §4 §4.1 §4.2 §4.3 §5.1, sp-docs/security.md §5.
 *
 * <p>트랜잭션 경계는 서비스에 둔다 (sp-docs/conventions.md §5).
 *
 * <p><b>소유자 검증도 여기다</b> (sp-docs/security.md §5.1,
 * sp-docs/requirements/board.md §5 규칙 5). Controller 나 Security 설정에 두지 않는다 —
 * 리소스를 조회해야 판정할 수 있다.
 */
@Service
public class PostService {

	/**
	 * sp-docs/api-contract.md §6 이 정한 {@code role} claim 의 값이다 ({@code USER} |
	 * {@code ADMIN}). <b>권한 판정은 이 Claim 만으로 한다</b> — 권한을 다른 서비스에 되묻지
	 * 않는다 (sp-docs/requirements/board.md §7).
	 */
	private static final String ROLE_ADMIN = "ADMIN";

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

	public PostService(
			PostRepository postRepository,
			PostQueryRepository postQueryRepository,
			PostReader postReader,
			MemberClient memberClient,
			CommentRepository commentRepository,
			PlatformTransactionManager transactionManager) {

		this.postRepository = postRepository;
		this.postQueryRepository = postQueryRepository;
		this.postReader = postReader;
		this.memberClient = memberClient;
		this.commentRepository = commentRepository;
		this.insertTransaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * 게시글 작성 (P-01).
	 *
	 * <p><b>순서가 전부다 — 조회 → 성공 확인 → BEGIN → INSERT → COMMIT</b>
	 * (sp-docs/requirements/board.md §3 규칙 8). sp-docs/architecture.md §5 가 트랜잭션 안의
	 * 원격 호출을 금지한다: DB 커넥션이 member 의 네트워크 지연만큼 점유된다.
	 *
	 * <p>{@code writer_id} 는 <b>검증된 JWT 의 {@code sub}</b>({@code accountId} 파라미터),
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
	 * @param accountId 검증된 JWT 의 {@code sub}
	 */
	public PostDetailResponse create(Long accountId, PostCreateRequest request) {
		// 1) 트랜잭션 밖. 실패하면 아래로 내려가지 않으므로 BEGIN 자체가 일어나지 않는다
		String writerNickname = this.memberClient.findActiveNickname(accountId);

		// 2) BEGIN -> INSERT -> COMMIT
		Post saved = this.insertTransaction.execute(status -> this.postRepository.saveAndFlush(
				Post.builder()
						.writerId(accountId)
						.writerNickname(writerNickname)
						.title(request.title())
						.content(request.content())
						.build()));

		return PostDetailResponse.from(saved);
	}

	/**
	 * 게시글 상세 조회 (P-04) 와 조회수 증가 (P-05). <b>비로그인 허용이다</b>
	 * (sp-docs/api-contract.md §4).
	 *
	 * <p><b>{@code MemberClient} 를 호출하지 않는다.</b> 작성자 닉네임은 작성 시점 스냅샷이
	 * 게시글에 있고, 조회 경로의 board → member 호출은 금지되어 있다
	 * (sp-docs/api-contract.md §4.2, sp-docs/architecture.md §5).
	 *
	 * <p><b>{@code readOnly = true} 를 쓸 수 없다.</b> 조회수 증가가 쓰기다 (P-05).
	 *
	 * <p>삭제된 게시글·없는 id 는 {@link PostReader} 가 404 {@code P001} 로 만든다 —
	 * {@code deleted} 조건을 여기서 다시 쓰지 않는다 (sp-docs/requirements/board.md §5 규칙 1).
	 */
	@Transactional
	public PostDetailResponse getDetail(Long id) {
		Post post = this.postReader.getActiveById(id);
		post.increaseViewCount();
		return PostDetailResponse.from(post);
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
	 */
	@Transactional
	public PostDetailResponse update(Long id, LoginMember loginMember, PostUpdateRequest request) {
		Post post = this.postReader.getActiveById(id);
		validateWriter(post, loginMember);

		post.update(request.title(), request.content());

		return PostDetailResponse.from(post);
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
		if (ROLE_ADMIN.equals(loginMember.role())) {
			return;
		}
		validateWriter(post, loginMember);
	}

}
