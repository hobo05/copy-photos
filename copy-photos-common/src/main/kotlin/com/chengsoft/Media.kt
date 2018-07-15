package com.chengsoft

/**
 * Represents the media types supported
 *
 * @author tcheng
 */
enum class Media(val value: String) {
    IMAGE("image"),
    VIDEO("video"),
    ALL("all");

    override fun toString(): String {
        return value
    }
}
