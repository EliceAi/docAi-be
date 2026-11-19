package com.team.docai.domain.law.client;

/**
 * 일상용어에 연계된 법령용어 하나 (법제처 dlytrmRlt 응답의 연계용어).
 *
 * @param legalTerm    법령용어명
 * @param relationCode 용어관계코드 (140301 동의어, 140302 반의어, 140303 상위어, 140304 하위어, 140305 연관어)
 * @param relation     용어관계 이름
 * @param note         비고 (동음이의어 설명). 없으면 null
 */
public record DailyTermLink(String legalTerm, String relationCode, String relation, String note) {

    public static final String SYNONYM = "140301";
    public static final String ANTONYM = "140302";
    public static final String BROADER = "140303";
    public static final String NARROWER = "140304";
    public static final String RELATED = "140305";
}
