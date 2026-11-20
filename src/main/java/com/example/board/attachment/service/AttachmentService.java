package com.example.board.attachment.service;

import com.example.board.attachment.dto.AttachmentResponse;
import com.example.board.attachment.entity.Attachment;
import com.example.board.attachment.repository.AttachmentRepository;
import com.example.board.attachment.storage.AttachmentStorage;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.example.board.post.support.PostReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 첨부 올리기 (P-10). 정본: sp-docs/requirements/board.md §11, sp-docs/domain-model.md §4.6 §6.2,
 * sp-docs/api-contract.md §4.7.
 *
 * <p>흐름은 sp-docs/domain-model.md §6.2 의 {@code POST /posts/{postId}/attachments} 다.
 * <ol>
 * <li>판정 — 0바이트(400 {@code C001}) → 없는·삭제된 글(404 {@code P001}) → 작성자인가(403
 * {@code P002}) → 형식(400 {@code AT002})·파일명(400 {@code C001}). <b>파일을 쓰기 전에 한다</b> —
 * 거부될 요청의 파일을 쓰지 않는다 (§6.2)
 * <li>0) 파일을 저장소에 쓴다. <b>트랜잭션 밖이다</b>
 * <li>1)·2) 한 트랜잭션 — {@code attachment_count} 의 조건부 UPDATE, 그다음 INSERT
 * ({@link #insert})
 * <li>그 트랜잭션이 커밋되지 않았으면 0) 의 파일을 지운다 (best effort, {@link #discard})
 * </ol>
 *
 * <p><b>개수 상한은 1) 의 조건부 UPDATE 하나가 판정한다</b> (§6.2). 미리 세어 보지 않는다 — §6.2 가
 * 막지 않지만 판정이 아니고, 상한에 걸린 요청은 쓴 파일을 지우는 것으로 끝난다.
 *
 * <p><b>올리기의 트랜잭션은 템플릿이다.</b> 그 메서드에 {@code @Transactional} 을 붙이면 파일 쓰기까지 트랜잭션
 * 안에 들어가고(§6.2 는 그것을 밖에 둔다), 커밋이 반환 뒤의 프록시에서 나서 커밋되지 않았는지를 이
 * 메서드가 보지 못한다 — 그것을 봐야 파일을 지울지 정한다.
 *
 * <p><b>파일 쓰기는 트랜잭션 밖이지만 커넥션 밖은 아니다.</b> board 는 {@code spring.jpa.open-in-view}
 * 를 설정하지 않아 Boot 기본값(켜짐)이고, 요청이 연 영속성 컨텍스트가 판정 읽기에서 얻은 커넥션을
 * 요청이 끝날 때까지 쥔다. 2026-11-18 B-23 실측 — {@code MockMvc} 로 올리는 동안
 * {@link AttachmentStorage#store} 안에서 Hikari 의 활성 커넥션이 1 이었고(요청 앞뒤는 0),
 * {@code open-in-view=false} 로 같은 측정을 하면 0 이었다. 1)·2) 의 트랜잭션도 그 세션의 커넥션을
 * 쓰므로 요청 하나가 커넥션을 둘 쥐지는 않는다 — 이 문장은 코드 읽기다(트랜잭션 도중의 커넥션 수는
 * 재지 않았다).
 *
 * <p><b>{@code MemberClient} 를 부르지 않는다.</b> 첨부는 작성자 스냅샷을 저장하지 않으므로 프로필이
 * 필요 없다 (sp-docs/requirements/board.md §5 규칙 8, §11 의 「프로필」 줄). 작성자 판정은 JWT 의
 * {@code sub} 와 글의 {@code writer_id} 로만 한다 — ADMIN 도 남의 글에는 403 {@code P002} 다 (§5 규칙
 * 6, sp-docs/api-contract.md §4.7).
 *
 * <p>{@code post} 패키지에서 참조하는 것은 {@link PostReader}·{@link PostRepository}·{@link Post} 다
 * (sp-docs/conventions.md §1.3 의 {@code attachment.*} 줄).
 *
 * <p><b>내려받기({@link #open})와 지우기({@link #delete})</b>는 B-24 다. 둘 다 파일을 지우지 않는다 —
 * 행과 파일의 수명이 다르다 (sp-docs/domain-model.md §4.6). 지우기도 {@code MemberClient} 를 부르지
 * 않는다.
 */
@Service
public class AttachmentService {

	/** 글당 활성 첨부의 상한. 정본: sp-docs/requirements/board.md §11 의 「개수」 줄. */
	private static final int MAX_ACTIVE_ATTACHMENTS = 10;

	private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

	private final PostReader postReader;

	/** {@code attachment_count} 의 조건부 UPDATE 와 INSERT 가 참조를 거는 지점. */
	private final PostRepository postRepository;

	private final AttachmentRepository attachmentRepository;

	private final AttachmentStorage attachmentStorage;

	/** 1)·2) 의 트랜잭션. 템플릿인 이유는 클래스 주석에 있다. */
	private final TransactionTemplate uploadTransaction;

	public AttachmentService(
			PostReader postReader,
			PostRepository postRepository,
			AttachmentRepository attachmentRepository,
			AttachmentStorage attachmentStorage,
			PlatformTransactionManager transactionManager) {

		this.postReader = postReader;
		this.postRepository = postRepository;
		this.attachmentRepository = attachmentRepository;
		this.attachmentStorage = attachmentStorage;
		this.uploadTransaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * 첨부 올리기 (P-10). 응답은 첨부 하나의 모양이다 (sp-docs/api-contract.md §4.7).
	 *
	 * <p>판정 순서와 흐름은 클래스 주석이다. 형식·파일명은 형식을 먼저 본다 — §11 은 둘을 가운뎃점으로
	 * 적고 둘 사이의 순서를 정하지 않았다.
	 *
	 * @param accountId 검증된 JWT 의 {@code sub}
	 * @param file {@code multipart/form-data} 의 {@code file} 파트. 크기 한도는 컨테이너가 이미
	 *     집행했다 (sp-docs/tech-stack.md §4.5)
	 */
	public AttachmentResponse upload(Long postId, Long accountId, MultipartFile file) {
		if (file.isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
		}
		Post post = this.postReader.getActiveById(postId);
		if (!post.getWriterId().equals(accountId)) {
			throw new BusinessException(ErrorCode.POST_FORBIDDEN);
		}
		ImageFormat format = ImageFormat.detect(head(file))
				.orElseThrow(() -> new BusinessException(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED));
		String originalFilename = AttachmentFilename.clean(file.getOriginalFilename());

		// 0) 판정을 모두 지난 뒤, 트랜잭션 밖에서 쓴다
		String storageKey = store(file);

		// 1)·2) 한 트랜잭션. 커밋되지 않으면 0)의 파일을 지운다 — 응답을 만드는 것은 그 판단 밖이다
		Attachment saved;
		boolean committed = false;
		try {
			saved = this.uploadTransaction.execute(status ->
					insert(postId, storageKey, originalFilename, format, file.getSize()));
			committed = true;
		}
		finally {
			if (!committed) {
				discard(storageKey, postId);
			}
		}
		return AttachmentResponse.from(saved);
	}

	/**
	 * 첨부 내려받기 (P-10). 그 첨부의 파일을 연 스트림과 서빙 헤더의 값을 돌려준다 — 바이트를 메모리에
	 * 올리지 않는다. 스트림을 닫는 것은 부르는 쪽이다.
	 *
	 * <p><b>첨부와 글이 둘 다 활성일 때만 준다</b> (sp-docs/domain-model.md §4.6). 없는 첨부, 지운 첨부,
	 * 지워진 글의 첨부는 모두 404 {@code AT001} 이다 (sp-docs/api-contract.md §4.7).
	 *
	 * <p><b>행은 있는데 저장소에 파일이 없으면 404 {@code AT001} 이고 WARN 을 남긴다</b> (§4.7) — 화면에서
	 * 「없는 첨부」 와 구별할 쓸모가 없고, 운영이 알아야 할 일이다. 그 밖의 읽기 실패는 감추지 않는다
	 * ({@link UncheckedIOException}, 500).
	 *
	 * <p>파일은 요청의 어떤 값으로도 열지 않는다 — id 로 찾은 행의 저장 이름으로 연다 (sp-docs/security.md
	 * §8).
	 *
	 * <p>트랜잭션은 조회의 것이다 (sp-docs/conventions.md §5). 스트림은 그 안에서 열리지만 바이트는 반환
	 * 뒤에 부르는 쪽이 흘려보낸다.
	 */
	@Transactional(readOnly = true)
	public AttachmentContent open(Long id) {
		Attachment attachment = this.attachmentRepository.findByIdAndDeletedFalse(id)
				.filter(found -> !found.getPost().isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.ATTACHMENT_NOT_FOUND));
		InputStream content;
		try {
			content = this.attachmentStorage.open(attachment.getStorageKey());
		}
		catch (NoSuchFileException ex) {
			log.warn("첨부 행은 있는데 저장소에 파일이 없다 — 404 AT001 로 답한다. attachmentId={}, storageKey={}",
					id, attachment.getStorageKey());
			throw new BusinessException(ErrorCode.ATTACHMENT_NOT_FOUND);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("첨부 파일을 열지 못했다", ex);
		}
		return new AttachmentContent(attachment.getContentType(), attachment.getOriginalFilename(),
				attachment.getSize(), content);
	}

	/**
	 * 첨부 지우기 (P-10). 행을 논리 삭제하고 그 글의 {@code attachment_count} 를 줄인다. <b>파일은
	 * 지우지 않는다</b> (sp-docs/requirements/board.md §11 의 「삭제」 줄).
	 *
	 * <p><b>판정 순서</b>는 없는·이미 지운 첨부 404 {@code AT001} → 작성자인가 403 {@code P002} 다
	 * (§11, sp-docs/api-contract.md §4.4). 그 글이 지워졌어도 지운 첨부는 {@code AT001} 이다 — 글의
	 * 404 {@code P001} 판정이 없다. ADMIN 도 남의 글의 첨부는 403 {@code P002} 다 (§11 의 「누가」 줄)
	 * — {@code role} 을 보지 않는다.
	 *
	 * <p><b>흐름은 sp-docs/domain-model.md §6.2 의 첨부 삭제다</b> — 한 트랜잭션에서
	 * <ol>
	 * <li>1) {@code attachment_count} 감소가 먼저다 ({@link PostRepository#decreaseAttachmentCount}).
	 * 0 행이면 집계가 이미 0 이고, 2) 를 그대로 하며 집계는 0 에 둔다(음수로 내리지 않는다). <b>1) 의 0 행만으로는
	 * 어긋난 상태가 아니다</b> — 같은 첨부의 동시 삭제에서 진 요청도 1) 이 0 행일 수 있다(아래 WARN 문단)
	 * <li>2) 첨부의 조건부 전환 ({@link AttachmentRepository#softDeleteIfActive}). <b>최종 판정이다</b> —
	 * 0 행이면 그 사이에 다른 요청이 지운 것이므로 던져서 1) 의 감소까지 되돌린다 (404 {@code AT001})
	 * </ol>
	 *
	 * <p><b>위 404·403 판정은 락 전의 읽기라 같은 첨부의 동시 삭제를 거르지 못한다</b> (§6.2) — 모두
	 * 활성으로 읽고 쓰기에 닿을 수 있다. 그 요청들 가운데 전환이 1 행인 요청만 감소를 남긴다. 조건 없이
	 * 지우면 둘 다 성공해 집계가 두 번 줄고, 활성 첨부보다 작아진 집계 때문에 올리기의 상한 판정을 지나
	 * 상한을 넘는 첨부가 들어간다 (§6.2).
	 *
	 * <p><b>어긋난 상태의 WARN 은 「1) 이 0 행이고 2) 가 1 행」 일 때만 남긴다</b> — §6.2 의 조건 그대로다
	 * (2026-11-19 {@code D-23} 개정). 1) 이 0 행인 것은 동시 삭제에서 먼저 커밋한 요청이 집계를 0 으로 내린
	 * 경우에도 나는데, 그 요청은 2) 에서 0 행을 보고 되돌아가므로 어긋난 상태가 아니다 — 1) 의 0 행에
	 * WARN 을 붙이면 지는 요청마다 거짓 경보가 난다. 그래서 로그는 2) 뒤에 둔다. 문장의 순서는 §6.2
	 * 그대로다. 이 조건은 {@code AttachmentServiceTest} 의 집계 0 동시 지우기 테스트가 고정한다 (WARN 정확히
	 * 1 회).
	 *
	 * @param accountId 검증된 JWT 의 {@code sub}
	 */
	@Transactional
	public void delete(Long id, Long accountId) {
		Attachment attachment = this.attachmentRepository.findByIdAndDeletedFalse(id)
				.orElseThrow(() -> new BusinessException(ErrorCode.ATTACHMENT_NOT_FOUND));
		Post post = attachment.getPost();
		if (!post.getWriterId().equals(accountId)) {
			throw new BusinessException(ErrorCode.POST_FORBIDDEN);
		}

		// 1) post 먼저. 0행(집계가 이미 0)이어도 2)를 그대로 한다 — 집계는 0 에 둔다
		boolean decreased = this.postRepository.decreaseAttachmentCount(post.getId()) == 1;
		// 2) 조건부 전환이 최종 판정이다. 0행이면 이미 지워진 것 — 던져서 1)의 감소까지 되돌린다
		if (this.attachmentRepository.softDeleteIfActive(id) == 0) {
			throw new BusinessException(ErrorCode.ATTACHMENT_NOT_FOUND);
		}
		if (!decreased) {
			log.warn("활성 첨부를 지웠는데 attachment_count 가 이미 0 이다 — 근거 행과 집계가 어긋났다. "
					+ "집계는 0 에 둔다. postId={}, attachmentId={}", post.getId(), id);
		}
	}

	/**
	 * sp-docs/domain-model.md §6.2 의 1)·2). 부르는 쪽의 트랜잭션 안에서 돈다.
	 *
	 * <p>1) 이 0 행이면 글이 없거나 지워졌을 때 404 {@code P001}, 아니면 상한이라 409 {@code AT003}
	 * 이다. 앞의 판정을 지났는데 404 인 것은 그 판정과 이 문장 사이에 글이 지워진 경우다 (§6.2).
	 *
	 * <p>1) 이 {@code post} 행의 배타 락을 먼저 잡으므로 2) 의 FK 공유 락과 교착하지 않는다 (§6.2 —
	 * §6.1 과 같은 이유). 글은 다시 읽지 않고 참조만 건다 — INSERT 에 필요한 것은 {@code post_id} 다.
	 * <b>이 순서는 발행된 문장의 순서로 고정한다</b> — {@code AttachmentServiceTest} 의 발행 순서 테스트
	 * (B-25). H2 로는 MySQL 의 FK 공유 락 교착을 볼 수 없어 결과가 아니라 순서를 본다. 2026-11-19 I-07 의
	 * 두 연결 프로브에서 이 순서를 뒤집으면 실 MySQL 이 5/5 교착했다(지금 순서 0/5).
	 *
	 * <p>{@code saveAndFlush} 다 — INSERT 가 이 트랜잭션의 커밋 전에, 이 메서드 안에서 나간다.
	 */
	private Attachment insert(Long postId, String storageKey, String originalFilename,
			ImageFormat format, long size) {

		if (this.postRepository.increaseAttachmentCount(postId, MAX_ACTIVE_ATTACHMENTS) == 0) {
			this.postReader.requireActive(postId);
			throw new BusinessException(ErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
		}
		return this.attachmentRepository.saveAndFlush(Attachment.builder()
				.post(this.postRepository.getReferenceById(postId))
				.storageKey(storageKey)
				.originalFilename(originalFilename)
				.contentType(format.contentType())
				.size(size)
				.build());
	}

	/** 형식 판정에 쓰는 앞부분. 파일이 그보다 짧으면 파일 전체다. */
	private static byte[] head(MultipartFile file) {
		try (InputStream in = file.getInputStream()) {
			return in.readNBytes(ImageFormat.HEAD_LENGTH);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("올린 파일을 읽지 못했다", ex);
		}
	}

	private String store(MultipartFile file) {
		try (InputStream in = file.getInputStream()) {
			return this.attachmentStorage.store(in);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("첨부 파일을 저장소에 쓰지 못했다", ex);
		}
	}

	/**
	 * 커밋되지 않은 첨부의 파일을 지운다 (sp-docs/domain-model.md §6.2 의 「best effort」).
	 *
	 * <p><b>지우지 못해도 던지지 않는다.</b> 이 메서드는 원래의 실패(409·404·DB 오류)를 전하는 길에서
	 * 불리므로, 여기서 던지면 그 실패가 이 예외로 바뀐다. 대신 WARN 을 남긴다 — 어느 행도 가리키지
	 * 않는 파일이 남은 것이고, 그 정리는 범위 밖이다 (sp-docs/requirements/board.md §11).
	 */
	private void discard(String storageKey, Long postId) {
		try {
			this.attachmentStorage.delete(storageKey);
		}
		catch (IOException | RuntimeException ex) {
			log.warn("커밋되지 않은 첨부의 파일을 지우지 못했다 — 어느 행도 가리키지 않는 파일로 남는다. "
					+ "postId={}, storageKey={}", postId, storageKey, ex);
		}
	}

}
