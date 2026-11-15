# 문서AI 백엔드

## 익명 사용자 발급 API

API 명세에 맞춰 회원가입 없이 기기별로 사용자를 구분하는 기능을 추가했다.
이 브랜치에는 로그인 화면 없이 익명 인증 API만 구현했다.

### 추가한 내용

- `POST /api/users/anonymous` 추가
- deviceId가 처음 들어오면 사용자를 만들고 201 응답
- 같은 deviceId로 다시 요청하면 기존 사용자와 새 토큰을 반환하고 200 응답
- deviceId는 UUID v4 형식인지 검사하고 원본 대신 SHA-256 해시로 저장
- 이후 API는 Bearer 토큰으로 사용자 구분
- IP당 1분에 10회까지 발급 요청 허용
- users 테이블 생성용 Flyway 마이그레이션 추가

응답에는 userId와 accessToken 및 isNew가 들어간다.
토큰은 24시간 동안 유효하다. 만료되면 기존 토큰을 빼고 같은 deviceId로 다시 요청하면 된다.

### 검증 중 수정한 문제

users 테이블의 device_id_hash는 CHAR(64)인데 JPA에서는 VARCHAR로 인식해 서버 시작 시 스키마 검증이 실패했다.
엔티티에 CHAR 타입을 명시해서 DB와 매핑을 맞췄다. 수정 후 PostgreSQL에서 정상 실행되는 것을 확인했다.

토큰 인증 후에는 컨트롤러에서 사용자 ID를 UUID로 받을 수 있도록 인증 객체도 맞췄다.
테스트에서 실제 사용자 ID가 컨트롤러까지 전달되는지 확인했다.

### 중복 요청 처리

사용자 조회 후 저장하는 방식 대신 DB의 ON CONFLICT를 사용했다.
같은 기기에서 요청이 동시에 들어와도 사용자 한 명만 생성된다.
동시 요청 4건으로 확인했으며 한 건은 201이고 나머지는 같은 사용자 ID로 200을 반환했다.

### 확인한 테스트

PostgreSQL 통합 테스트 6개와 빌드가 통과했다.

- 신규 발급과 같은 기기의 재발급
- 잘못된 deviceId 거절
- 같은 IP의 11번째 요청 제한
- 정상 토큰 인증과 잘못되거나 변조된 토큰 거절
- 만료된 토큰 거절과 재발급
- 동시 요청 시 사용자 중복 생성 방지

## 실행 방법

Java 17과 PostgreSQL이 필요하다.
새 DB를 만든 뒤 `.env.example`을 `.env`로 복사하고 DB 접속 정보를 입력한다.

```bash
./gradlew bootRun
```

DOCAI_TOKEN_SECRET을 비워두면 서버를 시작할 때마다 서명 키가 바뀐다.
재시작 후에도 토큰을 유지하려면 32바이트 이상의 임의 값을 설정한다.

## 테스트 실행

앱 DB와 별도로 테스트 DB를 만든 뒤 아래 접속 정보를 바꿔 실행한다.

```bash
export DB_URL=jdbc:postgresql://localhost:5432/docai_auth_test
export DB_USERNAME=docai
export DB_PASSWORD=your-test-db-password
export DOCAI_AUTH_INTEGRATION_TESTS=true
./gradlew build
```

DOCAI_AUTH_INTEGRATION_TESTS가 없으면 통합 테스트는 실행하지 않는다.

## 남은 작업

계약서와 비교 및 일정과 알림 API는 아직 구현 전이다.
전체 SQL을 합칠 때는 이미 생성한 users 테이블이 중복되지 않도록 마이그레이션을 맞춰야 한다.
요청 횟수는 현재 서버 메모리에서 관리한다. 여러 서버나 프록시를 쓰게 되면 이 부분도 변경해야 한다.
