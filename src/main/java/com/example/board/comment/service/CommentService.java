package com.example.board.comment.service;

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
import com.example.board.global.security.ContractRole;
import com.example.board.global.security.LoginMember;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.example.board.post.support.PostReader;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 댓글 쓰기·조회 (C-01 ~ C-04). 정본: sp-docs/requirements/board.md §2 §3 §3.0 §5,
 * sp-docs/api-contract.md §4 §4.2 §4.4 §4.5 §5.1 §7.1, sp-docs/domain-model.md §4.2 §5,
 * sp-docs/security.md §5.
 *
 * <p>트랜잭션 경계는 서비스에 둔다 (sp-docs/conventions.md §5).
 *
 * <p><b>소유자 검증도 여기다</b> (sp-docs/security.md §5.1,
 * sp-docs/requirements/board.md §5 규칙 5). Controller 나 Security 설정에 두지 않는다 —
 * 리소스를 조회해야 판정할 수 있다.
 *
 * <p><b>{@code post} 와 {@code comment} 사이에는 단방향 제약이 없다</b>
 * (sp-docs/conventions.md §1.3). <b>한 로컬 트랜잭션이 두 패키지를 걸치기 때문이다</b> —
 * 댓글 작성은 댓글 INSERT 와 {@code post.comment_count} 를, 게시글 삭제는 {@code post.deleted}
 * 와 하위 댓글 {@code deleted} 를 각각 한 트랜잭션으로 묶는다 (sp-docs/domain-model.md §5).
 * 방향을 끊으려면 그 원자성을 깨거나 두 패키지를 합쳐야 한다.
 *
 * <p><b>{@code PostService} 를 참조하지 않는다</b> (sp-docs/conventions.md §1.3) — 서로의
 * Service 를 참조하면 업무 규칙이 두 곳으로 갈리고 트랜잭션 경계가 어느 쪽 것인지 흐려진다.
 * 참조하는 것은 {@link PostReader}(게시글 존재 판정)와 {@link Post}(연관관계 매핑), 그리고
 * {@link PostRepository}({@code comment_count} 갱신)다.
 *
 * <p><b>{@link PostRepository} 참조는 §1.3 의 셋째 줄이고 B-10 이 등록했다.</b> 선언을
 * {@code post.repository} 에 두는 이유가 그 줄의 블록인용에 있다 — 문장이 바꾸는 테이블이
 * {@code post} 이므로, {@code comment} 쪽에 숨기면 {@link PostRepository} 를 읽는 사람이
 * {@code comment_count} 가 어디서도 바뀌지 않는다고 결론한다. 방향은 첫 줄
 * ({@code post.service} → {@code comment.repository.CommentRepository}, B-07 의 댓글 연쇄
 * 삭제)과 같은 모양이고, 금지된 Service 상호 참조가 아니다.
 *
 * <p>{@code PostReader} 를 쓰는 이유는 "없거나 삭제된 게시글은 404 {@code P001}" 판정이 이미
 * 거기 한 곳에 있기 때문이다 — 조건을 여기 복제하면 한쪽이 바뀔 때 두 경로의 답이 갈린다.
 */
@Service
public class CommentService {

	private final CommentRepository commentRepository;

	/**
	 * 게시글을 읽는 지점. 없거나 삭제된 게시글을 404 {@code P001} 로 만드는 것이 이 컴포넌트의
	 * 책임이다 (sp-docs/requirements/board.md §5 규칙 1). <b>작성과 목록 두 경로가 같은 판정을
	 * 여기서 받는다</b> (sp-docs/api-contract.md §4.5).
	 */
	private final PostReader postReader;

	/**
	 * {@code comment_count} 를 갱신하는 지점. <b>좁은 UPDATE 다</b> —
	 * {@code comment_count = comment_count ± 1} 한 컬럼만 싣는다
	 * (sp-docs/plan/phase1.md §6 B-10). 엔티티를 읽어 메모리에서 바꾸면 더티 체킹이 전체
	 * 컬럼을 싣고 동시 커밋에서 증가가 소실된다 (sp-docs/nfr.md §1).
	 */
	private final PostRepository postRepository;

	private final MemberClient memberClient;

	/**
	 * 작성의 DB 트랜잭션. <b>{@code @Transactional} 로 메서드 전체를 감쌀 수 없어서</b> 템플릿을
	 * 쓴다 — 감싸면 원격 호출이 그 안으로 들어간다. 메서드를 둘로 쪼개 한쪽에만 애노테이션을
	 * 붙이는 방법은 자기 호출이라 프록시를 지나지 않아 <b>경계가 아예 생기지 않는다.</b>
	 * {@code PostService} 의 작성 경로가 같은 이유로 같은 모양이다.
	 */
	private final TransactionTemplate insertTransaction;

	public CommentService(
			CommentRepository commentRepository,
			PostReader postReader,
			PostRepository postRepository,
			MemberClient memberClient,
			PlatformTransactionManager transactionManager) {

		this.commentRepository = commentRepository;
		this.postReader = postReader;
		this.postRepository = postRepository;
		this.memberClient = memberClient;
		this.insertTransaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * 댓글 작성 (C-01).
	 *
	 * <p><b>순서가 전부다 — 조회 → 성공 확인 → BEGIN → INSERT → COMMIT</b>
	 * (sp-docs/requirements/board.md §3 규칙 8 과 같은 절의 호출 순서도). 게시글과 같은
	 * 규칙이다. sp-docs/architecture.md §5 가 트랜잭션 안의 원격 호출을 금지한다: DB 커넥션이
	 * member 의 네트워크 지연만큼 점유된다.
	 *
	 * <p>{@code writer_id} 는 <b>검증된 JWT 의 {@code sub}</b>({@code accountId} 파라미터),
	 * {@code writer_nickname} 은 <b>{@code MemberClient} 응답</b>에서만 온다 (§3 규칙 1·2).
	 * JWT Claim 에 {@code nickname} 이 없기 때문이다 (sp-docs/adr/0012 §3). <b>조회 키도 그
	 * {@code sub} 다</b> (§3 규칙 3).
	 *
	 * <p>{@code MemberClient} 가 내는 {@code S002}(403, 프로필 없음·탈퇴)와
	 * {@code S001}(503, 호출 실패)을 <b>여기서 다시 감싸지 않는다.</b> 둘 다 BEGIN 전에
	 * 터지므로 댓글도 저장되지 않고 {@code comment_count} 도 증가하지 않는다
	 * (sp-docs/api-contract.md §5.1, sp-docs/requirements/board.md §3.0).
	 *
	 * <p><b>댓글 INSERT 와 {@code comment_count} 갱신이 한 로컬 트랜잭션이다</b>
	 * (sp-docs/domain-model.md §5). 따로 커밋되면 한쪽만 반영된 상태가 남는다 — 댓글은 있는데
	 * 집계가 그대로이거나, 집계만 올라가고 댓글이 없다. 경계는 이 템플릿 하나이고, <b>연산마다
	 * 자기 트랜잭션을 열지 않는다</b> — 그 변이는 행위 단언으로 잡히지 않으므로
	 * {@code CommentServiceTest} 가 커밋 횟수를 직접 센다
	 * (sp-docs/process/review-policy.md §4.1).
	 *
	 * <p><b>집계 증가는 좁은 UPDATE 다</b> — {@code comment_count = comment_count + 1}
	 * ({@link PostRepository#increaseCommentCount}, sp-docs/plan/phase1.md §6 B-10). 읽어 둔
	 * 엔티티를 메모리에서 올리지 않는다: 더티 체킹이 변경되지 않은 컬럼까지 UPDATE 에 실어
	 * 낡은 스냅샷째 덮어쓰고, <b>동시 커밋에서 증가가 소실된다</b> (sp-docs/nfr.md §1 의 I-03
	 * 실측 — 201 11건에 증가 10). 더하는 대상이 이 트랜잭션이 읽은 값이 아니라 DB 의 현재
	 * 값이어야 소실되지 않는다.
	 *
	 * <p><b>집계 증가를 INSERT 보다 앞에 둔다.</b> 둘 다 같은 트랜잭션이므로 순서가 결과를
	 * 바꾸지 않지만, 뒤에 두면 INSERT 에 실패를 주입하는 테스트에서 집계 변경이 아예 일어나지
	 * 않아 "집계도 롤백된다" 가 반증 불가능해진다 (§4.1).
	 *
	 * <p><b>게시글 조회는 트랜잭션 안이다.</b> 조회는 "없거나 삭제된 게시글은 404 {@code P001}"
	 * 판정을 받는 자리이고 (sp-docs/requirements/board.md §5 규칙 1), 그 판정과 집계 갱신·댓글
	 * INSERT 가 한 트랜잭션이어야 그 사이에 게시글이 삭제되는 틈이 없다. 규칙 8이 밖으로
	 * 내보내라고 한 것은 <b>원격 호출</b>이다.
	 *
	 * @param accountId 검증된 JWT 의 {@code sub}
	 */
	public CommentResponse create(Long postId, Long accountId, CommentCreateRequest request) {
		// 1) 트랜잭션 밖. 실패하면 아래로 내려가지 않으므로 BEGIN 자체가 일어나지 않는다
		String writerNickname = this.memberClient.findActiveNickname(accountId);

		// 2) BEGIN -> SELECT post -> UPDATE comment_count -> INSERT comment -> COMMIT
		Comment saved = this.insertTransaction.execute(status -> {
			Post post = this.postReader.getActiveById(postId);
			this.postRepository.increaseCommentCount(post.getId());
			return this.commentRepository.saveAndFlush(Comment.builder()
					.post(post)
					.writerId(accountId)
					.writerNickname(writerNickname)
					.content(request.content())
					.build());
		});

		return CommentResponse.from(saved);
	}

	/**
	 * 댓글 목록 (C-02). <b>비로그인 허용이다</b> (sp-docs/api-contract.md §4).
	 *
	 * <p><b>{@code MemberClient} 를 호출하지 않는다.</b> 작성자 닉네임은 작성 시점 스냅샷이
	 * 댓글 행에 있고, 조회 경로의 board → member 호출은 금지되어 있다
	 * (sp-docs/api-contract.md §4.2, sp-docs/architecture.md §5).
	 *
	 * <p>등록순·삭제분 제외는 리포지터리의 쿼리가 맡는다
	 * ({@code findByPostIdAndDeletedFalseOrderByCreatedAtAscIdAsc}).
	 *
	 * <p><b>없는·삭제된 게시글의 댓글 목록은 404 {@code P001} 이다</b> — 빈 페이지 200 이
	 * 아니다 (sp-docs/api-contract.md §4.5). <b>같은 서비스가 같은 게시글에 두 가지 답을 하지
	 * 않는다</b>: {@code GET /posts/{id}} 가 404 인데 그 댓글 목록이 200 이면 모순이고, 없는
	 * 자원에 대해 "댓글이 없다" 고 답하는 것은 <b>사실이 아닌 답</b>이다 (sp-docs/AGENTS.md
	 * 불변식 9). §4.4 가 존재 여부를 공개 정보로 두었으므로 404 를 내는 데 노출 비용도 없다.
	 *
	 * <p>비용은 상수 시간 조회 하나이고, 판정 지점은 늘지 않는다 — {@link #create} 가 이미
	 * 같은 {@link PostReader} 를 지난다. <b>조회한 게시글의 id 로 댓글을 찾는다</b>: 쿼리 인수가
	 * 그 조회의 결과에서 오므로 조회를 빼면 쿼리 인수도 함께 없어진다.
	 */
	@Transactional(readOnly = true)
	public PageResponse<CommentResponse> getList(Long postId, CommentPageCondition condition) {
		Post post = this.postReader.getActiveById(postId);

		return PageResponse.of(this.commentRepository
				.findByPostIdAndDeletedFalseOrderByCreatedAtAscIdAsc(
						post.getId(), PageRequest.of(condition.page(), condition.size()))
				.map(CommentResponse::from));
	}

	/**
	 * 댓글 수정 (C-03). <b>작성자 본인만 할 수 있다</b> — ADMIN 도 못 한다
	 * (sp-docs/security.md §5.2, sp-docs/requirements/board.md §5 규칙 6).
	 *
	 * <p><b>{@code MemberClient} 를 호출하지 않는다.</b> 수정 시 작성자 스냅샷을 갱신하지
	 * 않으므로 닉네임을 다시 얻을 이유가 없고 (sp-docs/requirements/board.md §3 규칙 7), 활성
	 * 프로필도 생성에만 필요하다 (§5 규칙 8). 그래서 member 장애가 수정을 막지 않는다
	 * (sp-docs/api-contract.md §4.2).
	 *
	 * <p>응답은 작성·목록과 <b>같은 한 모양</b>이다 (sp-docs/api-contract.md §4.5).
	 *
	 * <p>없는 id·삭제된 댓글은 404 {@code CM001} 이다 — <b>조회가 권한 판정보다 먼저다</b>
	 * (§4.4). 타인의 삭제된 댓글도 {@code CM001} 이고 {@code CM002} 가 아니다. 정보 노출이
	 * 아니다: 댓글 목록이 비로그인에 열려 있어 존재 여부는 이미 공개 정보이고 (§4.2), 403 을
	 * 먼저 내보내면 공개 조회와 어긋난 두 답이 나간다.
	 */
	@Transactional
	public CommentResponse update(Long id, LoginMember loginMember, CommentUpdateRequest request) {
		Comment comment = getActiveById(id);
		validateWriter(comment, loginMember);

		comment.update(request.content());

		return CommentResponse.from(comment);
	}

	/**
	 * 댓글 삭제 (C-04). <b>작성자 본인과 ADMIN 이 할 수 있다</b> (sp-docs/security.md §5.2).
	 * Soft Delete 이므로 행이 남는다 (sp-docs/requirements/board.md §5 규칙 2).
	 *
	 * <p><b>{@code comment_count} 가 1 감소한다</b> (sp-docs/domain-model.md §5). 그 둘이 한
	 * 트랜잭션인 것은 이 메서드의 {@code @Transactional} 이 보장한다.
	 *
	 * <p><b>감소도 작성과 같은 좁은 UPDATE 다</b>
	 * ({@link PostRepository#decreaseCommentCount}, sp-docs/plan/phase1.md §6 B-10). 엔티티를
	 * 메모리에서 깎으면 작성과 같은 소실이 삭제에서도 난다.
	 *
	 * <p><b>음수가 되지 않는 것은 그 문장의 {@code where} 절이다</b>
	 * ({@code comment_count > 0}, sp-docs/requirements/board.md §5 규칙 4). 좁은 UPDATE 는
	 * 엔티티를 지나지 않으므로 가드가 엔티티 메서드에 있으면 지나가지 않는다 — 그래서 가드가
	 * 문장으로 옮겨 갔다.
	 *
	 * <p><b>이미 삭제된 댓글은 404 {@code CM001} 이다.</b> 그래서 같은 댓글을 두 번 지워도
	 * 집계가 두 번 깎이지 않는다.
	 *
	 * <p><b>{@code MemberClient} 를 호출하지 않는다</b> (sp-docs/api-contract.md §4.2).
	 * {@link #update} 와 같은 이유다.
	 *
	 * <p><b>조회가 권한 판정보다 먼저인 것도 {@link #update} 와 같다</b> — §4.4 는 수정과
	 * 삭제를 함께 못박는다. 타인의 삭제된 댓글은 404 {@code CM001} 이고 403 {@code CM002} 가
	 * 아니며, <b>조용한 204 도 아니다.</b>
	 */
	@Transactional
	public void delete(Long id, LoginMember loginMember) {
		Comment comment = getActiveById(id);
		validateWriterOrAdmin(comment, loginMember);

		comment.softDelete();
		this.postRepository.decreaseCommentCount(comment.getPost().getId());
	}

	/**
	 * 수정·삭제 대상 조회. <b>삭제된 댓글은 없는 것으로 본다</b>
	 * (sp-docs/requirements/board.md §5 규칙 1) — 조건은 쿼리에 있다
	 * ({@code findByIdAndDeletedFalse}).
	 *
	 * <p>{@code P001}·{@code CM001} 처럼 "없으면 404" 를 {@code PostReader} 같은 별도
	 * 컴포넌트로 빼지 않는다. 댓글의 그 판정을 쓰는 쪽은 이 서비스이고, 게시글은 조회·수정·삭제
	 * 세 경로가 공유해 한 곳에 모을 이유가 있었다.
	 */
	private Comment getActiveById(Long id) {
		return this.commentRepository.findByIdAndDeletedFalse(id)
				.orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));
	}

	/**
	 * 수정의 권한 판정. <b>작성자 본인뿐이다.</b>
	 *
	 * <p><b>{@link #validateWriterOrAdmin} 와 한 메서드로 합치지 않는다.</b> 수정과 삭제의
	 * 권한이 다르다 — ADMIN 은 삭제만 할 수 있다 (sp-docs/security.md §5.2,
	 * sp-docs/requirements/board.md §5 규칙 6). 타인 댓글의 내용 변조를 막기 위한 것이고,
	 * 부적절한 댓글은 삭제로만 처리한다. 한쪽에 플래그를 받는 메서드로 합치면 호출처가 그
	 * 플래그를 잘못 넘기는 순간 ADMIN 이 타인 댓글을 고칠 수 있게 된다.
	 */
	private static void validateWriter(Comment comment, LoginMember loginMember) {
		if (!comment.getWriterId().equals(loginMember.accountId())) {
			throw new BusinessException(ErrorCode.COMMENT_FORBIDDEN);
		}
	}

	/**
	 * 삭제의 권한 판정. <b>작성자 본인 또는 ADMIN 이다</b> (sp-docs/security.md §5.2).
	 *
	 * <p>판정은 검증된 JWT 의 {@code sub}·{@code role} 만으로 한다 — 권한을 다른 서비스에
	 * 되묻지 않는다 (sp-docs/requirements/board.md §7). 오프라인 검증이므로 권한 박탈이 기존
	 * 토큰에 즉시 반영되지 않고, ADMIN 토큰의 잔여 권한에는 타인 댓글 삭제가 포함된다
	 * (sp-docs/security.md §5.3) — 1차가 비용으로 수용한 것이다.
	 */
	private static void validateWriterOrAdmin(Comment comment, LoginMember loginMember) {
		if (ContractRole.ADMIN.equals(loginMember.role())) {
			return;
		}
		validateWriter(comment, loginMember);
	}

}
