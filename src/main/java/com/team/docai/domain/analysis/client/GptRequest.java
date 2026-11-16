package com.team.docai.domain.analysis.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * GPT 호출 요청. 구조화 출력용 JSON 스키마는 호출하는 쪽(1차·2차·비교)이 각자 넘긴다.
 *
 * @param systemPrompt    시스템 지시문 (없으면 null)
 * @param userContent     사용자 내용 (글자·이미지 조각, 1개 이상)
 * @param schemaName      json_schema.name (영문·숫자·_·- 64자 이하)
 * @param schema          json_schema.schema (strict 모드 규칙을 따라야 함)
 * @param maxOutputTokens max_completion_tokens (null 이면 보내지 않음)
 * @param temperature     temperature (null 이면 보내지 않음)
 */
public record GptRequest(
        String systemPrompt,
        List<GptContentPart> userContent,
        String schemaName,
        Map<String, Object> schema,
        Integer maxOutputTokens,
        Double temperature
) {
    private static final Pattern SCHEMA_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    public GptRequest {
        Objects.requireNonNull(userContent, "userContent");
        Objects.requireNonNull(schema, "schema");
        if (userContent.isEmpty()) {
            throw new IllegalArgumentException("userContent 가 비어 있습니다.");
        }
        if (schemaName == null || !SCHEMA_NAME.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("schemaName 형식이 잘못되었습니다: " + schemaName);
        }
        userContent = List.copyOf(userContent);
    }

    public static GptRequest of(String systemPrompt, List<GptContentPart> userContent,
                                String schemaName, Map<String, Object> schema) {
        return new GptRequest(systemPrompt, userContent, schemaName, schema, null, null);
    }
}
