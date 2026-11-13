# 문서AI

계약서의 주요 조건을 확인하고 관련 일정을 관리하는 앱입니다.
현재 근로계약서와 임대차계약서를 지원하며 나머지 21종은 추후 추가할 예정입니다.

## 개발 환경

- Java 17
- Spring Boot 3.5.11
- Gradle
- PostgreSQL
- HTML / CSS / JavaScript

화면은 Spring Boot에서 함께 제공하므로 프런트엔드 서버를 따로 실행할 필요가 없습니다.

## 주요 기능

- 이메일과 비밀번호로 회원가입 및 로그인
- 계약서 이미지와 PDF 업로드
- 최대 10쪽 미리보기와 순서 변경
- 이름과 연락처 등 개인정보 직접 가리기
- 계약 조건과 확인이 필요한 조항 조회
- 문서별 대화
- 계약서 보관과 일정 등록
- 이전 계약서와 수정본 비교
- 수정본 저장 시 이전 버전 보존 및 일정 교체

개인정보를 가린 이미지만 서버에 저장합니다. 원본 이미지와 PDF는 서버로 전송하지 않습니다.

## 프로젝트 구조

```text
src/main/java/com/team/docai/
├── DocaiApplication.java
├── domain/                  비즈니스 로직
│   ├── auth/
│   ├── document/
│   ├── comparison/
│   ├── chat/
│   ├── schedule/
│   └── exam/
├── web/                     컨트롤러와 요청 및 응답 DTO
└── global/                  공통 응답과 설정 및 예외 처리

src/main/resources/
├── application.yml
├── catalog.json             문서 종류
├── db/migration/            Flyway 마이그레이션
└── static/                  화면 코드

src/test/java/com/team/docai/
├── domain/document/         단위 테스트
└── integration/             PostgreSQL 통합 테스트
```

요청은 Request → Command → Service → Info → Response 순서로 처리합니다.
DB 스키마 변경은 Flyway 마이그레이션으로 관리합니다.

## 실행 방법

JDK 17과 PostgreSQL이 필요합니다.

1. 프로젝트 루트의 `.env.example`을 `.env`로 복사합니다.
2. PostgreSQL에 `docai` 데이터베이스를 생성합니다.
3. `.env`의 DB_URL과 DB_USERNAME 및 DB_PASSWORD를 로컬 DB에 맞게 설정합니다.
4. 프로젝트 루트에서 다음 명령어를 실행합니다.

```bash
# macOS에 설치된 JDK 17 사용
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
chmod +x gradlew
./gradlew bootRun
```

[http://127.0.0.1:8080](http://127.0.0.1:8080)에 접속한 뒤 회원가입하면 됩니다.
비밀번호는 8자 이상이며 이메일 인증은 사용하지 않습니다.

Docker로 DB를 실행하려면 `.env`의 DB_NAME과 DB_USERNAME 및 DB_PASSWORD를 설정한 뒤 아래 명령어를 사용합니다.

```bash
docker compose up -d db
```

업로드한 이미지는 DOCAI_STORAGE_PATH에 지정한 경로에 저장됩니다.
기본 경로는 `./data/masked-pages`입니다.
`.env`와 업로드 파일은 Git에 포함하지 않습니다.

## 사용 순서

1. 로그인 후 계약서 종류를 선택합니다.
2. 파일을 올리거나 예시 계약서를 추가합니다.
3. 개인정보를 가리고 최종 이미지를 확인합니다.
4. 분석 결과에서 계약 조건과 조항을 확인합니다.
5. 필요한 일정을 선택한 뒤 계약서를 보관합니다.
6. 수정본은 이전 계약서와 비교한 뒤 저장합니다.

PDF 업로드는 `samples/pdf-upload-fixture.pdf`로 확인할 수 있습니다.

## AI 연동

현재 분석과 대화는 고정된 예시 데이터를 사용합니다. 업로드한 문서를 실제로 판독하지 않으며 외부 API를 호출하지 않습니다.

OpenAI 연동 코드는 아래 파일에 추가하면 됩니다.

```text
src/main/java/com/team/docai/domain/document/client/OpenAiDocumentClient.java
```

파일의 TODO 주석을 참고해 analyze와 answer 메서드를 구현하면 됩니다.
입출력 형식은 DocumentAiClient와 AnalysisInfo에 정의되어 있습니다.

기본 설정은 `DOCAI_AI_MODE=mock`입니다.
현재는 `openai`로 변경해도 API를 호출하지 않고 AI_NOT_CONFIGURED 오류를 반환합니다.

## 테스트

단위 테스트는 DB 없이 실행할 수 있습니다.

```bash
./gradlew test
```

통합 테스트는 별도의 PostgreSQL 테스트 DB가 필요합니다.
아래 예시의 DB 접속 정보를 환경에 맞게 변경한 뒤 실행합니다.

```bash
export DOCAI_INTEGRATION_TESTS=true
export TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/docai_test
export DB_USERNAME=docai
export DB_PASSWORD=your-test-db-password
export TEST_STORAGE_PATH=/tmp/docai-test-pages
./gradlew clean test
```

기본 실행에서는 단위 테스트 3개를 실행하고 통합 테스트 12개는 건너뜁니다.
전체 테스트 결과는 [VERIFICATION.md](VERIFICATION.md)에 기록되어 있습니다.

## 미구현 기능

- OpenAI 및 OCR 연동
- 법령 데이터 연동
- 이메일 인증과 비밀번호 재설정
- 소셜 로그인
- 외부 캘린더와 기기 푸시 알림
- 저장된 일정 수정 및 삭제
- AI를 이용한 유사 문서 자동 판별

## 라이선스

PDF 처리에는 PDF.js 4.10.38을 사용합니다.
관련 라이선스는 `src/main/resources/static/vendor/`에서 확인할 수 있습니다.
