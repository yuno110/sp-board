# board-service

게시글과 댓글을 담당하는 마이크로서비스.

> 상태: **스캐폴딩 전** — 작업 항목 B-01부터 시작한다.

| 항목 | 값 |
| --- | --- |
| 포트 | 8082 |
| 데이터베이스 | `board_db` (MySQL 8.0) |
| 소유 테이블 | `post`, `comment` |
| 기본 패키지 | `com.example.board` |
| JWT 역할 | **검증만** — RS256 RSA **공개키**만 보유 (서명 불가) |

## 문서

정본 문서는 별도 저장소에 있다.

```bash
git clone https://github.com/yuno110/simple-docs.git ../simple-docs
```

| 무엇을 찾는가 | 문서 |
| --- | --- |
| 무슨 문서를 읽어야 하나 | `simple-docs/README.md` |
| 지금 할 일 | [`docs/checklist.md`](docs/checklist.md) |
| 작업 항목의 상세 | `simple-docs/plan/phase1.md` |
| 구현·테스트 절차 | `simple-docs/process/dev-workflow.md` |
| 엔드포인트·에러 코드 | `simple-docs/api-contract.md` |
| 엔티티·컬럼 | `simple-docs/domain-model.md` |
| 기능 요구사항 | `simple-docs/requirements/board.md` |

AI 워커는 [`CLAUDE.md`](CLAUDE.md)를 먼저 읽는다.

## 작성자 정보 처리 — 핵심 설계

작성자 닉네임은 member-service 소유 데이터이므로 **DB 조인이 불가능하다.** 스냅샷 방식을 쓴다.

```
게시글 작성 시
  JWT Claim { sub, nickname } -> post.writer_id, post.writer_nickname 에 복제 저장
  => member-service 호출 없음
```

| 컬럼 | 의미 |
| --- | --- |
| `writer_id` | `member.id` **논리 참조** — FK 제약을 걸지 않는다 |
| `writer_nickname` | 작성 시점 닉네임 스냅샷 |

**효과**: 목록 조회 시 원격 호출이 0회이며, member-service가 다운돼도 게시글 조회가 동작한다. 작성자 닉네임 검색도 로컬 `LIKE`로 처리된다.

**트레이드오프**: 닉네임을 바꿔도 과거 글에는 반영되지 않는다. **1차에서 해결하지 않는다.** 2차에 Kafka 이벤트로 해소한다.

배경: `simple-docs/adr/0003-writer-snapshot.md`

## 주요 제약

- **`member_db`를 조회하지 않는다.** 같은 MySQL 인스턴스에 있어도 크로스 스키마 조인 금지
- **`writer_id`에 FK 제약을 걸지 않는다**
- `@Transactional` 안에서 member-service를 호출하지 않는다
- JWT 필터를 직접 만들지 않는다. Spring Security `oauth2-resource-server`를 쓴다
- **개인키를 이 저장소에 두지 않는다.** 공개키만 갖는다
- 시간대는 `Asia/Seoul`로 명시 설정한다

## 실행 (스캐폴딩 이후)

**1차는 Docker를 사용하지 않는다.** MySQL은 로컬에 직접 설치한다.

```sql
CREATE DATABASE board_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

| 환경변수 | 필수 | 설명 |
| --- | --- | --- |
| `DB_URL` | | 기본값 `jdbc:mysql://localhost:3306/board_db` |
| `DB_USERNAME` / `DB_PASSWORD` | | |
| `MEMBER_SERVICE_URL` | | 기본값 `http://localhost:8081` |

공개키는 `src/main/resources/jwt-public.pem`에 둔다 (커밋 가능).

- API 문서: http://localhost:8082/swagger-ui.html
- 헬스체크: http://localhost:8082/actuator/health
