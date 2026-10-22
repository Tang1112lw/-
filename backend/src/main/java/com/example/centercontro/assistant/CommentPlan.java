package com.example.centercontro.assistant;

/**
 * 一条待发送的评论：场景 + 文案。
 *
 * <p>内容全部来自本地语料库，不经过任何模型，也不依赖直播间的评论。
 */
public record CommentPlan(String scene, String text) {
}