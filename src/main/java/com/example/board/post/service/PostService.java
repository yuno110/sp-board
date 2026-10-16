package com.example.board.post.service;

import com.example.board.client.MemberClient;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostDetailResponse;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.example.board.post.support.PostReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 게시글 쓰기·상세 조회. 정본: sp-docs/requirements/board.md §1 (P-01, P-04, P-05) §3 §3.0,
 * sp-docs/api-contract.md §4 §4.2 §4.3 §5.1.
 *
 * <p>트랜잭션 경계는 서비스에 둔다 (sp-docs/conventions.md §5).
 */
@Service
public class PostService {

	private final PostRepository postRepository;

	private final PostReader postReader;

	private final MemberClient memberClient;

	/**
	 * 작성의 DB 트랜잭션. <b>{@code @Transactional} 로 메서드 전체를 감쌀 수 없어서</b> 템플릿을
	 * 쓴다 — 감싸면 원격 호출이 그 안으로 들어간다. 메서드를 둘로 쪼개 한쪽에만 애노테이션을
	 * 붙이는 방법은 자기 호출이라 프록시를 지나지 않아 <b>경계가 아예 생기지 않는다.</b>
	 */
	private final TransactionTemplate insertTransaction;

	public PostService(
			PostRepository postRepository,
			PostReader postReader,
			MemberClient memberClient,
			PlatformTransactionManager transactionManager) {

		this.postRepository = postRepository;
		this.postReader = postReader;
		this.memberClient = memberClient;
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

}
