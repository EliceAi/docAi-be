# 문서AI 로컬 시연

기존 ZIP의 Java 17 / Spring Boot 3.5.11 / Gradle / PostgreSQL 구조를 유지한 실행형 시연 프로젝트입니다.

**모든 AI 응답은 로컬 고정 예시입니다. 이미지 판독·법률 검증·외부 API 호출은 수행하지 않습니다. 결제·클라우드·외부 로그인 서비스도 연결하지 않았습니다.**

## 구현 범위

- 이메일·비밀번호 회원가입/로그인, BCrypt 비밀번호 해시, 서버 세션, CSRF, 사용자별 접근 제한
- 와이어프레임의 23종 문서 목록·검색: 근로계약서/임대차계약서만 활성화, 나머지 21종은 `추후 업데이트 예정`
- 이미지·PDF 가져오기, 모바일 카메라 파일 입력, 최대 10쪽 미리보기·순서 변경·삭제
- 브라우저 캔버스에서 직접 가림, 되돌리기, 가림 지우기/위치 보기, 이동·확대, 쪽별 가림 유지
- 최종 확인 후 **가림을 합성한 PNG만** 로컬 서버로 전송. 원본 파일과 PDF는 서버로 보내지 않음
- 비동기 모의 분석, 정상/부분/실패/수정본 시나리오, 실패 후 재시도
- 주요 조건·조항 원문·확인 필요 항목·모의 근거 표시, 저장된 가림 이미지 조회
- 문서에 고정된 모의 대화, 문서 전환 시 새 대화, 사용자별 대화 접근 제한
- 일정 선택·날짜 직접 입력·출처 표시·중복 저장 방지·일정 없는 보관
- 앱 내부 일정 목록/달력/상세, 계약서 목록/검색/상세, 앱 내 알림 목록
- 같은 종류의 이전본 선택과 동일 계약 확인 후 비교, 읽지 못한 값을 삭제로 추정하지 않음
- 비교 불가 값 직접 입력 시 `직접 입력` 출처 저장
- 수정본 확정 시 일정 교체와 v2 보관을 하나의 DB 트랜잭션으로 처리, v1 문서 보존

## 유지한 디렉토리

```text
src/main/java/com/team/docai/
├── DocaiApplication.java
├── domain/
│   ├── exam/                 기존 빈 예시 골격 유지
│   ├── auth/                 dto, entity, repository, service
│   ├── document/             dto, entity, repository, service, client
│   ├── comparison/           dto, entity, repository, service
│   ├── chat/                 dto, entity, repository, service
│   └── schedule/             dto, entity, repository, service
├── web/
│   ├── exam/                 기존 예시 유지
│   ├── auth/                 Controller, dto/request, dto/response
│   ├── document/
│   ├── comparison/
│   ├── chat/
│   └── schedule/
└── global/                   기존 common/config/error/util 활용
src/main/resources/
├── application.yml
├── catalog.json
├── db/migration/             V1__init.sql, V2__comparison_inputs.sql
└── static/                   실제 화면. 별도 프런트엔드 서버/Node 빌드 불필요
src/test/java/com/team/docai/
├── domain/document/          DB 없는 단위 테스트
└── integration/              실제 PostgreSQL 기반 통합 테스트
```

`Request → Command → Service → Info → Response` 흐름을 사용합니다. domain은 web을 import하지 않습니다. 기존 `ApiResponse`, `BaseEntity`, `JpaConfig`, `BusinessException`을 재사용합니다. 기존 ZIP의 파일을 다른 패키지로 이동하거나 삭제하지 않았습니다.

## 로컬 실행

필수: **JDK 17**, 로컬 PostgreSQL. 유료 서비스 가입은 필요하지 않습니다. 이미 실행 중인 PostgreSQL이 있다면 그대로 사용합니다.

1. 프로젝트 루트에서 `.env.example`을 `.env`로 복사합니다.
2. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 자신의 로컬 DB에 맞춥니다. `docai` DB는 미리 생성해야 합니다. Flyway는 DB 내부 테이블을 생성합니다.
3. `JAVA_HOME`을 JDK 17 경로로 지정합니다. macOS에 JDK 17이 설치된 경우:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
chmod +x gradlew
./gradlew bootRun
```

4. `http://127.0.0.1:8080`에 접속합니다. **시연 계정 만들기**에서 `demo@example.com` 같은 가상 이메일과 8자 이상 비밀번호로 가입합니다. 실제 이메일은 발송되지 않습니다.

기존 `docker-compose.yml`도 유지했습니다. 이미 Docker가 있고 사용 가능한 로컬 환경인 경우에만 `.env`의 `DB_NAME/DB_USERNAME/DB_PASSWORD`를 맞춘 뒤 `docker compose up -d db`를 사용할 수 있습니다. Docker는 필수가 아니며 외부 DB를 구매할 필요가 없습니다.

별도로 전달된 `docai-demo.jar`는 빌드 완료 파일입니다. 프로젝트 루트에서 환경 변수 또는 `.env`를 준비하고 `java -jar /경로/docai-demo.jar`로 실행할 수도 있습니다. 처음 Gradle 빌드에는 공개 저장소의 무료 의존성 다운로드가 필요하지만 JAR 실행 자체에는 외부 API 연결이 필요하지 않습니다.

기본 서버 주소는 `127.0.0.1`입니다. 로컬 컴퓨터에서만 접속하도록 설정했습니다. 로컬 파일은 `DOCAI_STORAGE_PATH`에 저장하며 DB에는 사용자·분석·일정·대화·버전 정보를 저장합니다.

## 권장 시연 순서

1. 회원가입/로그인 → 홈 → **예시 계약서로 먼저 해보기**
2. 가상 근로계약서에서 이름·연락처 영역을 드래그해 가림 → 최종 이미지와 동의 확인 → 분석 요청
3. 조항을 펼쳐 보기 → AI에게 질문 → 일정 확인하고 저장 → 내 일정/내 계약서 확인
4. 임대차계약서 선택 → 가상 예시 추가 → 정상 완료로 분석·보관
5. 임대차계약서를 다시 등록하고 **조건이 변경된 수정본 예시** 선택
6. 결과에서 이전본과 비교 → 같은 계약임을 확인 → 월세 60만→65만 원의 모의 변경 확인
7. 수정본 확정 → 일정 교체 동의 → 저장 → v1/v2 보관 확인
8. **일부 읽지 못한 상황 예시**, **분석 실패 → 다시 시도 예시**도 각각 선택해 오류 흐름 시연
9. PDF 입력은 `samples/pdf-upload-fixture.pdf`로 확인 가능. 이 파일도 실제 계약이 아닌 가상 테스트 문서입니다.

모의 응답은 업로드한 파일에 맞춰 생성되는 결과가 아닙니다. 실제 계약 내용을 검증하는 것처럼 사용하지 마세요.

## OpenAI 연결 지점 (현재 비활성)

`src/main/java/com/team/docai/domain/document/client/OpenAiDocumentClient.java`

`// TODO: 여기에 OpenAI API 연결` 주석이 있는 `analyze`, `answer` 메서드를 나중에 구현하면 됩니다. 현재 메서드는 `AI_NOT_CONFIGURED` 오류만 반환하며 HTTP 요청 코드도 API 키도 없습니다. `DOCAI_AI_MODE=mock`이 기본값입니다. `openai`로 바꿔도 실제 API를 호출하지 않습니다.

연결 시 필요한 계약은 `DocumentAiClient`, `AnalysisInfo`에 정리했습니다. 공식 참고 링크는 해당 파일 주석에 남겼습니다. 가림 이미지 입력, 구조화된 결과 검증, 제한 시간/거절 응답 처리, 근거 법령 검증을 추가한 뒤 별도 테스트해야 합니다. 운영 환경의 키는 환경 변수로 읽고 저장소나 프런트엔드 코드에 넣지 마세요.

## 테스트

DB 없이 실행하는 기본 테스트:

```bash
./gradlew test
```

PostgreSQL 통합 테스트까지 실행하려면 앱 DB와 **별도의 테스트 DB**를 만들어 아래 환경 변수를 설정합니다. 통합 테스트는 가상 계정과 가상 문서를 추가합니다.

```bash
export DOCAI_INTEGRATION_TESTS=true
export TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/docai_test
export DB_USERNAME=docai
export DB_PASSWORD='자신의 로컬 테스트 DB 비밀번호'
export TEST_STORAGE_PATH=/tmp/docai-test-pages
./gradlew clean test
./gradlew build
```

일반 `./gradlew test`에서는 DB가 필요한 12개 통합 테스트가 명시적으로 건너뛰어지고 단위 테스트 3개만 실행됩니다. 위 환경에서 전체 15개 테스트를 실행해 통과한 결과는 `VERIFICATION.md`에 기록했습니다.

## 의도적으로 연결하지 않은 부분

- OpenAI·OCR·법령 API: 연결 지점 주석만 제공, 실제 판독/최신 법령 검증 없음
- 이메일 인증·비밀번호 재설정·소셜 로그인: 시연 범위 제외
- 외부 캘린더·OS 푸시: 앱 내부 일정/알림으로 대체
- 실제 스마트폰 카메라 하드웨어 권한 흐름: 파일 입력 연결만 제공, 데스크톱 검증에서 실기 촬영은 하지 않음
- 일정 수정·삭제, AI 유사 문서 자동 판정: 와이어프레임의 협의 항목을 임의 확정하지 않고 선택·저장·수동 이전본 비교로 제공

PDF.js 4.10.38을 로컬 번들로 포함했습니다. 라이선스는 `static/vendor/LICENSE` 및 하위 폰트/CMap 라이선스를 참고하세요. 화면 폰트는 시스템 폰트만 사용합니다.
