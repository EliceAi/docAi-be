-- 전체 스키마 추가. users는 V1__anonymous_users.sql에서 생성하므로 제외한다.

-- 사용자 · 중복 요청 기록
CREATE TABLE idempotency_record (
    user_id UUID NOT NULL,
    endpoint VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_json JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, endpoint, idempotency_key)
);
COMMENT ON TABLE idempotency_record IS '중복 요청 기록';
COMMENT ON COLUMN idempotency_record.user_id IS '사용자';
COMMENT ON COLUMN idempotency_record.endpoint IS 'CONTRACT_CREATE / CONTRACT_ARCHIVE / COMPARISON_CONFIRM';
COMMENT ON COLUMN idempotency_record.idempotency_key IS '요청 헤더 Idempotency-Key';
COMMENT ON COLUMN idempotency_record.request_hash IS '요청 본문 해시. 같은 키에 다른 본문이면 422';
COMMENT ON COLUMN idempotency_record.status IS 'IN_PROGRESS / DONE';
COMMENT ON COLUMN idempotency_record.response_json IS '처음 처리한 응답. 재시도 시 그대로 반환';
COMMENT ON COLUMN idempotency_record.created_at IS '생성 시각. 일정 기간 지나면 삭제';
COMMENT ON COLUMN idempotency_record.updated_at IS '수정 시각 (BaseEntity)';

-- 문서 종류 · 문서 종류
CREATE TABLE document_type (
    code VARCHAR(40) NOT NULL,
    name VARCHAR(50) NOT NULL,
    category VARCHAR(10) NOT NULL,
    situation VARCHAR(30) NOT NULL,
    description VARCHAR(200) NOT NULL,
    check_level VARCHAR(1) NOT NULL,
    supported BOOLEAN NOT NULL,
    popular BOOLEAN NOT NULL,
    sort_order INT NOT NULL,
    PRIMARY KEY (code)
);
COMMENT ON TABLE document_type IS '문서 종류';
COMMENT ON COLUMN document_type.code IS 'EMPLOYMENT, LEASE ...';
COMMENT ON COLUMN document_type.name IS '문서 이름';
COMMENT ON COLUMN document_type.category IS 'HOME / WORK';
COMMENT ON COLUMN document_type.situation IS '상황 묶음 (집 구하기 등)';
COMMENT ON COLUMN document_type.description IS '주요 점검 내용 한 줄';
COMMENT ON COLUMN document_type.check_level IS 'A(깊게 점검) / B(공통 점검)';
COMMENT ON COLUMN document_type.supported IS 'PoC 분석 가능 여부';
COMMENT ON COLUMN document_type.popular IS '많이 찾는 계약서';
COMMENT ON COLUMN document_type.sort_order IS '정렬 순서';

-- 계약서 · 계약서
CREATE TABLE contract (
    contract_id BIGSERIAL NOT NULL,
    user_id UUID NOT NULL,
    document_type_code VARCHAR(40) NOT NULL,
    title VARCHAR(100),
    archived BOOLEAN NOT NULL,
    current_version_no INT NOT NULL,
    archived_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (contract_id)
);
COMMENT ON TABLE contract IS '계약서';
COMMENT ON COLUMN contract.contract_id IS '계약서 ID';
COMMENT ON COLUMN contract.user_id IS '소유자';
COMMENT ON COLUMN contract.document_type_code IS '문서 종류';
COMMENT ON COLUMN contract.title IS 'AI가 붙인 이름. 분석 전 null';
COMMENT ON COLUMN contract.archived IS '내 계약서 보관 여부';
COMMENT ON COLUMN contract.current_version_no IS '최신 버전 번호';
COMMENT ON COLUMN contract.archived_at IS '보관 시각';
COMMENT ON COLUMN contract.deleted_at IS '소프트 삭제 시각 (수정본 폐기·확정 후 임시 계약서, 정리 배치)';
COMMENT ON COLUMN contract.created_at IS '생성 시각';
COMMENT ON COLUMN contract.updated_at IS '수정 시각';

-- 계약서 · 계약서 버전
CREATE TABLE contract_version (
    version_id BIGSERIAL NOT NULL,
    contract_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    page_count INT NOT NULL,
    latest_analysis_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (version_id)
);
COMMENT ON TABLE contract_version IS '계약서 버전';
COMMENT ON COLUMN contract_version.version_id IS '버전 ID';
COMMENT ON COLUMN contract_version.contract_id IS '계약서. 수정본 확정 시 이전 계약서로 바뀌고, 같은 트랜잭션에서 version_no도 이전 계약서 current_version_no + 1로 바뀜';
COMMENT ON COLUMN contract_version.version_no IS '1, 2 ...';
COMMENT ON COLUMN contract_version.page_count IS '쪽 수';
COMMENT ON COLUMN contract_version.latest_analysis_id IS '화면에 보여줄 완료된 분석. 분석이 COMPLETED가 되는 트랜잭션에서만 바꿈. 페이지 재업로드 시 null';
COMMENT ON COLUMN contract_version.created_at IS '생성 시각';
COMMENT ON COLUMN contract_version.updated_at IS '수정 시각 (BaseEntity)';

-- 계약서 · 계약서 페이지
CREATE TABLE contract_page (
    page_id BIGSERIAL NOT NULL,
    version_id BIGINT NOT NULL,
    page_no INT NOT NULL,
    image_key VARCHAR(255) NOT NULL UNIQUE,
    masked_count INT NOT NULL,
    replaced_at TIMESTAMPTZ,
    PRIMARY KEY (page_id)
);
COMMENT ON TABLE contract_page IS '계약서 페이지';
COMMENT ON COLUMN contract_page.page_id IS '페이지 ID';
COMMENT ON COLUMN contract_page.version_id IS '버전';
COMMENT ON COLUMN contract_page.page_no IS '쪽 번호 (1부터)';
COMMENT ON COLUMN contract_page.image_key IS '파일 저장소 경로. 서버가 contracts/{userId}/{UUID}.jpg로 만듦 (앱이 보낸 값은 쓰지 않음)';
COMMENT ON COLUMN contract_page.masked_count IS '사용자가 가린 곳 수';
COMMENT ON COLUMN contract_page.replaced_at IS '다시 찍어 바꾼 시각';

-- 분석 · 분석
CREATE TABLE analysis (
    analysis_id BIGSERIAL NOT NULL,
    version_id BIGINT NOT NULL,
    document_type_code VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    progress_step VARCHAR(20) NOT NULL,
    fail_reason VARCHAR(30),
    fail_page_no INT,
    detected_type_name VARCHAR(50),
    partial_pages JSONB,
    law_base_date DATE,
    prompt_version VARCHAR(50) NOT NULL,
    extraction_json JSONB,
    clause_index_json JSONB,
    questions_json JSONB,
    limitations_json JSONB,
    validation_log_json JSONB,
    similar_contract_id BIGINT,
    heartbeat_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (analysis_id)
);
COMMENT ON TABLE analysis IS '분석';
COMMENT ON COLUMN analysis.analysis_id IS '분석 ID';
COMMENT ON COLUMN analysis.version_id IS '분석한 버전';
COMMENT ON COLUMN analysis.document_type_code IS '분석에 쓴 문서 종류 (요청 시점 값)';
COMMENT ON COLUMN analysis.status IS 'ANALYZING / COMPLETED / FAILED';
COMMENT ON COLUMN analysis.progress_step IS 'READING_TEXT / FINDING_TERMS / MATCHING_LAWS / SUMMARIZING / DONE. 실패 시 마지막 단계 유지';
COMMENT ON COLUMN analysis.fail_reason IS 'PAGE_UNREADABLE / TYPE_MISMATCH / TEMPORARY_ERROR';
COMMENT ON COLUMN analysis.fail_page_no IS '읽지 못한 쪽';
COMMENT ON COLUMN analysis.detected_type_name IS 'TYPE_MISMATCH일 때 실제 문서 종류';
COMMENT ON COLUMN analysis.partial_pages IS '일부만 읽은 쪽 번호 배열 (예: [2, 3])';
COMMENT ON COLUMN analysis.law_base_date IS '법 적용 기준일 (계약일, 없으면 분석일)';
COMMENT ON COLUMN analysis.prompt_version IS '분석에 쓴 프롬프트·출력 스키마 버전 (예: extract-v3/judge-v2)';
COMMENT ON COLUMN analysis.extraction_json IS '1차 추출 원본 (쪽 묶음 결과를 합친 것)';
COMMENT ON COLUMN analysis.clause_index_json IS '전체 목차 (조항 키, 제목, 한 줄 요약). 2차 호출 맥락용';
COMMENT ON COLUMN analysis.questions_json IS '서명 전에 물어볼 것';
COMMENT ON COLUMN analysis.limitations_json IS '이 계약서만으로 알 수 없는 것';
COMMENT ON COLUMN analysis.validation_log_json IS '8단계 검증 기록: 지운 인용, 차단한 표현, 제외한 조항, 길이 때문에 자른 칸, 버린 꼬리표';
COMMENT ON COLUMN analysis.similar_contract_id IS '같은 종류의 보관된 내 계약서 중 비슷한 것 (자기 자신 제외). 수정본 확정 시 null';
COMMENT ON COLUMN analysis.heartbeat_at IS '작업 중인 서버가 단계마다, 최소 15초마다 갱신';
COMMENT ON COLUMN analysis.started_at IS '시작 시각';
COMMENT ON COLUMN analysis.completed_at IS '완료·실패 시각';

-- 분석 · 분석 쪽 묶음
CREATE TABLE analysis_chunk (
    chunk_id BIGSERIAL NOT NULL,
    analysis_id BIGINT NOT NULL,
    chunk_no INT NOT NULL,
    page_from INT NOT NULL,
    page_to INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    result_json JSONB,
    retry_count INT NOT NULL,
    PRIMARY KEY (chunk_id)
);
COMMENT ON TABLE analysis_chunk IS '분석 쪽 묶음';
COMMENT ON COLUMN analysis_chunk.chunk_id IS '묶음 ID';
COMMENT ON COLUMN analysis_chunk.analysis_id IS '분석';
COMMENT ON COLUMN analysis_chunk.chunk_no IS '묶음 번호';
COMMENT ON COLUMN analysis_chunk.page_from IS '시작 쪽';
COMMENT ON COLUMN analysis_chunk.page_to IS '끝 쪽 (경계 쪽은 다음 묶음과 겹칠 수 있음)';
COMMENT ON COLUMN analysis_chunk.status IS 'PENDING / DONE / FAILED';
COMMENT ON COLUMN analysis_chunk.result_json IS '이 묶음의 1차 응답';
COMMENT ON COLUMN analysis_chunk.retry_count IS '재시도 횟수';

-- 분석 · 주요 조건
CREATE TABLE analysis_key_term (
    key_term_id BIGSERIAL NOT NULL,
    analysis_id BIGINT NOT NULL,
    clause_id BIGINT,
    label VARCHAR(30) NOT NULL,
    value VARCHAR(200),
    value_json JSONB,
    state VARCHAR(20) NOT NULL,
    source VARCHAR(10),
    evidence VARCHAR(300),
    sort_order INT NOT NULL,
    PRIMARY KEY (key_term_id)
);
COMMENT ON TABLE analysis_key_term IS '주요 조건';
COMMENT ON COLUMN analysis_key_term.key_term_id IS 'ID';
COMMENT ON COLUMN analysis_key_term.analysis_id IS '분석';
COMMENT ON COLUMN analysis_key_term.clause_id IS '이 값이 나온 조항. 1차 출력의 clauseKey를 병합 후 조항 ID로 바꿔 저장';
COMMENT ON COLUMN analysis_key_term.label IS '임금, 휴일, 보증금 ...';
COMMENT ON COLUMN analysis_key_term.value IS '화면에 보여 줄 값 (시급 10,000원)';
COMMENT ON COLUMN analysis_key_term.value_json IS '계산용 값. 항목별 모양 고정 (임금 {type, amount}, 근로시간 {hoursPerDay, daysPerWeek}, 계약기간 {start, end})';
COMMENT ON COLUMN analysis_key_term.state IS 'OK / NEEDS_CHECK / MISSING / UNREADABLE / MASKED';
COMMENT ON COLUMN analysis_key_term.source IS 'WRITTEN / MANUAL';
COMMENT ON COLUMN analysis_key_term.evidence IS '원문 위치와 문장';
COMMENT ON COLUMN analysis_key_term.sort_order IS '표시 순서';

-- 분석 · 분석 조항
CREATE TABLE analysis_clause (
    clause_id BIGSERIAL NOT NULL,
    analysis_id BIGINT NOT NULL,
    clause_key VARCHAR(20) NOT NULL,
    article_no VARCHAR(20),
    title VARCHAR(100),
    original_text TEXT,
    one_line VARCHAR(200),
    page_no INT NOT NULL,
    location VARCHAR(100),
    issue_found BOOLEAN NOT NULL,
    issue_summary TEXT,
    issue_tag_code VARCHAR(40),
    law_hints JSONB,
    search_keywords JSONB,
    verdict VARCHAR(20) DEFAULT 'JUDGING' NOT NULL,
    basis_type VARCHAR(20),
    headline VARCHAR(150),
    easy_explanation TEXT,
    why_check TEXT,
    comparison_json JSONB,
    ask_counterpart_json JSONB,
    priority INT,
    excluded BOOLEAN DEFAULT false NOT NULL,
    PRIMARY KEY (clause_id)
);
COMMENT ON TABLE analysis_clause IS '분석 조항';
COMMENT ON COLUMN analysis_clause.clause_id IS '조항 ID';
COMMENT ON COLUMN analysis_clause.analysis_id IS '분석';
COMMENT ON COLUMN analysis_clause.clause_key IS '분석 안에서 쓰는 키. GPT 값을 쓰지 않고 서버가 병합 때 쪽·위치 순서로 c1, c2 ...를 새로 매김';
COMMENT ON COLUMN analysis_clause.article_no IS '제7조';
COMMENT ON COLUMN analysis_clause.title IS '조항 제목. 문서에 없으면 null (화면은 article_no, 그다음 one_line 앞부분)';
COMMENT ON COLUMN analysis_clause.original_text IS '원문. verdict = UNREADABLE일 때만 null';
COMMENT ON COLUMN analysis_clause.one_line IS '목차용 한 줄 요약';
COMMENT ON COLUMN analysis_clause.page_no IS '쪽';
COMMENT ON COLUMN analysis_clause.location IS '쪽 안 위치 설명';
COMMENT ON COLUMN analysis_clause.issue_found IS '1차에서 문제 후보로 짚었는지';
COMMENT ON COLUMN analysis_clause.issue_summary IS 'AI가 쓴 문제 요약';
COMMENT ON COLUMN analysis_clause.issue_tag_code IS '꼬리표. 꼬리표 없는 문제, 목록에 없는 값을 받은 경우 null';
COMMENT ON COLUMN analysis_clause.law_hints IS 'AI가 떠올린 법령 [{law, article}]. DB 확인 전';
COMMENT ON COLUMN analysis_clause.search_keywords IS '법령 DB 검색어 배열 (예: ["위약금", "교육비"])';
COMMENT ON COLUMN analysis_clause.verdict IS 'JUDGING(판정 중, 1차와 2차 사이) / OK / NEEDS_CHECK / UNCERTAIN / UNREADABLE';
COMMENT ON COLUMN analysis_clause.basis_type IS 'RULE / AI_INTERPRETATION';
COMMENT ON COLUMN analysis_clause.headline IS '결과 카드 제목';
COMMENT ON COLUMN analysis_clause.easy_explanation IS '쉬운 설명';
COMMENT ON COLUMN analysis_clause.why_check IS '확인이 필요한 이유';
COMMENT ON COLUMN analysis_clause.comparison_json IS '숫자 비교 (계약서 시급 < 최저임금)';
COMMENT ON COLUMN analysis_clause.ask_counterpart_json IS '상대방에게 확인할 것';
COMMENT ON COLUMN analysis_clause.priority IS '우선 확인 순서. null이면 우선 목록에 안 나옴';
COMMENT ON COLUMN analysis_clause.excluded IS '8단계 검증에서 제외 (원문에 없는 조항 등). 지우지 않고 표시만, 조회 API는 false만 반환';

-- 분석 · 조항 근거
CREATE TABLE analysis_clause_ref (
    ref_id BIGSERIAL NOT NULL,
    clause_id BIGINT NOT NULL,
    ref_label VARCHAR(10) NOT NULL,
    law_article_id BIGINT,
    guidance_id BIGINT,
    found_by VARCHAR(20) NOT NULL,
    cited BOOLEAN NOT NULL,
    ref_title VARCHAR(150) NOT NULL,
    ref_text TEXT NOT NULL,
    PRIMARY KEY (ref_id)
);
COMMENT ON TABLE analysis_clause_ref IS '조항 근거';
COMMENT ON COLUMN analysis_clause_ref.ref_id IS 'ID';
COMMENT ON COLUMN analysis_clause_ref.clause_id IS '조항';
COMMENT ON COLUMN analysis_clause_ref.ref_label IS '2차 호출에서 붙인 ID (L1, G3). 조항 안에서만 유일';
COMMENT ON COLUMN analysis_clause_ref.law_article_id IS '조문';
COMMENT ON COLUMN analysis_clause_ref.guidance_id IS '해석 메모';
COMMENT ON COLUMN analysis_clause_ref.found_by IS 'RULE / TAG_MAP / AI_HINT / SEARCH / NEED_MORE';
COMMENT ON COLUMN analysis_clause_ref.cited IS 'AI가 인용하고 검증을 통과했는지 (RULE은 true)';
COMMENT ON COLUMN analysis_clause_ref.ref_title IS '근거 제목 스냅샷 (근로기준법 제20조 / 교육비 반환 해석 메모)';
COMMENT ON COLUMN analysis_clause_ref.ref_text IS 'AI에게 보낸 근거 내용 그대로 (나중에 법령·메모가 바뀌어도 당시 근거 유지)';

-- 분석 · 관련 조항
CREATE TABLE analysis_clause_relation (
    analysis_id BIGINT NOT NULL,
    clause_id BIGINT NOT NULL,
    related_clause_id BIGINT NOT NULL,
    PRIMARY KEY (clause_id, related_clause_id)
);
COMMENT ON TABLE analysis_clause_relation IS '관련 조항';
COMMENT ON COLUMN analysis_clause_relation.analysis_id IS '분석 (두 조항이 같은 분석인지 DB가 확인하는 데 씀)';
COMMENT ON COLUMN analysis_clause_relation.clause_id IS '판단한 조항';
COMMENT ON COLUMN analysis_clause_relation.related_clause_id IS '함께 봐야 하는 조항 (같은 분석 안)';

-- 분석 · 규칙 결과 (빠진 항목·기한)
CREATE TABLE analysis_finding (
    finding_id BIGSERIAL NOT NULL,
    analysis_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(300) NOT NULL,
    due_date DATE,
    law_article_id BIGINT,
    sort_order INT NOT NULL,
    PRIMARY KEY (finding_id)
);
COMMENT ON TABLE analysis_finding IS '규칙 결과 (빠진 항목·기한)';
COMMENT ON COLUMN analysis_finding.finding_id IS 'ID';
COMMENT ON COLUMN analysis_finding.analysis_id IS '분석';
COMMENT ON COLUMN analysis_finding.kind IS 'MISSING_ITEM / DEADLINE';
COMMENT ON COLUMN analysis_finding.name IS '휴일 / 임대차 신고 대상';
COMMENT ON COLUMN analysis_finding.description IS '주휴일을 꼭 적어야 해요 / 계약일로부터 30일 안에 신고';
COMMENT ON COLUMN analysis_finding.due_date IS 'DEADLINE의 기한. 계약일이 없으면 null';
COMMENT ON COLUMN analysis_finding.law_article_id IS '근거 조문';
COMMENT ON COLUMN analysis_finding.sort_order IS '표시 순서';

-- 분석 · 일정 후보
CREATE TABLE schedule_candidate (
    candidate_id BIGSERIAL NOT NULL,
    analysis_id BIGINT NOT NULL,
    date_kind VARCHAR(30) NOT NULL,
    title VARCHAR(50) NOT NULL,
    date DATE,
    source VARCHAR(10) NOT NULL,
    location VARCHAR(100),
    page_no INT,
    conflict_group VARCHAR(30),
    default_selected BOOLEAN NOT NULL,
    PRIMARY KEY (candidate_id)
);
COMMENT ON TABLE schedule_candidate IS '일정 후보';
COMMENT ON COLUMN schedule_candidate.candidate_id IS '후보 ID';
COMMENT ON COLUMN schedule_candidate.analysis_id IS '분석';
COMMENT ON COLUMN schedule_candidate.date_kind IS 'CONTRACT_START / CONTRACT_END / MOVE_IN / PAYDAY / RENEWAL_NOTICE / REPORT_DEADLINE / WAGE_CHANGE ...';
COMMENT ON COLUMN schedule_candidate.title IS '일정 이름';
COMMENT ON COLUMN schedule_candidate.date IS '계약서에 없으면 null (누락 후보)';
COMMENT ON COLUMN schedule_candidate.source IS 'WRITTEN / REVIEW (법정 기한·고시 기준 계산은 REVIEW)';
COMMENT ON COLUMN schedule_candidate.location IS '1쪽 제1조 / 2027년 최저임금 고시 기준';
COMMENT ON COLUMN schedule_candidate.page_no IS '쪽';
COMMENT ON COLUMN schedule_candidate.conflict_group IS '같은 항목의 다른 날짜끼리 같은 값';
COMMENT ON COLUMN schedule_candidate.default_selected IS '처음 선택 상태';

-- 비교 · 수정본 비교
CREATE TABLE comparison (
    comparison_id BIGSERIAL NOT NULL,
    user_id UUID NOT NULL,
    previous_contract_id BIGINT NOT NULL,
    previous_version_id BIGINT NOT NULL,
    revised_contract_id BIGINT,
    revised_version_id BIGINT NOT NULL,
    revised_analysis_id BIGINT NOT NULL,
    compatibility VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    unchanged_summary VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    PRIMARY KEY (comparison_id)
);
COMMENT ON TABLE comparison IS '수정본 비교';
COMMENT ON COLUMN comparison.comparison_id IS '비교 ID';
COMMENT ON COLUMN comparison.user_id IS '소유자';
COMMENT ON COLUMN comparison.previous_contract_id IS '이전본 (보관된 계약서)';
COMMENT ON COLUMN comparison.previous_version_id IS '비교 당시 이전본 버전';
COMMENT ON COLUMN comparison.revised_contract_id IS '수정본 임시 계약서. 확정 후 null';
COMMENT ON COLUMN comparison.revised_version_id IS '수정본 버전';
COMMENT ON COLUMN comparison.revised_analysis_id IS '비교에 쓴 수정본 분석';
COMMENT ON COLUMN comparison.compatibility IS 'FULL / PARTIAL (IMPOSSIBLE은 저장하지 않음)';
COMMENT ON COLUMN comparison.status IS 'PENDING_CONFIRM / CONFIRMED / DISCARDED(폐기, 정리 배치로 수정본 삭제) / SUPERSEDED(다른 확정·재업로드로 낡음)';
COMMENT ON COLUMN comparison.unchanged_summary IS '그대로인 곳 요약';
COMMENT ON COLUMN comparison.created_at IS '생성 시각';
COMMENT ON COLUMN comparison.updated_at IS '수정 시각 (BaseEntity)';
COMMENT ON COLUMN comparison.closed_at IS '확정·폐기·대체 시각';

-- 비교 · 비교 변경 항목
CREATE TABLE comparison_change (
    change_id BIGSERIAL NOT NULL,
    comparison_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    category VARCHAR(10) NOT NULL,
    label VARCHAR(100) NOT NULL,
    before_text TEXT,
    after_text TEXT,
    note VARCHAR(200),
    source VARCHAR(10) NOT NULL,
    location VARCHAR(100),
    reason VARCHAR(200),
    PRIMARY KEY (change_id)
);
COMMENT ON TABLE comparison_change IS '비교 변경 항목';
COMMENT ON COLUMN comparison_change.change_id IS 'ID (비교 못 한 칸의 uncomparedId와 같음)';
COMMENT ON COLUMN comparison_change.comparison_id IS '비교';
COMMENT ON COLUMN comparison_change.type IS 'CHANGED / ADDED / REMOVED / UNCOMPARED (직접 입력하면 CHANGED로 바뀜)';
COMMENT ON COLUMN comparison_change.category IS 'AMOUNT / DATE / CLAUSE';
COMMENT ON COLUMN comparison_change.label IS '항목 이름';
COMMENT ON COLUMN comparison_change.before_text IS '이전 v1 값';
COMMENT ON COLUMN comparison_change.after_text IS '수정 v2 값';
COMMENT ON COLUMN comparison_change.note IS '영향 설명';
COMMENT ON COLUMN comparison_change.source IS 'WRITTEN / MANUAL';
COMMENT ON COLUMN comparison_change.location IS '비교 못 한 위치';
COMMENT ON COLUMN comparison_change.reason IS '비교 못 한 이유';

-- 일정·알림 · 일정
CREATE TABLE schedule (
    schedule_id BIGSERIAL NOT NULL,
    user_id UUID NOT NULL,
    contract_id BIGINT,
    candidate_id BIGINT UNIQUE,
    date_kind VARCHAR(30),
    title VARCHAR(50) NOT NULL,
    date DATE NOT NULL,
    source VARCHAR(10) NOT NULL,
    location VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (schedule_id)
);
COMMENT ON TABLE schedule IS '일정';
COMMENT ON COLUMN schedule.schedule_id IS '일정 ID';
COMMENT ON COLUMN schedule.user_id IS '소유자';
COMMENT ON COLUMN schedule.contract_id IS '연결된 계약서';
COMMENT ON COLUMN schedule.candidate_id IS '저장한 후보. 직접 추가는 null. 같은 후보는 한 번만';
COMMENT ON COLUMN schedule.date_kind IS '후보와 같은 값. 수정본 일정 변경 때 짝짓기에 사용';
COMMENT ON COLUMN schedule.title IS '일정 이름';
COMMENT ON COLUMN schedule.date IS '날짜';
COMMENT ON COLUMN schedule.source IS 'WRITTEN / MANUAL / REVIEW (서버가 결정)';
COMMENT ON COLUMN schedule.location IS '날짜가 나온 위치';
COMMENT ON COLUMN schedule.created_at IS '생성 시각';
COMMENT ON COLUMN schedule.updated_at IS '수정 시각';

-- 일정·알림 · 알림
CREATE TABLE notification (
    notification_id BIGSERIAL NOT NULL,
    user_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    title VARCHAR(100) NOT NULL,
    sub_title VARCHAR(100),
    schedule_id BIGINT,
    analysis_id BIGINT,
    due_offset INT,
    due_date DATE,
    is_read BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (notification_id)
);
COMMENT ON TABLE notification IS '알림';
COMMENT ON COLUMN notification.notification_id IS '알림 ID';
COMMENT ON COLUMN notification.user_id IS '받는 사람';
COMMENT ON COLUMN notification.type IS 'SCHEDULE_DUE / ANALYSIS_DONE / ANALYSIS_FAILED';
COMMENT ON COLUMN notification.title IS '제목';
COMMENT ON COLUMN notification.sub_title IS '부제';
COMMENT ON COLUMN notification.schedule_id IS '이동 대상 일정. 일정을 지우면 알림도 함께 삭제';
COMMENT ON COLUMN notification.analysis_id IS '이동 대상 분석';
COMMENT ON COLUMN notification.due_offset IS 'SCHEDULE_DUE가 며칠 전 알림인지 (D-7이면 7, 당일은 0)';
COMMENT ON COLUMN notification.due_date IS '알림을 만들 때의 일정 날짜. 날짜가 바뀌면 새 날짜로 다시 알림';
COMMENT ON COLUMN notification.is_read IS '읽음 여부';
COMMENT ON COLUMN notification.created_at IS '생성 시각';
COMMENT ON COLUMN notification.updated_at IS '수정 시각 (BaseEntity)';

-- 챗봇 · 대화
CREATE TABLE conversation (
    conversation_id BIGSERIAL NOT NULL,
    user_id UUID NOT NULL,
    contract_id BIGINT,
    analysis_id BIGINT,
    comparison_id BIGINT,
    summary TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (conversation_id)
);
COMMENT ON TABLE conversation IS '대화';
COMMENT ON COLUMN conversation.conversation_id IS '대화 ID';
COMMENT ON COLUMN conversation.user_id IS '소유자';
COMMENT ON COLUMN conversation.contract_id IS '연결된 계약서. 일반 질문이면 null. 대화 중 바꾸지 않음';
COMMENT ON COLUMN conversation.analysis_id IS '참고하는 분석';
COMMENT ON COLUMN conversation.comparison_id IS '비교 결과에서 시작한 대화';
COMMENT ON COLUMN conversation.summary IS '오래된 대화 요약 (매번 전체 대화를 보내지 않기 위함)';
COMMENT ON COLUMN conversation.created_at IS '생성 시각';
COMMENT ON COLUMN conversation.updated_at IS '수정 시각';

-- 챗봇 · 대화 메시지
CREATE TABLE chat_message (
    message_id BIGSERIAL NOT NULL,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(10) NOT NULL,
    content TEXT NOT NULL,
    screen VARCHAR(10),
    answer_type VARCHAR(20),
    refs_json JSONB,
    partial_json JSONB,
    actions_json JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (message_id)
);
COMMENT ON TABLE chat_message IS '대화 메시지';
COMMENT ON COLUMN chat_message.message_id IS '메시지 ID';
COMMENT ON COLUMN chat_message.conversation_id IS '대화';
COMMENT ON COLUMN chat_message.role IS 'USER / ASSISTANT';
COMMENT ON COLUMN chat_message.content IS '내용';
COMMENT ON COLUMN chat_message.screen IS '질문한 화면';
COMMENT ON COLUMN chat_message.answer_type IS 'ANSWERED / PARTIAL / OUT_OF_SCOPE';
COMMENT ON COLUMN chat_message.refs_json IS '참고한 정보';
COMMENT ON COLUMN chat_message.partial_json IS '일부만 답할 때 {confirmed, unknown}';
COMMENT ON COLUMN chat_message.actions_json IS '이동 버튼';
COMMENT ON COLUMN chat_message.created_at IS '생성 시각';
COMMENT ON COLUMN chat_message.updated_at IS '수정 시각 (BaseEntity)';

-- AI 호출 기록 · AI 호출 기록
CREATE TABLE ai_call_log (
    log_id BIGSERIAL NOT NULL,
    user_id UUID,
    purpose VARCHAR(20) NOT NULL,
    analysis_id BIGINT,
    chunk_id BIGINT,
    comparison_id BIGINT,
    conversation_id BIGINT,
    model VARCHAR(50) NOT NULL,
    input_tokens INT,
    cached_input_tokens INT,
    output_tokens INT,
    latency_ms INT,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (log_id)
);
COMMENT ON TABLE ai_call_log IS 'AI 호출 기록';
COMMENT ON COLUMN ai_call_log.log_id IS 'ID';
COMMENT ON COLUMN ai_call_log.user_id IS '사용자 (비용·횟수 제한)';
COMMENT ON COLUMN ai_call_log.purpose IS 'EXTRACT / JUDGE / JUDGE_MORE / COMPARE / CHAT';
COMMENT ON COLUMN ai_call_log.analysis_id IS '분석';
COMMENT ON COLUMN ai_call_log.chunk_id IS '쪽 묶음';
COMMENT ON COLUMN ai_call_log.comparison_id IS '비교';
COMMENT ON COLUMN ai_call_log.conversation_id IS '대화';
COMMENT ON COLUMN ai_call_log.model IS '모델 이름';
COMMENT ON COLUMN ai_call_log.input_tokens IS '입력 토큰';
COMMENT ON COLUMN ai_call_log.cached_input_tokens IS '캐시 적용된 입력 토큰';
COMMENT ON COLUMN ai_call_log.output_tokens IS '출력 토큰';
COMMENT ON COLUMN ai_call_log.latency_ms IS '소요 시간';
COMMENT ON COLUMN ai_call_log.status IS 'OK / RETRY / FAILED';
COMMENT ON COLUMN ai_call_log.error_message IS '실패 원인 (저장 전 2,000자로 자름)';
COMMENT ON COLUMN ai_call_log.created_at IS '호출 시각';
COMMENT ON COLUMN ai_call_log.updated_at IS '수정 시각 (BaseEntity)';

-- 규칙 · 숫자 규칙 기준값
CREATE TABLE rule_param (
    rule_code VARCHAR(40) NOT NULL,
    valid_from DATE NOT NULL,
    value_json JSONB NOT NULL,
    law_article_id BIGINT NOT NULL,
    note VARCHAR(200),
    PRIMARY KEY (rule_code, valid_from)
);
COMMENT ON TABLE rule_param IS '숫자 규칙 기준값';
COMMENT ON COLUMN rule_param.rule_code IS 'MIN_WAGE / LEASE_REPORT / RENT_INCREASE_CAP ...';
COMMENT ON COLUMN rule_param.valid_from IS '적용 시작일 (예: 2027-01-01)';
COMMENT ON COLUMN rule_param.value_json IS '기준값 (예: {"hourly": 10700})';
COMMENT ON COLUMN rule_param.law_article_id IS '근거 조문 (필수. 별표에 있는 기준은 별표가 속한 조문)';
COMMENT ON COLUMN rule_param.note IS '출처 메모 (고시 번호 등)';

-- 법령 · 법령
CREATE TABLE law (
    law_id BIGSERIAL NOT NULL,
    name VARCHAR(100) NOT NULL,
    name_norm VARCHAR(100) NOT NULL UNIQUE,
    law_kind VARCHAR(20) NOT NULL,
    parent_law_id BIGINT,
    source_law_id VARCHAR(20) NOT NULL,
    synced_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (law_id)
);
COMMENT ON TABLE law IS '법령';
COMMENT ON COLUMN law.law_id IS '법령 ID';
COMMENT ON COLUMN law.name IS '정식 명칭';
COMMENT ON COLUMN law.name_norm IS '띄어쓰기·기호 제거한 이름';
COMMENT ON COLUMN law.law_kind IS 'API 법종구분 값 그대로 (법률 / 대통령령 / 총리령 / 부령 ...)';
COMMENT ON COLUMN law.parent_law_id IS '시행령·시행규칙이면 상위 법률. 사람이 시드 데이터로 연결';
COMMENT ON COLUMN law.source_law_id IS '국가법령정보센터 법령ID (개정돼도 같음)';
COMMENT ON COLUMN law.synced_at IS '마지막 동기화';

-- 법령 · 법령 약칭
CREATE TABLE law_alias (
    alias_norm VARCHAR(100) NOT NULL,
    law_id BIGINT NOT NULL,
    source VARCHAR(10) NOT NULL,
    PRIMARY KEY (alias_norm)
);
COMMENT ON TABLE law_alias IS '법령 약칭';
COMMENT ON COLUMN law_alias.alias_norm IS '약칭·줄임말 정규화 값';
COMMENT ON COLUMN law_alias.law_id IS '법령';
COMMENT ON COLUMN law_alias.source IS 'API(법령명약칭) / MANUAL(팀이 추가한 줄임말)';

-- 법령 · 조문
CREATE TABLE law_article (
    law_article_id BIGSERIAL NOT NULL,
    law_id BIGINT NOT NULL,
    article_no INT NOT NULL,
    article_branch_no INT DEFAULT 0 NOT NULL,
    paragraph_no INT,
    title VARCHAR(100),
    content TEXT NOT NULL,
    source_mst VARCHAR(20) NOT NULL,
    promulgated_at DATE,
    effective_from DATE NOT NULL,
    effective_to DATE,
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', coalesce(title, '') || ' ' || content)) STORED NOT NULL,
    PRIMARY KEY (law_article_id)
);
COMMENT ON TABLE law_article IS '조문';
COMMENT ON COLUMN law_article.law_article_id IS '조문 ID';
COMMENT ON COLUMN law_article.law_id IS '법령';
COMMENT ON COLUMN law_article.article_no IS 'API 조문번호 (제10조의2 → 10)';
COMMENT ON COLUMN law_article.article_branch_no IS 'API 조문가지번호 (제10조의2 → 2, 없으면 0)';
COMMENT ON COLUMN law_article.paragraph_no IS '항 번호. 항이 없는 조문이면 null';
COMMENT ON COLUMN law_article.title IS '조문 제목';
COMMENT ON COLUMN law_article.content IS '항 내용 + 그 항의 호 내용을 줄바꿈으로 이어 붙임. 항이 없으면 조문 내용 전체';
COMMENT ON COLUMN law_article.source_mst IS '가져온 개정본의 MST(법령 마스터 번호, 개정마다 바뀜)';
COMMENT ON COLUMN law_article.promulgated_at IS '공포일자';
COMMENT ON COLUMN law_article.effective_from IS '조문시행일자';
COMMENT ON COLUMN law_article.effective_to IS '동기화 배치가 다음 버전 시행일 전날로 채움. 현행·시행예정이면 null';
COMMENT ON COLUMN law_article.search_vector IS '전문 검색용. DB가 title + content로 자동 계산 (엔티티에 매핑하지 않음)';

-- 법령 · 문서별 관련 법령
CREATE TABLE document_type_law (
    document_type_code VARCHAR(40) NOT NULL,
    law_id BIGINT NOT NULL,
    core BOOLEAN NOT NULL,
    PRIMARY KEY (document_type_code, law_id)
);
COMMENT ON TABLE document_type_law IS '문서별 관련 법령';
COMMENT ON COLUMN document_type_law.document_type_code IS '문서 종류';
COMMENT ON COLUMN document_type_law.law_id IS '법령';
COMMENT ON COLUMN document_type_law.core IS '핵심 법령 여부 (검색 범위·우선순위)';

-- 법령 · 문제 꼬리표
CREATE TABLE issue_tag (
    code VARCHAR(40) NOT NULL,
    document_type_code VARCHAR(40) NOT NULL,
    name VARCHAR(50) NOT NULL,
    PRIMARY KEY (code)
);
COMMENT ON TABLE issue_tag IS '문제 꼬리표';
COMMENT ON COLUMN issue_tag.code IS 'PENALTY_ON_RESIGNATION ...';
COMMENT ON COLUMN issue_tag.document_type_code IS '문서 종류';
COMMENT ON COLUMN issue_tag.name IS '퇴사·위반 시 금전 부담';

-- 법령 · 꼬리표-조문 매핑
CREATE TABLE issue_tag_article (
    issue_tag_code VARCHAR(40) NOT NULL,
    law_article_id BIGINT NOT NULL,
    priority INT NOT NULL,
    PRIMARY KEY (issue_tag_code, law_article_id)
);
COMMENT ON TABLE issue_tag_article IS '꼬리표-조문 매핑';
COMMENT ON COLUMN issue_tag_article.issue_tag_code IS '꼬리표';
COMMENT ON COLUMN issue_tag_article.law_article_id IS '조문';
COMMENT ON COLUMN issue_tag_article.priority IS '우선순위';

-- 법령 · 해석 메모
CREATE TABLE guidance (
    guidance_id BIGSERIAL NOT NULL,
    issue_tag_code VARCHAR(40) NOT NULL,
    title VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    source_note VARCHAR(200) NOT NULL,
    source_type VARCHAR(20),
    source_ref VARCHAR(40),
    reviewed_at DATE NOT NULL,
    PRIMARY KEY (guidance_id)
);
COMMENT ON TABLE guidance IS '해석 메모';
COMMENT ON COLUMN guidance.guidance_id IS '메모 ID';
COMMENT ON COLUMN guidance.issue_tag_code IS '꼬리표';
COMMENT ON COLUMN guidance.title IS '제목';
COMMENT ON COLUMN guidance.content IS '언제 문제가 되고 언제 괜찮은지';
COMMENT ON COLUMN guidance.source_note IS '판례·해석례 출처 (사람이 읽는 설명)';
COMMENT ON COLUMN guidance.source_type IS 'EXPC(법령해석례) / MOEL(고용노동부 해석) / CASE(판례) / MANUAL';
COMMENT ON COLUMN guidance.source_ref IS '해석례 일련번호 등 원문을 다시 찾을 번호';
COMMENT ON COLUMN guidance.reviewed_at IS '팀이 검토한 날';

-- 외래 키
ALTER TABLE idempotency_record ADD CONSTRAINT fk_idempotency_record_user_id FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE contract ADD CONSTRAINT fk_contract_user_id FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE contract ADD CONSTRAINT fk_contract_document_type_code FOREIGN KEY (document_type_code) REFERENCES document_type (code);
ALTER TABLE contract_version ADD CONSTRAINT fk_contract_version_contract_id FOREIGN KEY (contract_id) REFERENCES contract (contract_id);
ALTER TABLE contract_version ADD CONSTRAINT fk_contract_version_latest_analysis_id FOREIGN KEY (latest_analysis_id) REFERENCES analysis (analysis_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE contract_page ADD CONSTRAINT fk_contract_page_version_id FOREIGN KEY (version_id) REFERENCES contract_version (version_id);
ALTER TABLE analysis ADD CONSTRAINT fk_analysis_version_id FOREIGN KEY (version_id) REFERENCES contract_version (version_id);
ALTER TABLE analysis ADD CONSTRAINT fk_analysis_document_type_code FOREIGN KEY (document_type_code) REFERENCES document_type (code);
ALTER TABLE analysis ADD CONSTRAINT fk_analysis_similar_contract_id FOREIGN KEY (similar_contract_id) REFERENCES contract (contract_id) ON DELETE SET NULL;
ALTER TABLE analysis_chunk ADD CONSTRAINT fk_analysis_chunk_analysis_id FOREIGN KEY (analysis_id) REFERENCES analysis (analysis_id);
ALTER TABLE analysis_key_term ADD CONSTRAINT fk_analysis_key_term_analysis_id FOREIGN KEY (analysis_id) REFERENCES analysis (analysis_id);
ALTER TABLE analysis_key_term ADD CONSTRAINT fk_analysis_key_term_clause_id FOREIGN KEY (clause_id) REFERENCES analysis_clause (clause_id);
ALTER TABLE analysis_clause ADD CONSTRAINT fk_analysis_clause_analysis_id FOREIGN KEY (analysis_id) REFERENCES analysis (analysis_id);
ALTER TABLE analysis_clause ADD CONSTRAINT fk_analysis_clause_issue_tag_code FOREIGN KEY (issue_tag_code) REFERENCES issue_tag (code);
ALTER TABLE analysis_clause_ref ADD CONSTRAINT fk_analysis_clause_ref_clause_id FOREIGN KEY (clause_id) REFERENCES analysis_clause (clause_id);
ALTER TABLE analysis_clause_ref ADD CONSTRAINT fk_analysis_clause_ref_law_article_id FOREIGN KEY (law_article_id) REFERENCES law_article (law_article_id);
ALTER TABLE analysis_clause_ref ADD CONSTRAINT fk_analysis_clause_ref_guidance_id FOREIGN KEY (guidance_id) REFERENCES guidance (guidance_id);
ALTER TABLE analysis_clause_relation ADD CONSTRAINT fk_analysis_clause_relation_analysis_id FOREIGN KEY (analysis_id) REFERENCES analysis (analysis_id);
ALTER TABLE analysis_clause_relation ADD CONSTRAINT fk_analysis_clause_relation_clause_id FOREIGN KEY (clause_id) REFERENCES analysis_clause (clause_id);
ALTER TABLE analysis_clause_relation ADD CONSTRAINT fk_analysis_clause_relation_related_clause_id FOREIGN KEY (related_clause_id) REFERENCES analysis_clause (clause_id);
ALTER TABLE analysis_finding ADD CONSTRAINT fk_analysis_finding_analysis_id FOREIGN KEY (analysis_id) REFERENCES analysis (analysis_id);
ALTER TABLE analysis_finding ADD CONSTRAINT fk_analysis_finding_law_article_id FOREIGN KEY (law_article_id) REFERENCES law_article (law_article_id);
ALTER TABLE schedule_candidate ADD CONSTRAINT fk_schedule_candidate_analysis_id FOREIGN KEY (analysis_id) REFERENCES analysis (analysis_id);
ALTER TABLE comparison ADD CONSTRAINT fk_comparison_user_id FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE comparison ADD CONSTRAINT fk_comparison_previous_contract_id FOREIGN KEY (previous_contract_id) REFERENCES contract (contract_id);
ALTER TABLE comparison ADD CONSTRAINT fk_comparison_previous_version_id FOREIGN KEY (previous_version_id) REFERENCES contract_version (version_id);
ALTER TABLE comparison ADD CONSTRAINT fk_comparison_revised_contract_id FOREIGN KEY (revised_contract_id) REFERENCES contract (contract_id) ON DELETE SET NULL;
ALTER TABLE comparison ADD CONSTRAINT fk_comparison_revised_version_id FOREIGN KEY (revised_version_id) REFERENCES contract_version (version_id);
ALTER TABLE comparison ADD CONSTRAINT fk_comparison_revised_analysis_id FOREIGN KEY (revised_analysis_id) REFERENCES analysis (analysis_id);
ALTER TABLE comparison_change ADD CONSTRAINT fk_comparison_change_comparison_id FOREIGN KEY (comparison_id) REFERENCES comparison (comparison_id);
ALTER TABLE schedule ADD CONSTRAINT fk_schedule_user_id FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE schedule ADD CONSTRAINT fk_schedule_contract_id FOREIGN KEY (contract_id) REFERENCES contract (contract_id) ON DELETE SET NULL;
ALTER TABLE schedule ADD CONSTRAINT fk_schedule_candidate_id FOREIGN KEY (candidate_id) REFERENCES schedule_candidate (candidate_id) ON DELETE SET NULL;
ALTER TABLE notification ADD CONSTRAINT fk_notification_user_id FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE notification ADD CONSTRAINT fk_notification_schedule_id FOREIGN KEY (schedule_id) REFERENCES schedule (schedule_id) ON DELETE CASCADE;
ALTER TABLE notification ADD CONSTRAINT fk_notification_analysis_id FOREIGN KEY (analysis_id) REFERENCES analysis (analysis_id) ON DELETE SET NULL;
ALTER TABLE conversation ADD CONSTRAINT fk_conversation_user_id FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE conversation ADD CONSTRAINT fk_conversation_contract_id FOREIGN KEY (contract_id) REFERENCES contract (contract_id) ON DELETE SET NULL;
ALTER TABLE conversation ADD CONSTRAINT fk_conversation_analysis_id FOREIGN KEY (analysis_id) REFERENCES analysis (analysis_id) ON DELETE SET NULL;
ALTER TABLE conversation ADD CONSTRAINT fk_conversation_comparison_id FOREIGN KEY (comparison_id) REFERENCES comparison (comparison_id) ON DELETE SET NULL;
ALTER TABLE chat_message ADD CONSTRAINT fk_chat_message_conversation_id FOREIGN KEY (conversation_id) REFERENCES conversation (conversation_id);
ALTER TABLE ai_call_log ADD CONSTRAINT fk_ai_call_log_user_id FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE SET NULL;
ALTER TABLE ai_call_log ADD CONSTRAINT fk_ai_call_log_analysis_id FOREIGN KEY (analysis_id) REFERENCES analysis (analysis_id) ON DELETE SET NULL;
ALTER TABLE ai_call_log ADD CONSTRAINT fk_ai_call_log_chunk_id FOREIGN KEY (chunk_id) REFERENCES analysis_chunk (chunk_id) ON DELETE SET NULL;
ALTER TABLE ai_call_log ADD CONSTRAINT fk_ai_call_log_comparison_id FOREIGN KEY (comparison_id) REFERENCES comparison (comparison_id) ON DELETE SET NULL;
ALTER TABLE ai_call_log ADD CONSTRAINT fk_ai_call_log_conversation_id FOREIGN KEY (conversation_id) REFERENCES conversation (conversation_id) ON DELETE SET NULL;
ALTER TABLE rule_param ADD CONSTRAINT fk_rule_param_law_article_id FOREIGN KEY (law_article_id) REFERENCES law_article (law_article_id);
ALTER TABLE law ADD CONSTRAINT fk_law_parent_law_id FOREIGN KEY (parent_law_id) REFERENCES law (law_id);
ALTER TABLE law_alias ADD CONSTRAINT fk_law_alias_law_id FOREIGN KEY (law_id) REFERENCES law (law_id);
ALTER TABLE law_article ADD CONSTRAINT fk_law_article_law_id FOREIGN KEY (law_id) REFERENCES law (law_id);
ALTER TABLE document_type_law ADD CONSTRAINT fk_document_type_law_document_type_code FOREIGN KEY (document_type_code) REFERENCES document_type (code);
ALTER TABLE document_type_law ADD CONSTRAINT fk_document_type_law_law_id FOREIGN KEY (law_id) REFERENCES law (law_id);
ALTER TABLE issue_tag ADD CONSTRAINT fk_issue_tag_document_type_code FOREIGN KEY (document_type_code) REFERENCES document_type (code);
ALTER TABLE issue_tag_article ADD CONSTRAINT fk_issue_tag_article_issue_tag_code FOREIGN KEY (issue_tag_code) REFERENCES issue_tag (code);
ALTER TABLE issue_tag_article ADD CONSTRAINT fk_issue_tag_article_law_article_id FOREIGN KEY (law_article_id) REFERENCES law_article (law_article_id);
ALTER TABLE guidance ADD CONSTRAINT fk_guidance_issue_tag_code FOREIGN KEY (issue_tag_code) REFERENCES issue_tag (code);

-- 인덱스·제약
CREATE INDEX idx_idempotency_created ON idempotency_record (created_at);
CREATE INDEX idx_contract_user_list ON contract (user_id, archived, updated_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_contract_cleanup ON contract (archived, created_at) WHERE deleted_at IS NULL;
ALTER TABLE contract ADD CONSTRAINT uq_contract_owner UNIQUE (contract_id, user_id);
CREATE UNIQUE INDEX uq_version_no ON contract_version (contract_id, version_no);
ALTER TABLE contract_version ADD CONSTRAINT uq_version_contract UNIQUE (version_id, contract_id);
CREATE UNIQUE INDEX uq_page_no ON contract_page (version_id, page_no);
CREATE INDEX idx_analysis_version ON analysis (version_id);
CREATE UNIQUE INDEX uq_analysis_running ON analysis (version_id) WHERE status = 'ANALYZING';
ALTER TABLE analysis ADD CONSTRAINT ck_analysis_status CHECK (status IN ('ANALYZING','COMPLETED','FAILED'));
ALTER TABLE analysis ADD CONSTRAINT ck_analysis_step CHECK (progress_step IN ('READING_TEXT','FINDING_TERMS','MATCHING_LAWS','SUMMARIZING','DONE'));
ALTER TABLE analysis ADD CONSTRAINT ck_analysis_fail CHECK ((status = 'FAILED') = (fail_reason IS NOT NULL));
ALTER TABLE analysis ADD CONSTRAINT ck_analysis_done CHECK ((status = 'ANALYZING') = (completed_at IS NULL));
ALTER TABLE analysis ADD CONSTRAINT uq_analysis_version UNIQUE (analysis_id, version_id);
ALTER TABLE contract_version ADD CONSTRAINT fk_latest_same_version FOREIGN KEY (latest_analysis_id, version_id) REFERENCES analysis (analysis_id, version_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX idx_chunk_analysis ON analysis_chunk (analysis_id);
ALTER TABLE analysis_chunk ADD CONSTRAINT uq_chunk_no UNIQUE (analysis_id, chunk_no);
ALTER TABLE analysis_chunk ADD CONSTRAINT ck_chunk_pages CHECK (page_from <= page_to);
ALTER TABLE analysis_chunk ADD CONSTRAINT ck_chunk_status CHECK (status IN ('PENDING','DONE','FAILED'));
CREATE UNIQUE INDEX uq_key_term ON analysis_key_term (analysis_id, label);
CREATE UNIQUE INDEX uq_clause_key ON analysis_clause (analysis_id, clause_key);
ALTER TABLE analysis_clause ADD CONSTRAINT uq_clause_analysis UNIQUE (analysis_id, clause_id);
ALTER TABLE analysis_clause ADD CONSTRAINT ck_clause_verdict CHECK (verdict IN ('JUDGING','OK','NEEDS_CHECK','UNCERTAIN','UNREADABLE'));
ALTER TABLE analysis_clause ADD CONSTRAINT ck_clause_text CHECK (original_text IS NOT NULL OR verdict = 'UNREADABLE');
CREATE UNIQUE INDEX uq_clause_ref ON analysis_clause_ref (clause_id, ref_label);
ALTER TABLE analysis_clause_ref ADD CONSTRAINT ck_ref_one_target CHECK ((law_article_id IS NULL) <> (guidance_id IS NULL));
ALTER TABLE analysis_clause_ref ADD CONSTRAINT ck_ref_found_by CHECK (found_by IN ('RULE','TAG_MAP','AI_HINT','SEARCH','NEED_MORE'));
ALTER TABLE analysis_clause_relation ADD CONSTRAINT ck_relation_self CHECK (clause_id <> related_clause_id);
ALTER TABLE analysis_clause_relation ADD CONSTRAINT fk_relation_same_analysis FOREIGN KEY (analysis_id, clause_id) REFERENCES analysis_clause (analysis_id, clause_id);
ALTER TABLE analysis_clause_relation ADD CONSTRAINT fk_relation_related_same_analysis FOREIGN KEY (analysis_id, related_clause_id) REFERENCES analysis_clause (analysis_id, clause_id);
CREATE INDEX idx_finding_analysis ON analysis_finding (analysis_id);
CREATE INDEX idx_candidate_analysis ON schedule_candidate (analysis_id);
CREATE INDEX idx_comparison_user ON comparison (user_id);
CREATE INDEX idx_comparison_previous ON comparison (previous_contract_id, status);
CREATE UNIQUE INDEX uq_comparison_pending ON comparison (previous_contract_id, revised_contract_id) WHERE status = 'PENDING_CONFIRM';
ALTER TABLE comparison ADD CONSTRAINT ck_comparison_status CHECK (status IN ('PENDING_CONFIRM','CONFIRMED','DISCARDED','SUPERSEDED'));
ALTER TABLE comparison ADD CONSTRAINT fk_comparison_prev_pair FOREIGN KEY (previous_version_id, previous_contract_id) REFERENCES contract_version (version_id, contract_id);
CREATE INDEX idx_change_comparison ON comparison_change (comparison_id);
CREATE INDEX idx_schedule_user_date ON schedule (user_id, date);
CREATE INDEX idx_schedule_contract ON schedule (contract_id);
ALTER TABLE schedule ADD CONSTRAINT fk_schedule_owner FOREIGN KEY (contract_id, user_id) REFERENCES contract (contract_id, user_id);
CREATE INDEX idx_notification_user ON notification (user_id, created_at);
CREATE INDEX idx_notification_unread ON notification (user_id, is_read);
CREATE UNIQUE INDEX uq_notification_due ON notification (schedule_id, type, due_offset, due_date) WHERE schedule_id IS NOT NULL;
ALTER TABLE notification ADD CONSTRAINT ck_notification_type CHECK (type IN ('SCHEDULE_DUE','ANALYSIS_DONE','ANALYSIS_FAILED'));
ALTER TABLE notification ADD CONSTRAINT ck_notification_due CHECK (type <> 'SCHEDULE_DUE' OR (schedule_id IS NOT NULL AND due_offset IS NOT NULL AND due_date IS NOT NULL));
CREATE INDEX idx_conversation_user ON conversation (user_id, updated_at);
ALTER TABLE conversation ADD CONSTRAINT fk_conversation_owner FOREIGN KEY (contract_id, user_id) REFERENCES contract (contract_id, user_id);
CREATE INDEX idx_message_conversation ON chat_message (conversation_id, created_at);
CREATE INDEX idx_ai_log_analysis ON ai_call_log (analysis_id);
CREATE UNIQUE INDEX uq_law_article ON law_article (law_id, article_no, article_branch_no, COALESCE(paragraph_no, 0), effective_from);
CREATE INDEX idx_law_article_search ON law_article USING GIN (search_vector);
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_law_article_trgm ON law_article USING GIN (content gin_trgm_ops);
CREATE INDEX idx_guidance_tag ON guidance (issue_tag_code);
