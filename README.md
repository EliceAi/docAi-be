# 문서AI 백엔드

## 계약서 등록 작업 · ELICE-12

가린 계약서 이미지를 저장하는 API와 분석 담당이 사용할 연결 메서드를 추가했다.

- POST /api/contracts: JPG와 PNG를 받아 최대 30쪽 등록
- 같은 요청 키로 재시도하면 기존 결과 반환. 내용이 다르면 422 응답
- PNG는 JPG로 변환해 저장. DB에는 이미지 상대 경로만 저장
- 저장 실패 시 DB 기록 롤백과 이미지 파일 삭제

### 분석 연결 메서드

| 메서드 | 역할 |
|---|---|
| getForAnalysis | 최신 버전과 쪽 순서대로 정렬한 이미지 경로 조회 |
| lockForUpdate | 계약서 행 잠금 |
| linkLatestAnalysis | 완료된 분석 ID 연결 |
| setTitleIfEmpty | 제목이 없을 때만 저장. 최대 100자 |
| FileStorage.read | 이미지 경로로 파일 읽기 |

lockForUpdate와 linkLatestAnalysis는 호출한 쪽 트랜잭션 안에서 실행한다.
다른 사용자 계약서와 삭제된 계약서는 404로 처리한다.

findArchivedSameType은 빈 목록을 반환한다.
createAnalysisResult는 아직 알림을 저장하지 않는다.
두 메서드는 팀원 요청대로 연결용으로만 추가했다.

### 수정한 문제

- 이미지 누락 시 오류 코드를 INVALID_INPUT으로 수정
- maskedCounts에 불필요한 JSON이 붙어도 저장되던 문제 수정
- 잘못된 요청 형식에 500이 나오던 문제를 400으로 수정
- 업로드 폴더 제외 규칙이 FileStorage.java까지 제외하던 문제 수정

### 확인 결과

인증 6개와 계약서 18개 테스트가 통과했다.
실제 HTTP 요청으로 30쪽 등록과 디스크 저장 실패 및 DB 커밋 실패를 추가 확인했다.
실패한 등록은 기록과 파일이 정리됐고 같은 요청 키로 재시도할 수 있었다.

최신 dev와는 .gitignore 충돌이 남아 있다. 분석 파이프라인 전체 연동은 아직 확인하지 않았다.

## 실행

Java 17과 PostgreSQL을 사용한다.
.env.example을 .env로 복사하고 DB 접속 정보를 입력한다.

```bash
./gradlew bootRun
```

STORAGE_ROOT 기본값은 ./storage다.
파일당 20MB와 전체 100MB까지 받으며 이미지당 최대 4천만 픽셀로 제한했다.
근로계약서와 임대차계약서 기본 항목이 DB에 없으면 실행 시 추가한다.

인증은 POST /api/users/anonymous에서 발급한 Bearer 토큰을 사용한다.
토큰 유효기간은 24시간이다. DOCAI_TOKEN_SECRET을 비워두면 재시작 시 서명 키가 바뀐다.
키를 유지하려면 32바이트 이상의 임의 값을 설정한다.

## 테스트

앱 DB와 별도의 테스트 DB를 사용한다. 아래 접속 정보를 바꾼 뒤 실행한다.

```bash
export DB_URL=jdbc:postgresql://localhost:5432/docai_test
export DB_USERNAME=docai
export DB_PASSWORD=your-test-db-password
export DOCAI_AUTH_INTEGRATION_TESTS=true
./gradlew clean test
```

DOCAI_AUTH_INTEGRATION_TESTS가 없으면 통합 테스트는 건너뛴다.

## DB 참고

V1은 users를 생성하고 V2는 나머지 테이블을 생성한다. 이번 계약서 작업에서는 변경하지 않았다.
현재 users는 CHAR(64)를 사용하며 updated_at이 없다. 팀원이 전달한 전체 SQL의 users 정의와 다른 부분이라 별도 조율이 필요하다.
새 Flyway 버전을 추가하기 전에는 팀원에게 먼저 공유한다.
