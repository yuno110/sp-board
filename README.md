# board-service

게시글과 댓글을 담당하는 마이크로서비스.

> 상태: **스캐폴딩 완료(B-01)** — 다음은 B-02다. `D-01`(정본 개정)이 선행 조건이다.

| 항목 | 값 |
| --- | --- |
| 포트 | 8082 |
| 데이터베이스 | `sp_board` (MySQL 8.0) |
| 소유 테이블 | `post`, `comment` |
| 기본 패키지 | `com.example.board` |
| 작업 항목 접두어 | `B-xx` (통합 검증 `I-xx`도 이 저장소에서 추적) |
| JWT 역할 | **검증만** — RS256 RSA **공개키**만 보유 (서명 불가) |

## 문서

정본 문서는 별도 저장소에 있다.

```bash
git clone https://github.com/yuno110/sp-docs.git ../sp-docs
```

| 무엇을 찾는가 | 문서 |
| --- | --- |
| 무슨 문서를 읽어야 하나 | `sp-docs/README.md` |
| 지금 할 일 | [`docs/checklist.md`](docs/checklist.md) |
| 작업 항목의 상세 | `sp-docs/plan/phase1.md` |
| 구현·테스트 절차 | `sp-docs/process/dev-workflow.md` |
| 엔드포인트·에러 코드 | `sp-docs/api-contract.md` |
| 엔티티·컬럼 | `sp-docs/domain-model.md` |
| 기능 요구사항 | `sp-docs/requirements/board.md` |

AI 워커는 [`CLAUDE.md`](CLAUDE.md)를 먼저 읽는다.

## 작성자 정보 처리 — 핵심 설계

작성자 닉네임은 member-service 소유 데이터이므로 **DB 조인이 불가능하다.** 스냅샷 방식을 쓴다.

```
게시글 작성 시
  1) JWT Claim { sub: 3, role }                       -> writer_id
  2) POST /internal/v1/members/bulk { accountIds:[3] } -> writer_nickname   (트랜잭션 밖)
  3) INSERT INTO post (writer_id, writer_nickname, ...)

게시글 조회 시
  SELECT ... FROM post          => member-service 호출 없음
```

| 컬럼 | 의미 |
| --- | --- |
| `writer_id` | **`accountId`** 논리 참조 — FK 제약을 걸지 않는다 |
| `writer_nickname` | 작성 시점 닉네임 스냅샷 |

**JWT Claim에 `nickname`이 없다.** auth가 닉네임을 소유하지 않기 때문이다. 그래서 **생성 시에만** member의 내부 API를 호출한다 (`sp-docs/adr/0012` §6).

| 경로 | member 호출 |
| --- | --- |
| 글·댓글 **생성** | **있음** |
| 글·댓글 조회·수정·삭제 | 없음 |

**효과**: 목록 조회 시 원격 호출이 0회이며, member-service가 다운돼도 게시글 **조회·수정·삭제**가 동작한다. 작성자 닉네임 검색도 로컬 `LIKE`로 처리된다.

**대가**: member가 다운되면 **새 글·댓글 작성이 503으로 막힌다.** 폴백할 스냅샷이 아직 없기 때문이다. 이것은 `sp-docs/adr/0012`가 비용으로 수용한 것이다.

**트레이드오프**: 닉네임을 바꿔도 과거 글에는 반영되지 않는다. **1차에서 해결하지 않는다.** 2차에 Kafka 이벤트로 해소한다.

### 실패 판정 — 404를 업무 의미로 쓰지 않는다

| member 응답 | board 응답 |
| --- | --- |
| 200, 결과에 `deleted = false` | 진행 |
| 200, 결과에서 제외됨 / `deleted = true` | 403 `S002` (프로필 등록 필요) |
| **그 밖의 모든 응답** (4xx, 5xx, 타임아웃) | **503 `S001`** — 경보 대상 |

404를 "프로필 없음"으로 해석하면 `MEMBER_SERVICE_URL` 오설정이 업무 오류로 위장되어 전 사용자의 쓰기가 조용히 멈춘다.

배경: `sp-docs/adr/0003-writer-snapshot.md`, `sp-docs/api-contract.md` §5.1

## 주요 제약

- **`sp_member`·`sp_auth`를 조회하지 않는다.** 같은 MySQL 인스턴스에 있어도 크로스 스키마 조인 금지
- **`writer_id`에 FK 제약을 걸지 않는다**
- **`@Transactional` 안에서 member-service를 호출하지 않는다.** 원격 호출을 먼저 하고 그 다음 트랜잭션을 연다
- **auth-service를 호출하지 않는다.** 토큰 검증은 공개키로 오프라인 수행한다
- 요청 본문의 `writerId`·`nickname`을 신뢰하지 않는다. 검증된 JWT의 `sub`와 내부 API 응답에서만 가져온다
- **수정 시 스냅샷을 갱신하지 않는다.** 그래서 수정 경로는 member를 호출하지 않는다
- JWT 필터를 직접 만들지 않는다. Spring Security `oauth2-resource-server`를 쓴다
- **개인키를 이 저장소에 두지 않는다.** 공개키만 갖는다
- 시간대는 실행 환경이 정한다 (`sp-docs/adr/0011`)

## 실행 (스캐폴딩 이후)

**1차는 Docker를 사용하지 않는다.** MySQL은 로컬에 직접 설치한다.

```sql
CREATE DATABASE sp_board DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

| 환경변수 | 필수 | 설명 |
| --- | --- | --- |
| `DB_URL` | | 기본값 `jdbc:mysql://localhost:3306/sp_board` |
| `DB_USERNAME` / `DB_PASSWORD` | | |
| `MEMBER_SERVICE_URL` | | 기본값 `http://localhost:8081` |
| `INTERNAL_API_KEY` | O | 내부 API 인증 키. **member와 같은 값** |

공개키는 `src/main/resources/jwt-public.pem`에 둔다 (커밋 가능).

- API 문서: http://localhost:8082/swagger-ui.html
- 헬스체크: http://localhost:8082/actuator/health
