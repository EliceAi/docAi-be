package com.team.docai.domain.law.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 2026-11-17 실제 법제처 응답(캐시)에서 잘라 낸 test resources 로 파서를 확인한다. */
class LawResponseParserTest {

    LawResponseParser parser = new LawResponseParser(new ObjectMapper());

    @Test
    void 목록에서_법령일련번호_법령ID_시행일자_약칭을_읽는다() {
        LawListPage page = parser.parseList(LawTestSupport.resource("labor_standards_act_list.json"));

        assertThat(page.totalCount()).isEqualTo(3);
        assertThat(page.items()).extracting(LawListItem::name)
                .containsExactly("근로기준법", "근로기준법 시행령", "근로기준법 시행규칙");
        LawListItem act = page.items().get(0);
        assertThat(act.mst()).isEqualTo("285279");
        assertThat(act.sourceLawId()).isEqualTo("001872");
        assertThat(act.kind()).isEqualTo("법률");
        assertThat(act.currentCode()).isEqualTo("현행");
        assertThat(act.abbreviation()).isNull();
        assertThat(act.effectiveDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(act.promulgatedAt()).isEqualTo(LocalDate.of(2026, 4, 7));
    }

    @Test
    void 본문을_항_단위로_나누고_장제목_삭제조문_삭제항은_버린다() {
        ParsedLaw law = parser.parseBody(LawTestSupport.resource("labor_standards_act_body_sample.json"));

        assertThat(law.name()).isEqualTo("근로기준법");
        assertThat(law.sourceLawId()).isEqualTo("001872");
        assertThat(law.kind()).isEqualTo("법률");
        assertThat(law.effectiveDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(law.promulgatedAt()).isEqualTo(LocalDate.of(2026, 4, 7));
        assertThat(law.headings()).isEqualTo(1);          // 제1장 총칙
        assertThat(law.deletedArticles()).isEqualTo(1);   // 제35조 삭제
        assertThat(law.deletedParagraphs()).isEqualTo(1); // 제60조 ③ 삭제
        // 제1조 1 + 제17조 2 + 제26조 1 + 제43조의2 5 + 제60조 6
        assertThat(law.articles()).hasSize(15);
        assertThat(law.articles()).allMatch(a -> a.effectiveFrom().equals(LocalDate.of(2026, 10, 8)));
    }

    @Test
    void 항이_없는_조문은_항번호_null_한_행에_조문_전체() {
        ParsedArticle a1 = find(parser.parseBody(LawTestSupport.resource("labor_standards_act_body_sample.json")).articles(), 1, 0, null);
        assertThat(a1.title()).isEqualTo("목적");
        assertThat(a1.content()).startsWith("제1조(목적) 이 법은 헌법에 따라");
    }

    @Test
    void 원문자_항번호를_숫자로_바꾸고_호를_줄바꿈으로_이어_붙인다() {
        List<ParsedArticle> rows = parser.parseBody(LawTestSupport.resource("labor_standards_act_body_sample.json")).articles();
        ParsedArticle p1 = find(rows, 17, 0, 1);

        assertThat(p1.title()).isEqualTo("근로조건의 명시");
        assertThat(p1.content().split("\n")).containsExactly(
                "① 사용자는 근로계약을 체결할 때에 근로자에게 다음 각 호의 사항을 명시하여야 한다. 근로계약 체결 후 다음 각 호의 사항을 변경하는 경우에도 또한 같다. <개정 2010.5.25>",
                "1. 임금", "2. 소정근로시간", "3. 제55조에 따른 휴일", "4. 제60조에 따른 연차 유급휴가",
                "5. 그 밖에 대통령령으로 정하는 근로조건");
        assertThat(find(rows, 17, 0, 2).content()).startsWith("② 사용자는 제1항제1호와 관련한");
    }

    @Test
    void 항번호_없이_호만_있는_항은_조문_본문_뒤에_호를_붙여_한_행() {
        ParsedArticle a = find(parser.parseBody(LawTestSupport.resource("labor_standards_act_body_sample.json")).articles(), 26, 0, null);

        String[] lines = a.content().split("\n");
        assertThat(lines[0]).startsWith("제26조(해고의 예고) 사용자는 근로자를 해고");
        assertThat(lines).hasSize(4);
        assertThat(lines[3]).startsWith("3. 근로자가 고의로");
    }

    @Test
    void 가지번호_조문은_branch_로_구분한다() {
        List<ParsedArticle> rows = parser.parseBody(LawTestSupport.resource("labor_standards_act_body_sample.json")).articles();

        assertThat(rows).filteredOn(r -> r.articleNo() == 43 && r.branchNo() == 2).hasSize(5);
        assertThat(find(rows, 43, 2, 1).title()).isEqualTo("체불사업주 명단 공개");
        assertThat(rows).noneMatch(r -> r.articleNo() == 35);
        assertThat(rows).noneMatch(r -> r.articleNo() == 60 && Integer.valueOf(3).equals(r.paragraphNo()));
        assertThat(find(rows, 60, 0, 4).content()).startsWith("④");
    }

    @Test
    void 목은_호_아래에_이어_붙이고_중첩_배열_목내용도_편다() {
        ParsedLaw law = parser.parseBody(LawTestSupport.resource("housing_lease_decree_body_sample.json"));
        ParsedArticle p1 = find(law.articles(), 4, 0, 1);

        assertThat(law.kind()).isEqualTo("대통령령");
        assertThat(p1.content()).contains("3. 임대인ㆍ임차인의 인적사항\n가. 자연인인 경우\n성명, 주소, 주민등록번호(외국인은 외국인등록번호)\n나. 법인이거나");
    }

    @Test
    void 삭제된_호_줄은_내용에서_빼고_센다() {
        ParsedLaw law = parser.parseBody(LawTestSupport.resource("labor_standards_decree_body_sample.json"));
        ParsedArticle p1 = find(law.articles(), 22, 0, 1);
        ParsedArticle p2 = find(law.articles(), 22, 0, 2);

        assertThat(law.deletedItems()).isEqualTo(2); // ① 7. 삭제<2014.12.9>, ② 5. 삭제<2018.6.29>
        assertThat(p1.content()).doesNotContain("삭제").contains("6. 휴가에 관한 서류\n8. ");
        assertThat(p2.content()).doesNotContain("5. 삭제");
        assertThat(p1.content().split("\n")).hasSize(1 + 8);
    }

    @Test
    void 원문자_범위별_항번호() {
        assertThat(LawResponseParser.parseParagraphNo("①")).isEqualTo(1);
        assertThat(LawResponseParser.parseParagraphNo("⑳")).isEqualTo(20);
        assertThat(LawResponseParser.parseParagraphNo("㉑")).isEqualTo(21);
        assertThat(LawResponseParser.parseParagraphNo("㊿")).isEqualTo(50);
        assertThat(LawResponseParser.parseParagraphNo("제3항")).isEqualTo(3);
        assertThat(LawResponseParser.parseParagraphNo("")).isNull();
    }

    @Test
    void 기대한_응답이_아니면_예외() {
        assertThatThrownBy(() -> parser.parseBody("{\"result\":\"실패\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parseList("<html></html>")).isInstanceOf(IllegalArgumentException.class);
    }

    private static ParsedArticle find(List<ParsedArticle> rows, int no, int branch, Integer paragraph) {
        return rows.stream()
                .filter(r -> r.articleNo() == no && r.branchNo() == branch
                        && (paragraph == null ? r.paragraphNo() == null : paragraph.equals(r.paragraphNo())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("없음: " + no + "-" + branch + "-" + paragraph));
    }
}
