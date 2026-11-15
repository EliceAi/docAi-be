# 문서AI 백엔드

## 이번 변경

- 익명 사용자 발급 API 추가
- 같은 기기에서 다시 요청하면 기존 사용자 반환
- 기기 식별값의 UUID v4 형식 검사와 SHA-256 해시 저장
- Bearer 토큰 인증과 만료 검사 추가
- IP당 1분 10회 요청 제한 적용
- 동시에 요청해도 사용자가 중복 생성되지 않도록 처리

`POST /api/users/anonymous`에 deviceId를 보내면 userId와 accessToken 및 isNew를 반환합니다.
신규 사용자는 201로 응답하고 기존 사용자는 200으로 응답합니다.
이후 API 요청은 Authorization 헤더에 Bearer 토큰을 넣습니다.
토큰이 만료되면 같은 deviceId로 발급 API를 다시 호출합니다. 발급 요청에는 기존 토큰을 보내지 않습니다.

이번 변경은 익명 인증 API만 포함합니다. 계약서와 비교 및 일정과 알림 API는 후속 작업입니다.
화면 코드는 이 브랜치에 포함되어 있지 않습니다.

## 실행

Java 17과 PostgreSQL이 필요합니다.
`.env.example`을 `.env`로 복사한 뒤 DB 접속 정보를 설정합니다.

```bash
./gradlew bootRun
```

토큰 유효기간은 24시간입니다.
DOCAI_TOKEN_SECRET을 비워두면 서버 시작 시 서명 키를 생성합니다.
서버 재시작 후에도 토큰을 유지하려면 32바이트 이상의 임의 값을 설정합니다.
실제 키는 Git에 올리지 않습니다.

요청 제한은 서버 메모리와 직접 연결한 IP를 기준으로 합니다.
서버를 여러 대로 운영하거나 프록시를 연결할 때는 공유 저장소와 신뢰할 프록시 설정이 필요합니다.

## DB

`V1__anonymous_users.sql`은 users 테이블만 생성합니다.
전체 스키마를 합칠 때 users 테이블을 중복 생성하지 않도록 후속 마이그레이션을 작성해야 합니다.
기존 앱의 DB 대신 이 브랜치용 새 DB를 사용합니다.

## 테스트

별도 테스트 DB를 생성하고 접속 정보를 환경 변수로 설정합니다.

```bash
export DB_URL=jdbc:postgresql://localhost:5432/docai_auth_test
export DB_USERNAME=docai
export DB_PASSWORD=your-test-db-password
export DOCAI_AUTH_INTEGRATION_TESTS=true
./gradlew build
```

PostgreSQL 통합 테스트에서 신규 발급과 재발급 및 동시 요청을 확인합니다.
입력 오류와 요청 제한 및 토큰 검증도 검사합니다.
DOCAI_AUTH_INTEGRATION_TESTS 설정이 없으면 통합 테스트를 건너뜁니다.
