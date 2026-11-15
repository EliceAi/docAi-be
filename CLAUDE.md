# 문서AI 백엔드

Java 17, Spring Boot 3.5, Spring Data JPA, Gradle, Lombok. 루트 패키지 `com.team.docai`.

- 빌드: `./gradlew build`
- 테스트: `./gradlew test`

## 패키지 구조

```
web/{기능}/            {기능}Controller, dto/request/*Request, dto/response/*Response
domain/{기능}/         dto/(*Command, *Info), entity/, repository/, service/, client/(외부 API)
global/               common/(ApiResponse, BaseEntity), config/, error/(ErrorCode, BusinessException, GlobalExceptionHandler), util/
```

기능 하나는 `web/{기능}`과 `domain/{기능}`을 같은 이름으로 짝지어 만든다.

## 계층 규칙

흐름: `Request → Command → Service → Info → Response`

- Controller는 `request.toCommand()`로 Service를 호출하고, 받은 Info를 `XxxResponse.from(info)`로 바꿔 `ApiResponse`로 감싸 반환한다. 로직이나 Repository 호출은 넣지 않는다.
- Service는 Command(또는 ID 같은 단순 값)를 받아 Info를 반환한다. Entity를 반환하면 지연 로딩 오류와 엔티티 노출이 생기므로 반환하지 않는다.
- `domain`은 `web`을 import하지 않는다. 같은 서비스를 스케줄러나 테스트에서도 부를 수 있게 하기 위해서다.
- 다른 기능의 데이터가 필요하면 그 기능의 Service만 주입받는다. 다른 기능의 Repository·Entity는 직접 쓰지 않는다. 호출할 수 있는 메서드는 아래 "담당과 연결 메서드" 표에 있는 것만 쓰고, 없으면 새로 만들지 말고 담당자에게 요청한다.
- 다른 기능의 Entity와는 JPA 연관관계를 맺지 않고 ID(Long, UUID) 필드로만 가진다. 다른 기능의 폴더(`web/{기능}`, `domain/{기능}`) 파일은 수정하지 않는다.

## 담당과 연결 메서드

- A(분석): `analysis`, `chat`, `law`. B(서비스): `user`, `documenttype`, `contract`, `comparison`, `schedule`, `notification`.
- 메서드는 주인이 만들고 상대는 부르기만 한다. 반환은 Info record. 시그니처를 바꾸려면 상대와 먼저 합의한다.
- 연결 메서드는 호출한 쪽 트랜잭션 안에서 부른다. 잠금 순서는 계약서 → 비교 → 분석. GPT 호출만 트랜잭션 밖.

| 메서드 | 주인 | 부르는 곳 |
| --- | --- | --- |
| `ContractService.getForAnalysis(userId, contractId)` | B | 분석 요청, 분석 이미지 읽기 |
| `ContractService.lockForUpdate(contractId)` | B | 분석 요청 (트랜잭션 맨 처음) |
| `ContractService.linkLatestAnalysis(versionId, analysisId)` | B | 분석 COMPLETED 전환이 1행 바뀐 트랜잭션 안에서만 |
| `ContractService.setTitleIfEmpty(contractId, title)` | B | 분석 완료 |
| `ContractService.findArchivedSameType(userId, documentTypeCode, excludeContractId)` | B | 비슷한 계약서 판단 |
| `ContractService.getSummary(userId, contractId)` | B | AI 질문, 대화 기록 |
| `ComparisonService.getForChat(userId, comparisonId)` | B | AI 질문 |
| `NotificationService.createAnalysisResult(userId, analysisId, type, title, subTitle)` | B | 분석 완료·실패 (상태 전환과 같은 트랜잭션) |
| `AnalysisService.getRef(userId, analysisId)` | A | 일정 후보 조회 |
| `AnalysisService.getVersionState(versionId)` | A | 계약서 상태 판단, 분석 중 검사 |
| `AnalysisService.getSummary(analysisId)` | A | 내 계약서 목록·상세, 비교 가능 여부 |
| `AnalysisService.getScheduleCandidates(analysisId)` | A | 일정 후보, 보관, 수정본 확정 |
| `AnalysisService.getComparisonSource(analysisId)` | A | 수정본 비교 (JUDGING·제외 조항은 넣지 않음) |
| `AnalysisService.clearSimilarContract(versionId)` | A | 수정본 확정 |
| `ChatService.moveContract(fromContractId, toContractId)` | A | 수정본 확정 |
| `GptClient.call(purpose, request, responseType, ctx)` | A (`domain/analysis/client`) | 수정본 비교 설명 |

## 작성 규칙

- DTO(Request, Response, Command, Info)는 record. Request 검증 메시지는 한국어.
- Entity: `@Setter` 없음, `@NoArgsConstructor(access = PROTECTED)`, `@Builder`는 private 생성자에. 생성은 정적 팩토리(`create`), 값 변경은 의미 있는 이름의 메서드(`complete`, `fail`). enum은 `EnumType.STRING`, 연관관계는 `LAZY`, 생성·수정 시각이 필요하면 `BaseEntity` 상속.
- Service: 클래스에 `@Transactional(readOnly = true)`, 쓰기 메서드에만 `@Transactional`.
- 오류는 `throw new BusinessException(ErrorCode.XXX)`. 새 코드는 `ErrorCode`의 해당 기능 주석 구역에 `{대상}_{상황}(상태코드, "한국어 메시지")`로 추가한다. 응답 변환은 `GlobalExceptionHandler`가 하므로 Controller에서 try-catch로 응답을 만들지 않는다.
- URL은 `/api/` 아래 복수형 자원 이름. 생성은 201, 그 밖의 성공은 200.
- API 키 등 비밀값은 코드에 쓰지 않고 `application.yml`과 환경 변수에서 읽는다.

## 기준 예시

새 기능은 아래와 같은 모양으로 쓴다. 프로젝트에 이미 구현된 기능이 있으면 그 코드도 함께 읽고 맞춘다. import와 package 선언은 생략했다.

```java
// web/document/DocumentController.java
@RestController
@RequestMapping("/api/checks")
@RequiredArgsConstructor
public class DocumentController {
    private final DocumentService documentService;

    @PostMapping
    public ResponseEntity<ApiResponse<CheckResponse>> create(
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody CreateCheckRequest request) {
        CheckInfo info = documentService.create(userId, request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(201, CheckResponse.from(info)));
    }
}

// web/document/dto/request/CreateCheckRequest.java
public record CreateCheckRequest(@NotNull(message = "문서 종류는 필수입니다") DocumentType documentType) {
    public CreateCheckCommand toCommand() { return new CreateCheckCommand(documentType); }
}

// web/document/dto/response/CheckResponse.java
public record CheckResponse(Long checkId, DocumentType documentType, CheckStatus status) {
    public static CheckResponse from(CheckInfo info) {
        return new CheckResponse(info.checkId(), info.documentType(), info.status());
    }
}

// domain/document/dto/CreateCheckCommand.java
public record CreateCheckCommand(DocumentType documentType) { }

// domain/document/dto/CheckInfo.java
public record CheckInfo(Long checkId, DocumentType documentType, CheckStatus status) {
    public static CheckInfo from(DocumentCheck c) {
        return new CheckInfo(c.getCheckId(), c.getDocumentType(), c.getStatus());
    }
}

// domain/document/entity/DocumentCheck.java
@Entity
@Table(name = "document_check")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentCheck extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long checkId;
    @Column(nullable = false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DocumentType documentType;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private CheckStatus status;

    @Builder
    private DocumentCheck(UUID userId, DocumentType documentType, CheckStatus status) {
        this.userId = userId; this.documentType = documentType; this.status = status;
    }

    public static DocumentCheck create(UUID userId, DocumentType type) {
        return DocumentCheck.builder().userId(userId).documentType(type).status(CheckStatus.PENDING).build();
    }

    public void complete() { this.status = CheckStatus.COMPLETED; }
}

// domain/document/repository/DocumentCheckRepository.java
public interface DocumentCheckRepository extends JpaRepository<DocumentCheck, Long> {
    Optional<DocumentCheck> findByCheckIdAndUserId(Long checkId, UUID userId);
}

// domain/document/service/DocumentService.java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentService {
    private final DocumentCheckRepository documentCheckRepository;

    @Transactional
    public CheckInfo create(UUID userId, CreateCheckCommand command) {
        return CheckInfo.from(documentCheckRepository.save(DocumentCheck.create(userId, command.documentType())));
    }

    public CheckInfo get(UUID userId, Long checkId) {
        return documentCheckRepository.findByCheckIdAndUserId(checkId, userId)
                .map(CheckInfo::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECK_NOT_FOUND));
    }
}
```

## global 공통 클래스

`global`에 아래 클래스가 없으면 이 형태로 먼저 만든다. 있으면 그대로 쓰고 새로 만들지 않는다.

- `ApiResponse<T>`: 필드 `int status, String code, String message, T data`. 정적 메서드 `success(data)`(200), `success(status, data)`, `error(ErrorCode)`, `error(ErrorCode, message)`. 성공 시 code와 message는 null, 실패 시 code는 `ErrorCode.name()`.
- `BaseEntity`: `@MappedSuperclass` + `AuditingEntityListener`. `@CreatedDate createdAt`(updatable=false), `@LastModifiedDate updatedAt`, 타입은 `OffsetDateTime`. `@EnableJpaAuditing`은 `config/JpaConfig`에 둔다.
- `ErrorCode`: enum, 필드 `int status, String message`. 기본으로 `INVALID_INPUT(400)`, `UNAUTHORIZED(401)`, `FORBIDDEN(403)`, `INTERNAL_SERVER_ERROR(500)`을 두고, 기능별 코드는 `// Document`처럼 주석으로 구역을 나눈다.
- `BusinessException`: `RuntimeException` 상속, `ErrorCode` 필드를 가진다.
- `GlobalExceptionHandler`: `@RestControllerAdvice`. `BusinessException`은 해당 상태코드로, 검증 실패(`MethodArgumentNotValidException`)와 JSON 파싱 실패는 `INVALID_INPUT`으로, 그 밖의 `Exception`은 로그를 남기고 `INTERNAL_SERVER_ERROR`로 `ApiResponse.error(...)`를 반환한다.

## DB

- 스키마 변경은 Flyway로만 한다: `src/main/resources/db/migration/V{번호}__{내용}.sql`. 번호는 기존 최댓값 + 1이고, 파일이 없으면 `V1__init.sql`부터 시작한다.
- 이미 있는 마이그레이션 파일은 수정하지 않는다. 적용된 DB와 체크섬이 어긋나 실행이 실패한다.
- 테이블·컬럼 이름은 snake_case. Entity 필드는 camelCase로 두면 기본 네이밍 전략이 snake_case로 매핑한다.

## 새 기능 작업

순서: Entity → Flyway SQL → Repository → Command/Info → Service(+ErrorCode) → Request/Response → Controller → 테스트(`src/test/java`의 같은 패키지 경로).

마치면 `./gradlew test`를 돌려 통과를 확인한다.
