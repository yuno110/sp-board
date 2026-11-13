-- 정본: sp-docs/domain-model.md §4.2 (컬럼), §4.3 (인덱스·제약), §7 (할 것)
-- 버전 번호는 계획이 배정했다 (sp-docs/plan/phase2-board.md §2.2)
--
-- 답글의 부모다 (C-05, sp-docs/requirements/board.md §12). 최상위 댓글이면 NULL 이다.
-- 기존 행은 모두 최상위이므로 NULL 로 남는다. 깊이가 1단계라는 불변식은 제약이 아니라 작성 경로의
-- 판정(400 CM003)이 지킨다 (sp-docs/domain-model.md §4.2).
--
-- 같은 테이블 안의 참조이므로 FK 를 둔다 (§4.3 의 fk_comment_parent_id).
--
-- 인덱스를 FK 보다 먼저 만든다. MySQL 은 FK 를 걸 때 그 컬럼이 앞에 오는 인덱스가 없으면 인덱스를
-- 따로 만든다. idx_comment_parent_id 가 먼저 있으면 그것이 FK 의 인덱스를 겸한다 (§4.3).
ALTER TABLE comment ADD COLUMN parent_id BIGINT NULL;

-- 최상위 댓글들의 답글을 한 번에 읽는다
CREATE INDEX idx_comment_parent_id ON comment (parent_id, created_at);

ALTER TABLE comment
    ADD CONSTRAINT fk_comment_parent_id FOREIGN KEY (parent_id) REFERENCES comment (id);
