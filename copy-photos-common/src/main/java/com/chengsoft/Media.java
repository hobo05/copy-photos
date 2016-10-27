package com.chengsoft;

/**
 * Represents the media types supported
 *
 * @author tcheng
 */
public enum Media {
    IMAGE("image"),
    VIDEO("video"),
    ALL("all");

    private String value;

    Media(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }

    public String getValue() {
        return value;
    }
}
