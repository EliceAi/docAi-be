package com.team.docai.domain.chat.entity;

/** 이동 버튼 종류: 화면 이동 / 조항 상세 / 문서 선택. 이동만 하고 문서 전송·일정 저장 같은 동작은 없다 */
public enum ActionType {
    OPEN_SCREEN,
    OPEN_CLAUSE,
    OPEN_DOCUMENT_TYPE
}
