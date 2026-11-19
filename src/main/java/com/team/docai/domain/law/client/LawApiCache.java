package com.team.docai.domain.law.client;

import com.team.docai.global.config.LawProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 법제처 응답 원본 캐시. 목록은 list/{검색어}_p{쪽}.json, 본문은 body/{MST}.json,
 * 일상용어-법령용어 연계는 daily-term/{일상용어}.json.
 * MST(법령일련번호)는 개정마다 바뀌므로 본문 캐시는 개정본 단위로 고정된다.
 * 저장 전에 인증값을 가린 본문만 받는다 (LawApiClient 가 가림).
 */
@Component
public class LawApiCache {

    private static final Pattern UNSAFE = Pattern.compile("[^\\p{L}\\p{N}_-]");

    private final Path root;

    public LawApiCache(Path root) {
        this.root = root;
    }

    /** 캐시 폴더: docai.law.cache-dir, 없으면 {STORAGE_ROOT 또는 ./storage}/law-cache (storage/ 는 .gitignore 대상). */
    @Autowired
    public LawApiCache(LawProperties properties, Environment environment) {
        this(resolveRoot(properties.cacheDir(), environment.getProperty("STORAGE_ROOT")));
    }

    static Path resolveRoot(String cacheDir, String storageRoot) {
        if (cacheDir != null && !cacheDir.isBlank()) {
            return Path.of(cacheDir);
        }
        String root = storageRoot == null || storageRoot.isBlank() ? "./storage" : storageRoot;
        return Path.of(root, "law-cache");
    }

    public Path root() {
        return root;
    }

    public Optional<String> readList(String query, int page) {
        return read(listPath(query, page));
    }

    public void writeList(String query, int page, String body) {
        write(listPath(query, page), body);
    }

    public Optional<String> readBody(String mst) {
        return read(bodyPath(mst));
    }

    public void writeBody(String mst, String body) {
        write(bodyPath(mst), body);
    }

    /** 일상용어-법령용어 연계 응답. 키는 일상용어. */
    public Optional<String> readDailyTerm(String dailyTerm) {
        return read(dailyTermPath(dailyTerm));
    }

    public void writeDailyTerm(String dailyTerm, String body) {
        write(dailyTermPath(dailyTerm), body);
    }

    Path dailyTermPath(String dailyTerm) {
        return root.resolve("daily-term").resolve(safe(dailyTerm) + ".json");
    }

    Path listPath(String query, int page) {
        return root.resolve("list").resolve(safe(query) + "_p" + page + ".json");
    }

    Path bodyPath(String mst) {
        return root.resolve("body").resolve(safe(mst) + ".json");
    }

    private static String safe(String name) {
        String s = UNSAFE.matcher(name).replaceAll("_");
        if (s.isEmpty()) {
            throw new IllegalArgumentException("캐시 이름이 비어 있습니다.");
        }
        return s;
    }

    private Optional<String> read(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("법령 캐시를 읽지 못했습니다: " + path, e);
        }
    }

    private void write(Path path, String body) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("법령 캐시를 쓰지 못했습니다: " + path, e);
        }
    }
}
