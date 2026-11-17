package com.team.docai.domain.law.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.law.client.FakeLawServer;
import com.team.docai.domain.law.client.LawApiCache;
import com.team.docai.domain.law.client.LawApiClient;
import com.team.docai.domain.law.client.LawListItem;
import com.team.docai.domain.law.client.LawResponseParser;
import com.team.docai.domain.law.client.LawTestSupport;
import com.team.docai.global.config.LawProperties;
import com.team.docai.domain.law.dto.LawLoadReport;
import com.team.docai.domain.law.dto.LawLoadReport.Status;
import com.team.docai.domain.law.dto.RuleSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 적재기의 캐시 사용·바뀐 것만 받기·실패 처리. API 는 가짜 서버, DB 저장(LawWriter)은 모의 객체. */
class LawLoaderTest {

    static final List<LawCatalog.Target> TARGETS = List.of(
            new LawCatalog.Target("근로기준법", "근로기준법", null),
            new LawCatalog.Target("근로기준법 시행령", "근로기준법", "근로기준법"));
    static final String LIST = LawTestSupport.resource("labor_standards_act_list.json");
    static final String BODY = LawTestSupport.resource("labor_standards_act_body_sample.json");

    @TempDir
    Path tmp;
    FakeLawServer server;
    LawWriter writer;
    LawApiCache cache;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeLawServer();
        writer = mock(LawWriter.class);
        cache = new LawApiCache(tmp.resolve("law-cache"));
        when(writer.saveVersion(any(), any(), any())).thenReturn(new LawWriter.SaveResult(1L, 15, 0, 0, 15));
        when(writer.upsertRule(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    LawLoader loader(int maxConsecutiveFailures, boolean force) {
        LawProperties props = LawTestSupport.properties(server.baseUrl(), Duration.ofMillis(10), Duration.ofMillis(20),
                maxConsecutiveFailures, force);
        LawApiClient client = LawTestSupport.client(props, LawTestSupport.FAKE_OC);
        return new LawLoader(client, cache, new LawResponseParser(new ObjectMapper()), writer, props);
    }

    LawLoadReport run(LawLoader loader, List<LawCatalog.Target> targets, boolean force) {
        return loader.load(targets, Map.of("근기법", "근로기준법"), List.<RuleSeed>of(), force);
    }

    LawLoadReport runOffline(LawLoader loader, List<LawCatalog.Target> targets, boolean force) {
        return loader.load(targets, Map.of(), List.of(), List.<RuleSeed>of(), force, true);
    }

    @Test
    void 캐시가_없으면_목록은_검색어마다_1번_본문은_법령마다_1번_부르고_캐시에_저장한다() throws Exception {
        server.handler(q -> new FakeLawServer.Reply(200, q.startsWith("lawSearch") ? LIST : BODY));

        LawLoadReport report = run(loader(3, false), List.of(TARGETS.get(0)), false);

        assertThat(server.received()).extracting(FakeLawServer.Received::path).containsExactly("lawSearch.do", "lawService.do");
        assertThat(report.apiCalls()).isEqualTo(2);
        assertThat(report.laws().get(0).status()).isEqualTo(Status.LOADED);
        assertThat(Files.readString(tmp.resolve("law-cache/body/285279.json"))).contains("근로기준법");
        assertThat(Files.exists(tmp.resolve("law-cache/list/근로기준법_p1.json"))).isTrue();
        verify(writer).upsertAlias("근기법", "근로기준법", com.team.docai.domain.law.entity.AliasSource.MANUAL);
    }

    @Test
    void 목록은_캐시가_있어도_매번_API_로_받고_본문은_캐시를_쓴다() {
        cache.writeList("근로기준법", 1, LIST);
        cache.writeBody("285279", BODY);
        server.handler(q -> q.startsWith("lawSearch") ? new FakeLawServer.Reply(200, LIST) : new FakeLawServer.Reply(500, "본문은 부르면 안 됨"));

        LawLoadReport report = run(loader(3, false), List.of(TARGETS.get(0)), false);

        assertThat(server.received()).extracting(FakeLawServer.Received::path).containsExactly("lawSearch.do");
        assertThat(report.apiCalls()).isEqualTo(1);
        assertThat(report.cacheHits()).isEqualTo(1);
        assertThat(report.laws().get(0).status()).isEqualTo(Status.LOADED);
    }

    @Test
    void 캐시된_목록이_옛_개정본이어도_새_목록의_MST_로_본문을_받는다() {
        cache.writeList("근로기준법", 1, LIST);           // 캐시: 근로기준법 MST 285279
        cache.writeBody("285279", BODY);
        String newList = LIST.replace("\"285279\"", "\"999999\"").replace("MST=285279", "MST=999999");
        server.handler(q -> q.startsWith("lawSearch") ? new FakeLawServer.Reply(200, newList)
                : q.contains("MST=999999") ? new FakeLawServer.Reply(200, BODY) : new FakeLawServer.Reply(500, "x"));

        LawLoadReport report = run(loader(3, false), List.of(TARGETS.get(0)), false);

        assertThat(server.received()).extracting(r -> r.path() + (r.query().contains("MST=999999") ? ":999999" : ""))
                .containsExactly("lawSearch.do", "lawService.do:999999");
        assertThat(report.laws().get(0).mst()).isEqualTo("999999");
        assertThat(report.laws().get(0).status()).isEqualTo(Status.LOADED);
        assertThat(cache.readBody("999999")).isPresent();
        assertThat(cache.readList("근로기준법", 1)).get().asString().contains("999999");
    }

    @Test
    void offline_이면_목록_본문_모두_캐시만_쓰고_API_를_부르지_않는다() {
        cache.writeList("근로기준법", 1, LIST);
        cache.writeBody("285279", BODY);
        server.handler(q -> new FakeLawServer.Reply(500, "부르면 안 됨"));

        LawLoadReport report = runOffline(loader(3, false), List.of(TARGETS.get(0)), false);

        assertThat(server.received()).isEmpty();
        assertThat(report.apiCalls()).isZero();
        assertThat(report.cacheHits()).isEqualTo(2);
        assertThat(report.laws().get(0).status()).isEqualTo(Status.LOADED);
    }

    @Test
    void offline_강제_갱신은_DB_에_있어도_캐시로_다시_저장한다() {
        cache.writeList("근로기준법", 1, LIST);
        cache.writeBody("285279", BODY);
        server.handler(q -> new FakeLawServer.Reply(500, "부르면 안 됨"));
        when(writer.isLoaded(any(LawListItem.class))).thenReturn(true);

        LawLoadReport report = runOffline(loader(3, true), List.of(TARGETS.get(0)), true);

        assertThat(server.received()).isEmpty();
        assertThat(report.laws().get(0).status()).isEqualTo(Status.LOADED);
        verify(writer).saveVersion(any(), any(), any());
    }

    @Test
    void offline_인데_캐시가_없으면_그_법령은_실패() {
        server.handler(q -> new FakeLawServer.Reply(200, LIST));

        assertThatThrownBy(() -> runOffline(loader(3, false), List.of(TARGETS.get(0)), false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(server.received()).isEmpty();
    }

    @Test
    void 실제_적재_경로는_호출_간격이_1초_미만이면_거절한다() {
        server.handler(q -> new FakeLawServer.Reply(200, LIST));

        assertThatThrownBy(() -> loader(3, false).load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("min-interval");
        assertThat(server.received()).isEmpty();
    }

    @Test
    void 강제_갱신이면_캐시가_있어도_다시_부른다() {
        cache.writeList("근로기준법", 1, LIST);
        cache.writeBody("285279", BODY);
        server.handler(q -> new FakeLawServer.Reply(200, q.startsWith("lawSearch") ? LIST : BODY));

        LawLoadReport report = run(loader(3, true), List.of(TARGETS.get(0)), true);

        assertThat(report.apiCalls()).isEqualTo(2);
        assertThat(report.cacheHits()).isZero();
    }

    @Test
    void DB_에_같은_법령일련번호가_있으면_본문을_부르지도_저장하지도_않는다() {
        server.handler(q -> new FakeLawServer.Reply(200, LIST));
        when(writer.isLoaded(any(LawListItem.class))).thenReturn(true);

        LawLoadReport report = run(loader(3, false), List.of(TARGETS.get(0)), false);

        assertThat(server.received()).extracting(FakeLawServer.Received::path).containsExactly("lawSearch.do");
        assertThat(report.laws().get(0).status()).isEqualTo(Status.UNCHANGED);
        verify(writer, never()).saveVersion(any(), any(), any());
    }

    @Test
    void 같은_검색어의_법령은_목록을_한_번만_부른다() {
        server.handler(q -> new FakeLawServer.Reply(200, q.startsWith("lawSearch") ? LIST : BODY));
        when(writer.isLoaded(any(LawListItem.class))).thenReturn(true);

        run(loader(3, false), TARGETS, false);

        assertThat(server.received()).extracting(FakeLawServer.Received::path).containsExactly("lawSearch.do");
        verify(writer).linkParent("근로기준법 시행령", "근로기준법");
    }

    @Test
    void 한_법령이_실패하면_그_법령만_건너뛰고_나머지를_계속한다() {
        // 시행령(270551) 본문만 계속 실패
        server.handler(q -> q.startsWith("lawSearch") ? new FakeLawServer.Reply(200, LIST)
                : q.contains("MST=270551") ? new FakeLawServer.Reply(500, "x") : new FakeLawServer.Reply(200, BODY));

        LawLoadReport report = run(loader(3, false), TARGETS, false);

        assertThat(report.laws()).extracting(LawLoadReport.LawResult::status).containsExactly(Status.LOADED, Status.FAILED);
        // 목록 1 + 근로기준법 본문 1 + 시행령 본문 2 (재시도 1회)
        assertThat(server.received()).hasSize(4);
        verify(writer).saveVersion(any(), any(), any());
    }

    @Test
    void 연속_실패가_한도에_닿으면_중단한다() {
        server.handler(q -> new FakeLawServer.Reply(500, "down"));

        assertThatThrownBy(() -> run(loader(2, false), List.of(
                new LawCatalog.Target("가법", "가법", null),
                new LawCatalog.Target("나법", "나법", null),
                new LawCatalog.Target("다법", "다법", null)), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("연속 2개 실패");
        // 2개 법령 x (1회 + 재시도 1회). 세 번째는 부르지 않음
        assertThat(server.received()).hasSize(4);
        verify(writer, never()).upsertAlias(anyString(), anyString(), any());
    }

    @Test
    void 모두_실패하면_한도_전이라도_예외() {
        server.handler(q -> new FakeLawServer.Reply(500, "down"));

        assertThatThrownBy(() -> run(loader(5, false), List.of(TARGETS.get(0)), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("저장된 법령이 없습니다");
    }

    @Test
    void document_type_행이_없는_종류는_그_시드만_건너뛰고_나머지는_넣는다() {
        cache.writeList("근로기준법", 1, LIST);
        server.handler(q -> new FakeLawServer.Reply(500, "부르면 안 됨"));
        when(writer.isLoaded(any(LawListItem.class))).thenReturn(true);
        when(writer.insertDocumentTypeLaw(eq("EMPLOYMENT"), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(LawWriter.SeedResult.INSERTED, LawWriter.SeedResult.EXISTS);
        when(writer.insertDocumentTypeLaw(eq("NO_TYPE"), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("fk_document_type_law_document_type_code"));

        LawLoadReport report = loader(3, false).load(List.of(TARGETS.get(0)), Map.of(), List.of(
                new LawCatalog.DocumentTypeLawSeed("NO_TYPE", "근로기준법", true),
                new LawCatalog.DocumentTypeLawSeed("NO_TYPE", "근로기준법 시행령", false),
                new LawCatalog.DocumentTypeLawSeed("EMPLOYMENT", "근로기준법", true),
                new LawCatalog.DocumentTypeLawSeed("EMPLOYMENT", "근로기준법 시행령", false)), List.of(), false, true);

        assertThat(report.documentTypeLaws()).containsExactly(
                "NO_TYPE -> 근로기준법: SKIPPED_NO_DOCUMENT_TYPE",
                "NO_TYPE -> 근로기준법 시행령: SKIPPED_NO_DOCUMENT_TYPE",
                "EMPLOYMENT -> 근로기준법: INSERTED",
                "EMPLOYMENT -> 근로기준법 시행령: EXISTS");
        // 없는 종류는 첫 번째 실패 뒤 더 시도하지 않는다
        verify(writer, org.mockito.Mockito.times(1)).insertDocumentTypeLaw(eq("NO_TYPE"), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void document_type_이_아닌_무결성_오류는_건너뛰지_않고_실패() {
        cache.writeList("근로기준법", 1, LIST);
        when(writer.isLoaded(any(LawListItem.class))).thenReturn(true);
        when(writer.insertDocumentTypeLaw(anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("fk_document_type_law_law_id"));

        assertThatThrownBy(() -> loader(3, false).load(List.of(TARGETS.get(0)), Map.of(), List.of(
                new LawCatalog.DocumentTypeLawSeed("EMPLOYMENT", "근로기준법", true)), List.of(), false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("문서별 관련 법령 저장 실패");
    }

    @Test
    void 목록에_같은_이름이_없으면_NOT_FOUND() {
        cache.writeList("근로기준법", 1, LIST);
        server.handler(q -> new FakeLawServer.Reply(500, "부르면 안 됨"));
        when(writer.isLoaded(any(LawListItem.class))).thenReturn(true);

        LawLoadReport report = runOffline(loader(3, false), List.of(TARGETS.get(0),
                new LawCatalog.Target("근로기준법 특례법", "근로기준법", null)), false);

        assertThat(report.laws().get(1).status()).isEqualTo(Status.NOT_FOUND);
        assertThat(server.received()).isEmpty();
        verify(writer, never()).saveVersion(any(), any(), eq(null));
    }
}
