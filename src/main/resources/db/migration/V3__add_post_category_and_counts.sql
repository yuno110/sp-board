-- 정본: sp-docs/domain-model.md §4.1 (컬럼), §4.3 (인덱스), §7 (할 것)
-- 버전 번호는 계획이 배정했다 (sp-docs/plan/phase2-board.md §2.2)
--
-- 2차 기능 확장의 post 컬럼이다 — category 는 P-11, like_count 는 P-09, attachment_count 는 P-10.
-- 기존 행은 컬럼 기본값이 채운다: category 는 FREE, 두 집계는 0 (sp-docs/domain-model.md §4.1).
--
-- 컬럼마다 문장을 나눈다. 한 ALTER TABLE 에 컬럼을 여럿 더하는 구문은 H2 와 MySQL 이 다르게 받을 수
-- 있는 것으로 계획이 지목했다 (sp-docs/plan/phase2-board.md §2.2, sp-docs/domain-model.md §7).
--
-- category 는 VARCHAR(20) 이다. ddl-auto: validate 는 폭을 보지 않으므로 PostRepositoryTest 가
-- information_schema 의 실제 폭과 엔티티 선언을 대조한다 (sp-docs/tech-stack.md §5.2).
--
-- like_count·attachment_count 는 좁은 UPDATE 로만 바뀐다 (sp-docs/domain-model.md §6).
-- 그 문장은 PostRepository 에 있다.
ALTER TABLE post ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'FREE';
ALTER TABLE post ADD COLUMN like_count INT NOT NULL DEFAULT 0;
ALTER TABLE post ADD COLUMN attachment_count INT NOT NULL DEFAULT 0;

-- 카테고리별 최신순 목록, 공지 고정 조회
CREATE INDEX idx_post_category ON post (category, created_at DESC);
