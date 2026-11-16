package com.team.docai.domain.analysis.client;

import java.util.Objects;

/**
 * 사용자 메시지에 들어가는 내용 조각. 글자와 이미지를 섞어 넣을 수 있다.
 */
public sealed interface GptContentPart permits GptContentPart.Text, GptContentPart.Image {

    static Text text(String text) {
        return new Text(text);
    }

    static Image image(byte[] data, String mimeType) {
        return new Image(data, mimeType, ImageDetail.HIGH);
    }

    record Text(String text) implements GptContentPart {
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * 이미지 바이트. 요청 때 base64 data URL 로 바뀐다. toString 에 바이트를 남기지 않는다.
     */
    record Image(byte[] data, String mimeType, ImageDetail detail) implements GptContentPart {
        public Image {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(mimeType, "mimeType");
            if (!mimeType.startsWith("image/")) {
                throw new IllegalArgumentException("이미지 MIME 형식이 아닙니다: " + mimeType);
            }
            if (detail == null) {
                detail = ImageDetail.HIGH;
            }
        }

        @Override
        public String toString() {
            return "Image[mimeType=" + mimeType + ", bytes=" + data.length + ", detail=" + detail + "]";
        }
    }

    enum ImageDetail {
        LOW, HIGH, AUTO;

        String apiValue() {
            return name().toLowerCase();
        }
    }
}
