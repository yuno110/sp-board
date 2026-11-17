package com.example.board.comment.service;

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
import com.example.board.global.security.ContractRole;
import com.example.board.global.security.LoginMember;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.example.board.post.support.PostReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 댓글·답글 쓰기·조회 (C-01 ~ C-05). 정본: sp-docs/requirements/board.md §2 §3 §3.0 §5 §12,
 * sp-docs/api-contract.md §4 §4.2 §4.4 §4.5 §5.1 §7.1, sp-docs/domain-model.md §4.2 §5 §6,
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
	 * (sp-docs/plan/phase1.md §6 B-10). 엔티티를 읽어 메모리에서 바꾸면 그 트랜잭션이 읽은 값으로
	 * 계산한 값을 써서 동시 커밋에서 증가가 소실된다 (sp-docs/nfr.md §1).
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
	 * 댓글·답글 작성 (C-01, C-05). {@code parentId} 가 없거나 {@code null} 이면 최상위 댓글이고,
	 * 있으면 그 댓글의 답글이다 (sp-docs/api-contract.md §4.5).
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
	 * 엔티티를 메모리에서 올리지 않는다: 이 트랜잭션이 읽은 값에 1 을 더한 값을 쓰므로 <b>동시
	 * 커밋에서 증가가 소실된다</b> (sp-docs/nfr.md §1 의 I-03 실측 — 201 11건에 증가 10). 더하는
	 * 대상이 이 트랜잭션이 읽은 값이 아니라 DB 의 현재 값이어야 소실되지 않는다.
	 *
	 * <p><b>집계 증가를 INSERT 보다 앞에 둔다.</b> 둘 다 같은 트랜잭션이므로 순서가 결과를
	 * 바꾸지 않지만, 뒤에 두면 INSERT 에 실패를 주입하는 테스트에서 집계 변경이 아예 일어나지
	 * 않아 "집계도 롤백된다" 가 반증 불가능해진다 (§4.1). <b>답글도 같은 순서다</b>
	 * (sp-docs/plan/phase2-board.md B-22) — {@link #delete} 가 {@code post} 감소를 먼저 하는 것과
	 * 같은 쪽이다 (sp-docs/domain-model.md §6 의 블록인용). 답글도 댓글이므로 집계는 +1 이다
	 * (sp-docs/domain-model.md §5).
	 *
	 * <p><b>판정 순서는 sp-docs/requirements/board.md §12 다</b> — 프로필({@code S001}·{@code S002},
	 * 트랜잭션 밖) → 없는·삭제된 글 404 {@code P001} → 부모의 존재·같은 글·활성 404 {@code CM001}
	 * → 부모가 최상위인가 400 {@code CM003}. 다른 글의 답글을 부모로 주면 {@code CM001} 이다 — 같은
	 * 글인지를 최상위인지보다 먼저 본다 ({@link #getReplyableParent}). 판정이 모두 첫 쓰기보다
	 * 앞이므로 실패하면 쓴 것이 없다.
	 *
	 * <p><b>게시글 조회는 트랜잭션 안이다.</b> 조회는 "없거나 삭제된 게시글은 404 {@code P001}"
	 * 판정을 받는 자리이고 (sp-docs/requirements/board.md §5 규칙 1), 규칙 8이 밖으로 내보내라고
	 * 한 것은 <b>원격 호출</b>이다. <b>그 조회와 부모 확인은 잠그지 않는 읽기다</b> — 같은 트랜잭션에
	 * 있어도 확인 뒤 첫 쓰기 전에 커밋된 삭제를 막지 않는다. 글 삭제도 그렇다: 증가 문장의
	 * {@code where} 에 {@code deleted} 조건이 없어 그 삭제를 알아채는 자리가 없고, 삭제된 글 아래에
	 * 활성 댓글이 남는다 (2026-11-17 B-22 실측 — 사본에서 최상위 댓글 작성의 {@code update post}
	 * 직전에 {@code PostService.delete} 를 다른 스레드로 커밋시킨 프로브, H2).
	 *
	 * <p><b>부모 확인과 커밋 사이의 창을 닫지 않는다</b> (sp-docs/requirements/board.md §12,
	 * sp-docs/plan/phase2-board.md B-22). 확인 뒤 커밋 전에 부모가 지워지면 삭제된 부모 아래에
	 * 답글이 생기고, 그 답글이 활성이므로 부모가 목록에 자리로 남는다 — §12 가 「삭제된 댓글에 답글
	 * 불가」 를 보장이 아니라 통상 동작으로 두었다. 닫으려면 정본 개정이 먼저다. H2 에서 이 창에 부모
	 * 삭제를 끼워 넣은 결과는 {@code CommentServiceTest} 의 B-22 중첩 클래스가 잰다.
	 *
	 * <p><b>부모 확인이 집계 증가보다 앞인 것도 그래서다</b> — 잠그지 않는 읽기 그대로 증가 뒤로
	 * 옮기면 H2 의 테스트가 운영과 다른 동작을 재게 될 것으로 본다. 사본에서 그렇게 옮기자
	 * {@code CommentServiceTest} 의 {@code 부모_확인_뒤에_지워진_부모_아래에_답글이_생긴다} 에서
	 * 답글이 404 {@code CM001} 로 끝났다 — 커밋된 읽기를 보는 H2 에서는 옮긴 읽기가 증가 전에 커밋된
	 * 삭제를 본다 (2026-11-17 B-22 실측). 첫 읽기의 스냅숏을 보는 MySQL 의 반복 읽기에서는 같은
	 * 읽기가 그 삭제를 보지 못해 창이 열린 채로 남을 것으로 본다(추정 — 실 MySQL 에서 재지 않았다).
	 * 닫는 모양은 §12 가 적었다 — 「부모 확인을 그 락 뒤의 잠금 읽기로 옮기는 것만으로 닫힌다」. 그
	 * 모양이 기대는 줄 세우기가 H2 에서 성립하지 않은 경우는 {@link #delete} 가 적는다.
	 *
	 * @param accountId 검증된 JWT 의 {@code sub}
	 */
	public CommentResponse create(Long postId, Long accountId, CommentCreateRequest request) {
		// 1) 트랜잭션 밖. 실패하면 아래로 내려가지 않으므로 BEGIN 자체가 일어나지 않는다
		String writerNickname = this.memberClient.findActiveNickname(accountId);

		// 2) BEGIN -> SELECT post -> (답글이면) SELECT parent -> UPDATE comment_count
		//    -> INSERT comment -> COMMIT
		Comment saved = this.insertTransaction.execute(status -> {
			Post post = this.postReader.getActiveById(postId);
			Comment parent = request.parentId() == null
					? null
					: getReplyableParent(request.parentId(), post.getId());
			this.postRepository.increaseCommentCount(post.getId());
			return this.commentRepository.saveAndFlush(Comment.builder()
					.post(post)
					.parent(parent)
					.writerId(accountId)
					.writerNickname(writerNickname)
					.content(request.content())
					.build());
		});

		return CommentResponse.from(saved);
	}

	/**
	 * 답글의 부모. 같은 글의 활성 댓글이 아니면 404 {@code CM001}, 답글이면 400 {@code CM003} 이다
	 * (sp-docs/requirements/board.md §12). <b>그 순서다</b> — 다른 글의 답글이나 삭제된 답글을
	 * 부모로 주면 {@code CM001} 이다.
	 *
	 * <p>깊이가 1단계라는 불변식은 제약이 아니라 이 판정이 지킨다 (sp-docs/domain-model.md §4.2).
	 */
	private Comment getReplyableParent(Long parentId, Long postId) {
		Comment parent = this.commentRepository.findByIdAndPostIdAndDeletedFalse(parentId, postId)
				.orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));
		if (parent.getParent() != null) {
			throw new BusinessException(ErrorCode.NESTED_REPLY_NOT_ALLOWED);
		}
		return parent;
	}

	/**
	 * 댓글 목록 (C-02, C-05). <b>비로그인 허용이다</b> (sp-docs/api-contract.md §4).
	 *
	 * <p><b>{@code MemberClient} 를 호출하지 않는다.</b> 작성자 닉네임은 작성 시점 스냅샷이
	 * 댓글 행에 있고, 조회 경로의 board → member 호출은 금지되어 있다
	 * (sp-docs/api-contract.md §4.2, sp-docs/architecture.md §5).
	 *
	 * <p><b>최상위 항목을 페이징하고 답글을 각 최상위 아래에 전부 등록순으로 싣는다</b>
	 * (sp-docs/requirements/board.md §12, sp-docs/api-contract.md §4.5). 페이징의 단위와
	 * {@code totalElements} 가 최상위 항목이다 — 답글은 {@code size} 에 세지 않는다. 어느 최상위가
	 * 목록에 오는지와 그 순서·건수는 {@link CommentRepository#findListedTopLevels} 가, 답글의 활성
	 * 조건과 순서는 {@link CommentRepository#findActiveRepliesOf} 가 맡는다. 삭제된 최상위의 내용을
	 * 가리는 것은 응답 모양이다 ({@link CommentResponse#from}).
	 *
	 * <p><b>답글은 그 페이지의 최상위 id 들로 한 번에 읽는다</b> — 최상위마다 읽지 않는다. 그래서
	 * 쿼리 수가 최상위 항목 수·답글 수와 무관하다 (sp-docs/plan/phase2-board.md B-22). 빈 페이지면
	 * 읽을 부모가 없으므로 답글을 읽지 않는다.
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
	public PageResponse<TopLevelCommentResponse> getList(
			Long postId, CommentPageCondition condition) {
		Post post = this.postReader.getActiveById(postId);

		Page<Comment> topLevels = this.commentRepository.findListedTopLevels(
				post.getId(), PageRequest.of(condition.page(), condition.size()));
		Map<Long, List<CommentResponse>> repliesByParentId = activeRepliesOf(topLevels.getContent());

		return PageResponse.of(topLevels.map(topLevel -> TopLevelCommentResponse.of(
				topLevel, repliesByParentId.getOrDefault(topLevel.getId(), List.of()))));
	}

	/**
	 * 최상위 댓글들의 활성 답글을 부모 id 별로 나눈다. 각 목록의 순서는 조회의 등록순 그대로다 —
	 * 순차 스트림의 {@code groupingBy} 는 만난 순서대로 담는다.
	 */
	private Map<Long, List<CommentResponse>> activeRepliesOf(List<Comment> topLevels) {
		if (topLevels.isEmpty()) {
			return Map.of();
		}
		List<Long> parentIds = topLevels.stream().map(Comment::getId).toList();
		return this.commentRepository.findActiveRepliesOf(parentIds).stream()
				.collect(Collectors.groupingBy(
						reply -> reply.getParent().getId(),
						Collectors.mapping(CommentResponse::from, Collectors.toList())));
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
	 * <p>응답은 작성 응답·목록의 답글과 <b>같은 모양</b>이다 (sp-docs/api-contract.md §4.5) — 목록의
	 * 최상위 항목은 거기에 {@code replies} 를 더한 것이다.
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
	 * <p><b>{@code comment_count} 가 1 감소한다</b> (sp-docs/domain-model.md §5). 감소와 댓글
	 * 행의 전환이 한 트랜잭션인 것은 이 메서드의 {@code @Transactional} 이 보장한다.
	 *
	 * <p><b>답글이 달린 댓글을 지워도 답글은 그대로다</b> — 전환하는 행은 그 댓글 하나이고 감소도
	 * 1 이다 (sp-docs/domain-model.md §5 의 표, sp-docs/requirements/board.md §12). 지운 최상위는
	 * 활성 답글이 남아 있는 동안 목록에 자리로 남는다 ({@link #getList}). 답글 삭제도 이 경로다 —
	 * 수정·삭제 권한이 댓글과 같다 (§12).
	 *
	 * <p><b>순서는 {@code post} 감소 → 댓글의 조건부 전환이다</b> (sp-docs/plan/phase2-board.md
	 * B-17, sp-docs/domain-model.md §6.2 의 첨부 삭제와 같은 모양).
	 * <ol>
	 * <li>{@link PostRepository#decreaseCommentCount} — 1 행이면 {@code post} 행의 배타 락을
	 * 쥔다. 0 행이면 집계가 이미 0 이다. 이 댓글이 아직 활성이면 근거 행과 어긋난 상태이고, 1차처럼
	 * 2) 를 그대로 해 집계를 0 에 둔다. 그새 다른 요청이 이 댓글을 지웠으면 2) 가 0 행을 보고
	 * 되돌린다. 어긋난 상태에 WARN 로그를 남기지 않는다 — sp-docs/plan/phase2-board.md B-17 이
	 * 그렇게 정했고, 남길지는 같은 계획의 §4.3 이 정본 개정으로 넘겼다
	 * <li>{@link CommentRepository#softDeleteIfActive} — 0 행이면 이미 지워진 것이다. 404
	 * {@code CM001} 을 던져 트랜잭션을 되돌린다 — 1) 의 감소도 함께 되돌아간다
	 * </ol>
	 *
	 * <p><b>{@code post} 가 먼저인 이유는 락 순서다.</b> 댓글 행을 먼저 쓰면 답글 작성
	 * ({@code post} → 부모 행의 FK 공유 락)과 순서가 반대라 교착할 것으로 본다 —
	 * sp-docs/domain-model.md §6 의 블록인용(2026-11-11 {@code D-18})이 추정으로 적었다. 작성
	 * 경로도 집계 증가를 INSERT 보다 먼저 한다({@link #create}).
	 *
	 * <p><b>1) 이 0 행일 때 {@code post} 행의 락은 DB 에 달렸다.</b> H2 는 쥐지 않는다: 집계를 0
	 * 으로 어긋나게 둔 글에서, 답글이 {@code post} 를 갱신하고 INSERT 를 내기 직전에 그 부모를 이
	 * 메서드로 지우자 감소가 답글의 커밋을 기다리지 않았고, 삭제가 먼저 커밋돼 답글이 지워진 부모
	 * 아래에 생겼다 (2026-11-17 B-22 실측 — 사본 프로브). MySQL 의 반복 읽기는 쥘 것으로 본다(추정
	 * — 실 MySQL 에서 재지 않았다). 그 결과는 sp-docs/requirements/board.md §12 가 열어 둔 창 안에
	 * 있다. 다만 §12 가 그 창을 닫을 근거로 적은 「두 경로가 {@code post} 의 배타 락에서 한 줄로
	 * 서므로」 는 H2 에서 이 감소가 0 행이면 성립하지 않았다.
	 *
	 * <p><b>전환의 영향 행 수가 최종 판정이다.</b> 아래 404·403 판정은 락 전의 읽기라 같은 댓글의
	 * 동시 삭제를 거르지 못한다 — 모두 활성으로 읽고 쓰기에 닿을 수 있다. 그 요청들 가운데 전환이
	 * 1 행인 요청 하나만 감소를 남기고, 나머지는 0 행을 보고 되돌아간다. 그래서 같은 댓글을 두 번
	 * 지워도 — 순차든 동시든 — 집계가 한 번만 깎인다. 순차에서는 두 번째 요청이 판정에서 404 를
	 * 받아 쓰기에 닿지 않는다. 동시는 {@code CommentServiceTest} 의 B-17 중첩 클래스가 H2 에서
	 * 끼워넣기를 강제해 잰다 — 실 MySQL 의 측정은 sp-docs/plan/phase2-board.md §4.1 이 통합으로
	 * 넘겼다. <b>1차는 그렇지 않았다</b> — 엔티티의 {@code softDelete()} 를 더티 체킹으로 조건 없이
	 * 저장해 전환이 언제나 성공했고, 같은 댓글의 동시 삭제 3건이 모두 성공해 집계를 3 줄였다(B-17
	 * 이 고치기 전의 코드에 같은 테스트를 돌려 잰 값).
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

		// 1) post 먼저. 0행(집계가 이미 0)이어도 2)를 그대로 한다
		this.postRepository.decreaseCommentCount(comment.getPost().getId());
		// 2) 조건부 전환이 최종 판정이다. 0행이면 이미 지워진 것 — 던져서 1)의 감소까지 되돌린다
		if (this.commentRepository.softDeleteIfActive(comment.getId()) == 0) {
			throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
		}
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
