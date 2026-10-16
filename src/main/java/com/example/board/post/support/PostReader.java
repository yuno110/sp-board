package com.example.board.post.support;

import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 게시글 조회의 "없으면 예외" 지점을 한곳에 둔다.
 *
 * <p>{@code P001}(404, 게시글을 찾을 수 없습니다) 은 sp-docs/api-contract.md §8.3 이 정본이다.
 *
 * <p><b>삭제된 게시글은 없는 것으로 본다.</b> 논리 삭제이므로 행은 남아 있지만 목록·상세에서
 * 제외하는 것이 규칙이고 (sp-docs/requirements/board.md §5 규칙 1), 그 판정을 호출하는 쪽마다
 * 복제하면 한 군데가 빠지는 순간 삭제된 글이 노출된다. member 의 {@code MemberReader} 가
 * 탈퇴를 "없음" 으로 바꾸지 않는 것과 다른 선택인데, 거기서는 미등록과 탈퇴에 서로 다른 응답이
 * 배정되어 있어 구별이 필요했고 여기서는 둘 다 {@code P001} 이다.
 */
@Component
@RequiredArgsConstructor
public class PostReader {

	private final PostRepository postRepository;

	public Post getActiveById(Long id) {
		return postRepository.findByIdAndDeletedFalse(id)
				.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
	}

}
