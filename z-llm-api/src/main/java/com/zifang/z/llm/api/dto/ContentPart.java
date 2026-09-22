package com.zifang.z.llm.api.dto;

/**
 * 多模态内容片段.
 *
 * <p>type: "text" / "image_url" / "audio_url" 等.
 * <p>text: 文本片段.
 * <p>imageUrl: 图片 URL 或 data URI.
 */
public class ContentPart {

    private String type;
    private String text;
    private ImageUrl imageUrl;

    public ContentPart() {
    }

    public ContentPart(String type, String text) {
        this.type = type;
        this.text = text;
    }

    public static ContentPart text(String text) {
        return new ContentPart("text", text);
    }

    public static ContentPart imageUrl(String url) {
        ContentPart p = new ContentPart();
        p.type = "image_url";
        p.imageUrl = new ImageUrl();
        p.imageUrl.setUrl(url);
        return p;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public ImageUrl getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(ImageUrl imageUrl) {
        this.imageUrl = imageUrl;
    }

    public static class ImageUrl {
        private String url;
        private String detail;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }
    }
}