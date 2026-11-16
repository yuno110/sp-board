package com.example.board.like.service;

import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.like.dto.LikeResponse;
import com.example.board.like.entity.PostLike;
import com.example.board.like.repository.PostLikeRepository;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import java.util.OptionalInt;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 좋아요·취소 (P-09). 정본: sp-docs/requirements/board.md §10, sp-docs/domain-model.md §4.4 §6 §6.1,
 * sp-docs/api-contract.md §4.2 §4.6.
 *
 * <p>트랜잭션 경계는 서비스에 둔다 (sp-docs/conventions.md §5).
 *
 * <p><b>{@code post} 행의 배타 락을 {@code post_like} 쓰기보다 먼저 잡는다</b>
 * (sp-docs/domain-model.md §6.1). 락을 잡는 문장은 {@code like_count} 의 좁은 UPDATE 다
 * ({@link PostRepository#increaseLikeCount}·{@link PostRepository#decreaseLikeCount}). 순서를
 * 뒤집으면 {@code post_like} INSERT 의 FK 검사가 잡는 공유 락과 그 UPDATE 의 배타 락 사이에서
 * 교착할 것으로 본다 — §6.1 이 추정으로 적었고, 실 MySQL 의 측정은
 * sp-docs/plan/phase2-board.md §4.1 이 통합으로 넘겼다.
 *
 * <p><b>그 UPDATE 앞에 읽기를 두지 않는다.</b> 같은 글의 좋아요·취소는 그 락에서 한 줄로 서므로
 * (감소가 0 행인 취소는 DB 에 달렸다 — {@link #unlike}) 락을 잡은 뒤의 읽기는 앞서 커밋된 쓰기를
 * 본다. 반복 읽기 격리에서 락 전에 일반 읽기를 하면 그때의
 * 스냅샷을 읽어 그것을 놓칠 수 있다 (§6.1). 그래서 두 메서드의 첫 문장이 그 UPDATE 다.
 *
 * <p><b>증감은 근거 행이 이 트랜잭션에서 실제로 생기거나 지워졌을 때만 남는다</b> (§6 의 표).
 * 증감을 먼저 하고, 근거 행이 생기지(지워지지) 않으면 트랜잭션을 되돌려 그 증감도 되돌린다. 되돌리는
 * 경로도 실패가 아니다 — 이미 누른 글에 누르기와 누르지 않은 글에 취소는 둘 다 200 이고 그 요청
 * 뒤의 상태를 돌려준다 (sp-docs/api-contract.md §4.6).
 *
 * <p><b>그래서 {@code @Transactional} 이 아니라 템플릿이다.</b> 콜백 안에서
 * {@link TransactionStatus#setRollbackOnly()} 로 그 트랜잭션에 되돌림을 표시하면 템플릿이 부르는
 * 커밋이 되돌림이 되고 콜백의 값은 그대로 돌아온다. 되돌린 뒤의 {@code like_count} 는 그 트랜잭션
 * 밖에서 읽어야 하는데, 메서드에 애노테이션을 붙이면 그 밖이 메서드 안에 없다 — 메서드를 둘로 쪼개
 * 한쪽에만 붙이면 자기 호출이라 프록시를 지나지 않는다. {@code CommentService} 의 작성 경로도
 * 템플릿이다 — 그쪽이 지키는 것은 원격 호출을 트랜잭션 밖에 두는 것이고, 쪼개면 자기 호출이 되는
 * 것은 같다.
 *
 * <p><b>{@code MemberClient} 를 부르지 않는다.</b> 좋아요는 작성자 스냅샷을 저장하지 않으므로
 * 프로필이 필요 없다 (sp-docs/requirements/board.md §10) — 프로필이 없거나 탈퇴한 계정도 토큰이
 * 유효한 동안 누를 수 있다 (sp-docs/api-contract.md §4.2).
 *
 * <p><b>{@code post} 패키지에서 참조하는 것은 {@link PostRepository} 다</b> (sp-docs/conventions.md
 * §1.3 의 {@code like.service} 줄). {@link Post} 는 그 리포지터리가 돌려주는 타입으로만 쓴다. 그
 * 절은 표 밖의 참조를 「면제는 이 표까지다」 로 막고, 그 줄은 {@code PostReader} 를 적지 않았다.
 * 그래서 취소의 0 행에서 글이 있는지 가르는 읽기도 {@link PostRepository#findByIdAndDeletedFalse}
 * 이고, 없거나 지워진 글을 404 {@code P001} 로 바꾸는 것은 누르기의 0 행과 같이 이 서비스가 한다.
 */
@Service
public class LikeService {

	private static final Logger log = LoggerFactory.getLogger(LikeService.class);

	/**
	 * {@code like_count} 를 바꾸고 그 글을 읽는 지점. 증감은 <b>좁은 UPDATE 다</b> — 엔티티를 읽어
	 * 메모리에서 바꾸면 동시 커밋 사이에서 증가가 소실된다 (sp-docs/domain-model.md §6 의 표).
	 */
	private final PostRepository postRepository;

	private final PostLikeRepository postLikeRepository;

	/** 누르기·취소의 DB 트랜잭션. 템플릿인 이유는 클래스 주석에 있다. */
	private final TransactionTemplate likeTransaction;

	public LikeService(
			PostRepository postRepository,
			PostLikeRepository postLikeRepository,
			PlatformTransactionManager transactionManager) {

		this.postRepository = postRepository;
		this.postLikeRepository = postLikeRepository;
		this.likeTransaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * 좋아요 (P-09). 응답의 {@code liked} 는 언제나 {@code true} 다 (sp-docs/api-contract.md §4.6).
	 *
	 * <p>순서는 sp-docs/domain-model.md §6.1 의 {@code PUT} 이다.
	 * <ol>
	 * <li>{@link PostRepository#increaseLikeCount} — 0 행이면 글이 없거나 지워진 것이고 404
	 * {@code P001} 이다. 1 행이면 {@code post} 행의 배타 락을 쥔다
	 * <li>{@link PostLikeRepository#existsByPostIdAndAccountId} — 이 계정의 행이 있으면 이미 누른
	 * 것이다. 되돌려 1) 의 증가도 되돌린다
	 * <li>INSERT — UNIQUE 충돌이면 2) 와 같은 결과다 ({@link #insertLike})
	 * </ol>
	 *
	 * <p>응답의 {@code likeCount} 는 쓴 뒤(되돌렸으면 되돌린 뒤) 읽은 값이다 (§6.1,
	 * {@link #likeCountOf}).
	 *
	 * @param accountId 검증된 JWT 의 {@code sub}
	 */
	public LikeResponse like(Long postId, Long accountId) {
		OptionalInt written = this.likeTransaction.execute(status -> {
			// 1) post 의 배타 락. 이 앞에 읽기가 없다
			if (this.postRepository.increaseLikeCount(postId) == 0) {
				throw new BusinessException(ErrorCode.POST_NOT_FOUND);
			}
			// 2) 락을 잡은 뒤의 확인. 이미 눌렀으면 1)의 증가까지 되돌린다
			if (this.postLikeRepository.existsByPostIdAndAccountId(postId, accountId)) {
				status.setRollbackOnly();
				return OptionalInt.empty();
			}
			// 3) UNIQUE 충돌이면 2)와 같은 결과다
			if (!insertLike(postId, accountId)) {
				status.setRollbackOnly();
				return OptionalInt.empty();
			}
			return OptionalInt.of(likeCountOf(postId));
		});

		return new LikeResponse(true, written.orElseGet(() -> likeCountOf(postId)));
	}

	/**
	 * 좋아요 취소 (P-09). 응답의 {@code liked} 는 언제나 {@code false} 다
	 * (sp-docs/api-contract.md §4.6).
	 *
	 * <p>순서는 sp-docs/domain-model.md §6.1 의 {@code DELETE} 다.
	 * <ol>
	 * <li>{@link PostRepository#decreaseLikeCount} — 1 행이면 {@code post} 행의 배타 락을 쥔다. 0
	 * 행이면 글이 없거나 지워졌거나 집계가 이미 0 이다. 글이 없거나 지워졌으면 404 {@code P001} 이고,
	 * 글이 있으면 2) 를 그대로 한다
	 * <li>{@link PostLikeRepository#deleteByPostIdAndAccountId} — 0 행이면 누르지 않은 것이다.
	 * 되돌려 1) 의 감소도 되돌린다
	 * </ol>
	 *
	 * <p><b>1) 이 0 행인데 2) 가 1 행이면 근거 행과 어긋난 상태다</b> — 행이 있는데 집계가 0
	 * 이었다. 집계를 0 에 둔 채 행을 지우고 커밋하며 WARN 을 남긴다 (§6.1). 1) 이 0 행이어도 2) 가 0
	 * 행이면 어긋난 것이 아니다 — 누르지 않은 글의 집계가 0 인 것이다.
	 *
	 * <p><b>1) 이 0 행일 때 {@code post} 행의 락은 DB 에 달렸다.</b> 조건에 맞지 않은 행의 락을
	 * 커밋까지 쥐는 DB 라야 2) 까지 같은 계정의 누르기가 끼어들지 못한다. H2 는 쥐지 않는다 (B-20
	 * 실측 — 테스트 프로필과 같은 H2 버전·URL 옵션의 별도 메모리 DB 에서 이 메서드와 {@link #like}
	 * 가 내는 것과 같은 조건의 문장을 두 연결로 차례로 냈다. 집계가 0 인 글에서 0 행 감소 뒤의 증가는
	 * 기다리지 않았고, 그 누르기가 커밋한 뒤의 2) 가 그 행을 지워 {@code like_count} 1·행 0 으로
	 * 끝났다. 1 행 감소 뒤의 증가는 락을 기다리다 시간 초과했다). MySQL 의 반복 읽기는 쥘 것으로
	 * 본다(추정 — 실 MySQL 에서 재지 않았다). 그래서 이 창에 누르기를 끼워 넣는 테스트를 두지
	 * 않았다 — H2 에서는 그 측정대로 빨개질 것으로 본다.
	 *
	 * <p>응답의 {@code likeCount} 는 {@link #like} 와 같다.
	 *
	 * @param accountId 검증된 JWT 의 {@code sub}
	 */
	public LikeResponse unlike(Long postId, Long accountId) {
		OptionalInt written = this.likeTransaction.execute(status -> {
			// 1) post 먼저. 0행이면 글이 있는지 다시 읽어 404 와 2)를 가른다
			int decreased = this.postRepository.decreaseLikeCount(postId);
			if (decreased == 0 && this.postRepository.findByIdAndDeletedFalse(postId).isEmpty()) {
				throw new BusinessException(ErrorCode.POST_NOT_FOUND);
			}
			// 2) 근거 행의 삭제가 최종 판정이다. 0행이면 누르지 않은 것 — 1)의 감소까지 되돌린다
			if (this.postLikeRepository.deleteByPostIdAndAccountId(postId, accountId) == 0) {
				status.setRollbackOnly();
				return OptionalInt.empty();
			}
			if (decreased == 0) {
				log.warn("post_like 행이 있는데 like_count 가 0 이었다 — 근거 행과 어긋난 상태다. "
						+ "집계를 0 에 두고 행을 지운다. postId={}, accountId={}", postId, accountId);
			}
			return OptionalInt.of(likeCountOf(postId));
		});

		return new LikeResponse(false, written.orElseGet(() -> likeCountOf(postId)));
	}

	/**
	 * 근거 행을 넣는다. UNIQUE 충돌이면 {@code false} 다 — sp-docs/domain-model.md §6.1 의 3) 이 그
	 * 충돌을 「2)와 같은 결과」 로 정했다.
	 *
	 * <p><b>충돌은 정상 결과다.</b> 락을 잡은 뒤의 확인
	 * ({@link PostLikeRepository#existsByPostIdAndAccountId})이 앞서 커밋된 좋아요를 볼 것이지만
	 * 회원당 한 번의 최종 판정은 {@code uk_post_like_post_account} 다 (§6.1, §4.4). 그래서 충돌을
	 * 500 으로 만들지 않는다.
	 *
	 * <p><b>그래도 그 충돌은 ERROR 로그를 남긴다</b> (B-20 실측 — H2 에서 Hibernate 의
	 * {@code SqlExceptionHelper} 가 SQL 상태를 WARN 으로, 위반 메시지를 ERROR 로 남겼다).
	 * sp-docs/plan/phase2-board.md §4.1 이 이 로그 수준을 실 MySQL 측정 대상으로 넘기며 ERROR 면
	 * sp-docs/conventions.md §6.1 과 어긋난다고 적었다. 그 로거는 이 충돌만의 것이 아니라 Hibernate 가
	 * 변환하는 SQL 오류를 남기는 자리이므로(코드 읽기) 수준을 바꾸는 결정은 이 메서드가 하지 않는다.
	 *
	 * <p><b>충돌한 트랜잭션은 이미 rollback-only 다.</b> 리포지터리의 트랜잭션 어드바이스가 참여한
	 * 트랜잭션에서 예외를 보고 전체를 그렇게 표시한다. 부르는 쪽이 이 결과를 보고
	 * {@link TransactionStatus#setRollbackOnly()} 로 자기 트랜잭션에도 되돌림을 표시하면, 템플릿이
	 * 부르는 커밋이 그 표시를 먼저 보고 예외 없이 되돌린다. 표시하지 않고 커밋하면
	 * {@code UnexpectedRollbackException} 이 되고 응답이 500 이다 (B-20 실측 — 표시를 뺀 변이).
	 *
	 * <p><b>충돌 뒤에는 그 트랜잭션에서 아무것도 읽지 않는다</b> — {@code like_count} 는 되돌린 뒤에
	 * 읽는다. 그 트랜잭션 안에서 읽으면 아직 되돌리지 않은 증가가 보인다 (B-20 실측 — 안에서 읽은
	 * 변이의 응답이 커밋된 값보다 1 컸다).
	 *
	 * <p><b>UNIQUE 위반만 잡는다.</b> Hibernate 가 분류한 위반의 종류({@link ConstraintKind#UNIQUE})로
	 * 가른다 — 그 밖의 무결성 위반은 2) 와 같은 결과가 아니므로 그대로 던진다. 삼키면 실패가
	 * {@code liked: true} 로 위장된다 (sp-docs/AGENTS.md 불변식 9).
	 *
	 * <p>{@code saveAndFlush} 다 — INSERT 가 이 {@code try} 안에서 나가야 충돌을 여기서 받는다. 키
	 * 생성이 {@code IDENTITY} 인 동안은 {@code save} 도 곧바로 INSERT 한다 (B-20 실측 — {@code save}
	 * 로 바꾼 변이에서 UNIQUE 경로의 테스트가 녹색이었다). 거기 기대면 키 생성이 바뀔 때 INSERT 가
	 * 커밋 시점으로 밀려 충돌이 이 메서드 밖에서 터질 것으로 본다.
	 *
	 * <p>글은 다시 읽지 않고 참조만 건다({@code getReferenceById}) — INSERT 에 필요한 것은
	 * {@code post_id} 이고 그 행은 1) 의 락이 쥐고 있다.
	 */
	private boolean insertLike(Long postId, Long accountId) {
		try {
			this.postLikeRepository.saveAndFlush(PostLike.builder()
					.post(this.postRepository.getReferenceById(postId))
					.accountId(accountId)
					.build());
			return true;
		}
		catch (DataIntegrityViolationException ex) {
			if (isUniqueViolation(ex)) {
				return false;
			}
			throw ex;
		}
	}

	/**
	 * 그 글의 {@code like_count}. 쓴 트랜잭션 안에서 부르면 그 트랜잭션에 참여해 쓴 값을 읽는다 —
	 * 1) 이 1 행이면 그 락을 커밋까지 쥐므로 그 값이 커밋되는 값이다(0 행일 때는 {@link #unlike}).
	 * 되돌린 뒤에 부르면 리포지터리가 여는 읽기 트랜잭션에서 커밋된 값을 읽는다.
	 *
	 * <p><b>좁은 UPDATE 앞의 사본을 읽지 않는다.</b> 그 UPDATE 는 엔티티를 지나지 않으므로 그 전에
	 * 읽은 {@code Post} 가 영속성 컨텍스트에 남아 있으면 증감 전 값을 들고 있다. 두 메서드는 그
	 * UPDATE 앞에 글을 읽지 않고, 그 UPDATE 가 컨텍스트를 비운다
	 * ({@link PostRepository#increaseLikeCount}·{@link PostRepository#decreaseLikeCount} 의
	 * {@code clearAutomatically}). <b>지금 응답을 지키는 것은 그 UPDATE 앞에 읽기가 없다는 순서다</b>
	 * (B-20 실측 — 비우기만 끈 변이에서 좋아요 테스트가 녹색이었고, 그 UPDATE 앞에 읽기를 더하고
	 * 비우기를 끈 변이에서 응답이 커밋된 값보다 낡았다. 읽기를 더하고 비우기를 둔 변이에서는 응답이
	 * 맞았다). 비우기는 그 순서가 깨질 때의 방어다.
	 *
	 * <p>지워진 글도 읽는다({@code findById}) — 수를 보는 읽기이고 존재 판정은 1) 이 이미 했다. 그
	 * 판정 뒤에 다른 요청이 글을 지웠어도 응답은 판정 시점의 결과를 따른다. 행이 아예 없으면 404
	 * {@code P001} 이다 — 글은 논리 삭제라 행이 남으므로 그 경우를 조용히 0 으로 바꾸지 않는다.
	 */
	private int likeCountOf(Long postId) {
		return this.postRepository.findById(postId)
				.map(Post::getLikeCount)
				.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
	}

	/** 원인 사슬에서 Hibernate 의 제약 위반을 찾아 그 종류가 UNIQUE 인지 본다. */
	private static boolean isUniqueViolation(DataIntegrityViolationException ex) {
		for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getKind() == ConstraintKind.UNIQUE;
			}
		}
		return false;
	}

}
