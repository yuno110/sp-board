# board-service

게시글과 댓글을 담당하는 마이크로서비스.

> 상태: **1차 기능 완료(B-01~B-09)** — 남은 것은 통합 검증 `I-01`~`I-05`다. 세 서비스가 모두 기동된 상태를 전제하므로 순차로 진행한다. 항목별 상태는 [`docs/checklist.md`](docs/checklist.md)가 단일 원본이다.

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

## 로컬 실행

**1차는 Docker를 사용하지 않는다.** MySQL은 로컬에 직접 설치한다.

### 1. MySQL — 최초 1회

```sql
CREATE DATABASE sp_board DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
SET PERSIST time_zone = '+09:00';
```

콜레이션이 `utf8mb4_general_ci`라 **검색이 대소문자를 구분하지 않는다** (`sp-docs/tech-stack.md` §4.1). 스키마는 Flyway가 만든다 — 테이블을 직접 만들지 않는다.

### 2. 공개키 — 이미 배치되어 있다

`src/main/resources/jwt-public.pem`이 **실제 공개키**이고 커밋되어 있다. 교체가 필요할 때의 절차는 아래 "공개키 배치"에 있다. **개인키는 두지 않는다.**

### 3. `application-local.yml` — 최초 1회

로컬에서는 비밀 값을 환경변수가 아니라 파일로 준다. 이 파일은 `.gitignore`에 있어 **평문으로 적어도 커밋되지 않는다** (`sp-docs/tech-stack.md` §4.3.1).

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

**템플릿을 복사한 뒤 값을 채운다.**

| 키 | 채울 것 |
| --- | --- |
| `spring.datasource.password` | MySQL 비밀번호 |
| `member-service.internal-api-key` | 내부 API 키. **member와 같은 값** |
| `cors.allowed-origins` | 허용 오리진. 로컬은 `http://localhost:3000` |
| `spring.jpa.show-sql` · `logging.level.org.hibernate.orm.jdbc.bind` | 그대로 둔다 (로컬만 SQL 로그 ON) |

> **`cors.allowed-origins`가 없으면 `local` 프로파일로 기동할 수 없다.** 값이 없으면 `SecurityConfig.corsConfigurationSource`의 `@Value` 해석이 실패해 `BeanCreationException`으로 **기동이 멈춘다.** 허용 오리진은 환경이 정하는 정책값이므로 기본값을 두지 않는다 (`sp-docs/security.md` §1.1, `sp-docs/tech-stack.md` §4.3). 비었거나 `*`가 섞여도 같은 가드가 기동을 실패시킨다.
>
> `member-service.internal-api-key`도 값이 없으면 `MemberClient` 빈 생성이 실패한다. **다만 빈 문자열은 해석에 성공한다** — 비워 두지 않는다.

### 4. 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
./gradlew test    # 프로파일은 build.gradle 이 test 로 고정한다. 로컬 설정·실제 키를 쓰지 않는다
```

- API 문서: http://localhost:8082/swagger-ui.html (**`prod` 프로파일에서는 404다**)
- 헬스체크: http://localhost:8082/actuator/health — `/actuator`에서 노출되는 것은 이것뿐이다. 나머지 경로는 무토큰 **401 `A001`**, 유효 토큰 **403 `A004`**다 (실측)

**member-service가 떠 있지 않아도 기동된다.** 글·댓글 **작성**만 503 `S001`이 되고 조회·수정·삭제는 동작한다.

### 환경변수

`local` 프로파일은 아래를 `application-local.yml`에서 읽는다. 배포 환경에서는 그 파일을 두지 않고 환경변수로 주입한다 (`sp-docs/tech-stack.md` §4.3).

**목록은 `application.yml`의 `${...}` 전부다.** 다른 데서 읽는 비밀 값은 없다.

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `DB_PASSWORD` | **없음** | MySQL 비밀번호. 없으면 기동 실패 — 비밀번호가 틀린 것이 아니라 값이 없는 것이다 |
| `INTERNAL_API_KEY` | **없음** | 내부 API 호출 키. **member와 같은 값이어야 한다** (`sp-docs/security.md` §6) |
| `CORS_ALLOWED_ORIGINS` | **없음** | 허용 오리진 목록(쉼표 구분). 비었거나 `*`가 섞이면 기동 실패 |
| `DB_URL` | `jdbc:mysql://localhost:3306/sp_board?serverTimezone=Asia/Seoul&characterEncoding=UTF-8` | |
| `DB_USERNAME` | `root` | |
| `SERVER_PORT` | `8082` | |
| `MEMBER_SERVICE_URL` | `http://localhost:8081` | 내부 API 호출 대상. **오설정이 503 `S001`로 드러난다** — 404를 "프로필 없음"으로 읽지 않기 때문이다 |

**비밀값과 정책값에는 기본값을 두지 않는다.** 임의 기본값을 두면 그 값이 정해진 정책인지 임시값인지 구분할 수 없고 운영에 그대로 나간다. 없으면 기동이 실패하는 것이 의도된 동작이다.

**개인키 관련 환경변수가 없다.** 이 서비스는 서명하지 않으므로 공개키 경로(`classpath:jwt-public.pem`)만 쓰고 그 값은 프로파일과 무관하다.

member 호출의 타임아웃(connect 1초 / read 3초)은 환경변수가 아니다 — `application.yml`에 고정이고 정본은 `sp-docs/nfr.md` §3이다.

시간대는 실행 환경이 정한다. 환경변수로 넘길 것은 없다 (`sp-docs/adr/0011`).

### `prod` 프로파일

`application-prod.yml`은 운영에서 꺼야 하는 것만 적는다. **비밀 값을 적지 않는다** — 위 환경변수로 주입한다.

| 끄는 것 | 왜 |
| --- | --- |
| `springdoc.api-docs.enabled` · `springdoc.swagger-ui.enabled` | API 문서를 운영에서 비활성화한다 (`sp-docs/nfr.md` §8). **둘 다 끈다** — UI만 끄면 `/v3/api-docs`가 전체 엔드포인트 목록을 그대로 낸다 |
| `spring.jpa.show-sql` · `logging.level.org.hibernate.*` | SQL·바인드 파라미터 로그는 로컬만 ON이다 (`sp-docs/nfr.md` §5). board의 바인드 파라미터에는 사용자가 쓴 제목·본문이 실린다 |

```bash
java -jar build/libs/board-service.jar --spring.profiles.active=prod
```

## 공개키 배치

**이 저장소에는 공개키만 둔다.** 개인키는 auth-service 하나만 갖는다. 키 관리 규칙은 `sp-docs/security.md` §3이 정본이다.

1. 키 페어는 **auth-service 쪽에서** 만든다 (`sp-docs/tech-stack.md` §4.2). board는 만들지 않는다

   ```bash
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
   openssl rsa -in private.pem -pubout -out public.pem
   ```

2. **공개키만** 이 저장소로 가져온다

   ```bash
   cp public.pem sp-board/src/main/resources/jwt-public.pem
   ```

3. 커밋한다. **공개키는 커밋해도 된다** — 유출돼도 토큰을 위조할 수 없다

**두 사본이 같은 키인지는 `I-01`이 확인한다.** auth가 실제로 발급한 토큰을 board가 받는지까지 본다.
