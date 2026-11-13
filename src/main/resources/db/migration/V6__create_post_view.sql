-- 정본: sp-docs/domain-model.md §4.5 (컬럼), §4.3 (인덱스·제약), §7 (할 것)
-- 버전 번호는 계획이 배정했다 (sp-docs/plan/phase2-board.md §2.2)
--
-- 행 하나가 "이 조회자의 이 글 조회가 이 시각에 집계됐다" 이다 (sp-docs/domain-model.md §4.5).
--
-- post_id 에 FK 를 두지 않는다. 판정 INSERT 의 FK 검사가 post 행에 공유 락을 잡고 같은 트랜잭션이
-- 이어서 view_count 의 좁은 UPDATE 로 배타 락을 요청하므로, 같은 글을 동시에 보는 트랜잭션끼리
-- 승격 교착을 만들 것으로 본다 (§4.5 — 추정, 실 MySQL 측정 대상).
--
-- viewer_key 는 utf8mb4 로 80자다. 내용은 ASCII 이지만 문자셋을 바꿀 이유가 없다 (§4.5).
-- ddl-auto: validate 는 폭을 보지 않으므로 PostViewRepositoryTest 가 information_schema 의 실제 폭과
-- 엔티티 선언을 대조한다 (sp-docs/tech-stack.md §5.2).
CREATE TABLE post_view
(
    -- viewed_at: 마지막으로 집계된 조회의 시각. 감사 시각이 아니고 판정하는 코드가 넣는다 (§4.5)
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    post_id    BIGINT      NOT NULL,
    viewer_key VARCHAR(80) NOT NULL,
    viewed_at  DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_post_view_post_viewer UNIQUE (post_id, viewer_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 오래된 판정 행의 주기 삭제
CREATE INDEX idx_post_view_viewed_at ON post_view (viewed_at);
