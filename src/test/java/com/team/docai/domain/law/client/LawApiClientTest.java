package com.team.docai.domain.law.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 호출 간격·재시도·동시 호출 금지·인증값 가리기. 가짜 서버(FakeLawServer)만 부른다. */
class LawApiClientTest {

    static final Duration INTERVAL = Duration.ofMillis(300);
    static final Duration RETRY = Duration.ofMillis(700);
    static final String LIST_OK = "{\"LawSearch\":{\"totalCnt\":\"0\",\"law\":[],\"link\":\"/DRF/lawService.do?OC="
            + LawTestSupport.FAKE_OC + "&MST=1\"}}";
    static final String BODY_OK = "{\"법령\":{\"기본정보\":{}}}";

    FakeLawServer server;
    LawApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeLawServer();
        client = LawTestSupport.client(LawTestSupport.properties(server.baseUrl(), INTERVAL, RETRY), LawTestSupport.FAKE_OC);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void 요청_변수와_응답의_인증값을_가린다() {
        server.handler(q -> new FakeLawServer.Reply(200, LIST_OK));

        String body = client.fetchList("근로기준법", 2, 100);

        FakeLawServer.Received r = server.received().get(0);
        assertThat(r.path()).isEqualTo("lawSearch.do");
        assertThat(r.query()).contains("OC=" + LawTestSupport.FAKE_OC, "target=law", "type=JSON", "query=근로기준법",
                "display=100", "page=2");
        assertThat(body).doesNotContain(LawTestSupport.FAKE_OC).contains("OC=***");
        assertThat(client.mask("https://x/lawService.do?OC=abc&MST=1")).isEqualTo("https://x/lawService.do?OC=***&MST=1");
    }

    @Test
    void 연속_호출_사이에_최소_간격을_둔다() {
        server.handler(q -> new FakeLawServer.Reply(200, q.startsWith("lawService") ? BODY_OK : LIST_OK));

        client.fetchList("a", 1, 100);
        client.fetchBody("1");
        client.fetchBody("2");

        List<FakeLawServer.Received> rs = server.received();
        assertThat(rs).hasSize(3);
        for (int i = 1; i < rs.size(); i++) {
            long gapMillis = (rs.get(i).startNanos() - rs.get(i - 1).endNanos()) / 1_000_000;
            assertThat(gapMillis).isGreaterThanOrEqualTo(INTERVAL.toMillis() - 20);
        }
        assertThat(client.callCount()).isEqualTo(3);
    }

    @Test
    void 오류는_간격을_늘려_1회만_재시도하고_성공하면_돌려준다() {
        AtomicInteger n = new AtomicInteger();
        server.handler(q -> n.incrementAndGet() == 1 ? new FakeLawServer.Reply(503, "busy") : new FakeLawServer.Reply(200, BODY_OK));

        assertThat(client.fetchBody("9")).isEqualTo(BODY_OK);

        List<FakeLawServer.Received> rs = server.received();
        assertThat(rs).hasSize(2);
        long gapMillis = (rs.get(1).startNanos() - rs.get(0).endNanos()) / 1_000_000;
        assertThat(gapMillis).isGreaterThanOrEqualTo(RETRY.toMillis() - 20);
    }

    @Test
    void 재시도도_실패하면_2번만_부르고_예외_메시지에_인증값이_없다() {
        server.handler(q -> new FakeLawServer.Reply(500, "error for OC=" + LawTestSupport.FAKE_OC));

        assertThatThrownBy(() -> client.fetchBody("9"))
                .isInstanceOf(LawApiException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(LawTestSupport.FAKE_OC).contains("OC=***"));
        assertThat(server.received()).hasSize(2);
    }

    @Test
    void 예상한_JSON_이_아니면_오류로_보고_재시도한다() {
        server.handler(q -> new FakeLawServer.Reply(200, "{\"result\":\"사용자 정보 검증에 실패하였습니다.\"}"));

        assertThatThrownBy(() -> client.fetchList("a", 1, 100)).isInstanceOf(LawApiException.class);
        assertThat(server.received()).hasSize(2);
    }

    @Test
    void 여러_스레드가_불러도_동시에_하나만_보낸다() {
        server.delayMillis(100);
        server.handler(q -> new FakeLawServer.Reply(200, BODY_OK));

        List<CompletableFuture<String>> calls = List.of(
                CompletableFuture.supplyAsync(() -> client.fetchBody("1")),
                CompletableFuture.supplyAsync(() -> client.fetchBody("2")),
                CompletableFuture.supplyAsync(() -> client.fetchBody("3")));
        calls.forEach(CompletableFuture::join);

        assertThat(server.received()).hasSize(3);
        assertThat(server.maxInFlight()).isEqualTo(1);
    }

    @Test
    void 인증값이_없으면_부르지_않는다() {
        LawApiClient noKey = LawTestSupport.client(LawTestSupport.properties(server.baseUrl(), INTERVAL, RETRY), "");

        assertThatThrownBy(() -> noKey.fetchBody("1")).isInstanceOf(LawApiException.class);
        assertThat(server.received()).isEmpty();
    }

    @Test
    void 일상용어_연계는_dlytrmRlt_로_부르고_연계가_없다는_안내도_정상_응답으로_받는다() {
        server.handler(q -> new FakeLawServer.Reply(200, q.contains("query=%EC%97%86%EC%9D%8C") || q.contains("query=없음")
                ? "{\"Law\": \"일치하는 일상용어가 없습니다.\"}"
                : "{\"dlytrmRltService\":{\"일상용어\":{\"연계용어\":{\"법령용어명\":\"차임\",\"용어간관계링크\":\"/DRF/lawService.do?OC="
                        + LawTestSupport.FAKE_OC + "&target=lstrmRlt\"}}}}"));

        String body = client.fetchDailyTermLinks("월세");
        String none = client.fetchDailyTermLinks("없음");

        FakeLawServer.Received r = server.received().get(0);
        assertThat(r.path()).isEqualTo("lawService.do");
        assertThat(r.query()).contains("target=dlytrmRlt", "type=JSON", "query=월세");
        assertThat(body).doesNotContain(LawTestSupport.FAKE_OC).contains("OC=***", "차임");
        assertThat(none).contains("일치하는 일상용어가 없습니다");
        assertThat(server.received()).hasSize(2);
    }
}
