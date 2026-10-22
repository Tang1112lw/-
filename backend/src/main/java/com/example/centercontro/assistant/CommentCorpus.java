package com.example.centercontro.assistant;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 本地线程式语料库。没有 AI，也不读取直播间评论。
 *
 * <p><b>每一行就是一条独立评论，单独发送。</b> 同一个 threadId 的多行只表示先后关系：
 * 首条发完，等一个轮间隔再发中条，再等一个轮间隔发尾条；一条线程走完才开下一条。
 * 中间不会插入我们自己的其它线程，也不会把多行拼成一条消息。
 *
 * <p>每条评论按场景加权抽取：夸主播 0.35 / AI 漫剧 0.35 / AI 课程 0.175 / 萌新唠嗑 0.125。
 * 抽到「夸主播」就直接发一句夸奖，不开新线程、不动线程进度；抽到对话场景时，当前线程还没走完
 * 就继续走它，走完再按抽到的场景开新线程；线程全部用完后自动洗牌重来。
 */
@Service
public class CommentCorpus {

    private static final Logger log = LoggerFactory.getLogger(CommentCorpus.class);

    private static final String FILE = "corpus/threads.tsv";
    private static final String PRAISE_FILE = "corpus/praise.txt";
    private static final double WEIGHT_COMIC = 0.35;
    private static final double WEIGHT_COURSE = 0.175;
    private static final double WEIGHT_CHAT = 0.125;
    /** 夸主播单独作为一个加权场景；抽到时不打断线程，只插一句夸奖。 */
    private static final double WEIGHT_PRAISE = 0.35;

    private final List<StoryThread> pool = new ArrayList<>();
    private final List<StoryThread> unused = new ArrayList<>();
    private final List<String> praiseMaster = new ArrayList<>();
    private final List<String> praisePool = new ArrayList<>();
    private final Random random = new Random();

    /** 当前正在播放的线程；为 null 时下一条开新线程。 */
    private StoryThread current;

    private record StoryLine(String scene, String intent, String text) {
    }

    private static final class StoryThread {
        final String id;
        final String startScene;
        final List<StoryLine> lines;
        int cursor;

        StoryThread(String id, String startScene, List<StoryLine> lines) {
            this.id = id;
            this.startScene = startScene;
            this.lines = lines;
        }
    }

    @PostConstruct
    public void load() {
        Map<String, List<StoryLine>> grouped = new LinkedHashMap<>();
        Set<String> seenTexts = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(FILE).getInputStream(), StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.replace("\uFEFF", "").trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|", 4);
                if (parts.length != 4) {
                    throw new IllegalStateException("语料格式错误（需要 4 段）: " + line);
                }
                String threadId = parts[0].trim();
                String scene = parts[1].trim();
                String intent = parts[2].trim();
                String text = parts[3].trim();
                if (threadId.isEmpty() || scene.isEmpty() || text.isEmpty()) {
                    throw new IllegalStateException("语料有空字段: " + line);
                }
                if (!seenTexts.add(text)) {
                    throw new IllegalStateException("语料有重复句子: " + text);
                }
                grouped.computeIfAbsent(threadId, key -> new ArrayList<>())
                        .add(new StoryLine(scene, intent, text));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取语料库失败: " + FILE + " (" + e.getMessage() + ")", e);
        }

        for (Map.Entry<String, List<StoryLine>> entry : grouped.entrySet()) {
            List<StoryLine> lines = entry.getValue();
            String startScene = lines.get(0).scene();
            if (!startScene.equals("course") && !startScene.equals("comic")
                    && !startScene.equals("chat")) {
                throw new IllegalStateException("线程起始场景必须是 course/comic/chat: " + entry.getKey());
            }
            pool.add(new StoryThread(entry.getKey(), startScene, List.copyOf(lines)));
        }
        unused.addAll(pool);
        if (pool.isEmpty()) {
            throw new IllegalStateException("语料库为空，无法启动");
        }
        loadPraise(seenTexts);
        log.info("线程语料库已加载：{} 条评论 / {} 条线程 / {} 条夸奖",
                totalSize(), pool.size(), praiseMaster.size());
    }

    private void loadPraise(Set<String> seenTexts) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(PRAISE_FILE).getInputStream(), StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String text = raw.replace("\uFEFF", "").trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (!seenTexts.add(text)) {
                    throw new IllegalStateException("夸奖语料有重复句子: " + text);
                }
                praiseMaster.add(text);
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取夸奖语料失败: " + PRAISE_FILE + " (" + e.getMessage() + ")", e);
        }
        if (praiseMaster.isEmpty()) {
            throw new IllegalStateException("夸奖语料为空: " + PRAISE_FILE);
        }
        praisePool.addAll(praiseMaster);
    }

    /**
     * 取下一个要发的评论。每次只返回一条，调用方分开发送。
     *
     * <p>当前线程没走完时，一定先发它的下一行；走完才开新线程。
     */
    public synchronized CommentPlan next() {
        if (pool.isEmpty()) {
            throw new IllegalStateException("语料库未加载");
        }

        // 每一条都按权重抽场景：抽到夸主播就单独插一句，不开线程、不动线程进度；
        // 抽到对话场景时，如果当前线程还没走完就继续走当前线程，走完才按抽到的场景开新线程。
        String scene = weightedScene();
        if (scene.equals("praise")) {
            return new CommentPlan("夸主播", nextPraise());
        }

        if (current == null) {
            current = startNewThread(scene);
            if (current == null) {
                throw new IllegalStateException("没有可用的语料线程");
            }
        }
        StoryLine line = current.lines.get(current.cursor++);
        String sceneLabel = label(current.startScene);
        if (current.cursor >= current.lines.size()) {
            current = null;
        }
        return new CommentPlan(sceneLabel, line.text());
    }

    private String nextPraise() {
        if (praisePool.isEmpty()) {
            praisePool.addAll(praiseMaster);
        }
        return praisePool.remove(random.nextInt(praisePool.size()));
    }

    private StoryThread startNewThread(String scene) {
        if (unused.isEmpty()) {
            recycle();
        }
        if (unused.isEmpty()) {
            return null;
        }
        List<StoryThread> candidates = new ArrayList<>();
        for (StoryThread thread : unused) {
            if (thread.startScene.equals(scene)) {
                candidates.add(thread);
            }
        }
        if (candidates.isEmpty()) {
            candidates.addAll(unused);
        }
        StoryThread thread = candidates.get(random.nextInt(candidates.size()));
        unused.remove(thread);
        thread.cursor = 0;
        return thread;
    }

    private String weightedScene() {
        double roll = random.nextDouble();
        if (roll < WEIGHT_COMIC) {
            return "comic";
        }
        if (roll < WEIGHT_COMIC + WEIGHT_COURSE) {
            return "course";
        }
        if (roll < WEIGHT_COMIC + WEIGHT_COURSE + WEIGHT_CHAT) {
            return "chat";
        }
        return "praise";
    }

    private void recycle() {
        unused.clear();
        for (StoryThread thread : pool) {
            thread.cursor = 0;
            unused.add(thread);
        }
    }

    private String label(String scene) {
        return switch (scene) {
            case "course" -> "AI 课程";
            case "comic" -> "AI 漫剧";
            default -> "萌新唠嗑";
        };
    }

    public int totalSize() {
        int total = praiseMaster.size();
        for (StoryThread thread : pool) {
            total += thread.lines.size();
        }
        return total;
    }

    public int threadCount() {
        return pool.size();
    }

    public Map<String, Integer> sizes() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (StoryThread thread : pool) {
            map.merge(label(thread.startScene), thread.lines.size(), Integer::sum);
        }
        map.put("夸主播", praiseMaster.size());
        return map;
    }
}