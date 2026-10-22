"""检查线程式语料格式与比例。

用法：
    python tools/check_threads.py
"""

from pathlib import Path
from collections import Counter, OrderedDict

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "backend" / "src" / "main" / "resources" / "corpus" / "threads.tsv"
PRAISE = ROOT / "backend" / "src" / "main" / "resources" / "corpus" / "praise.txt"


def main() -> int:
    rows = [
        line.strip()
        for line in CORPUS.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]
    errors = []
    threads = OrderedDict()
    texts = []

    for number, row in enumerate(rows, 1):
        parts = row.split("|")
        if len(parts) != 4:
            errors.append(f"第 {number} 行不是 4 段: {row}")
            continue
        thread_id, scene, intent, text = (part.strip() for part in parts)
        if scene not in {"course", "comic", "chat"}:
            errors.append(f"第 {number} 行场景非法: {scene}")
        if not text:
            errors.append(f"第 {number} 行内容为空")
        threads.setdefault(thread_id, []).append((scene, intent, text))
        texts.append(text)

    duplicates = [text for text, count in Counter(texts).items() if count > 1]
    if duplicates:
        errors.append(f"重复句子 {len(duplicates)} 条: {duplicates[:3]}")

    praise = [line.strip() for line in PRAISE.read_text(encoding="utf-8").splitlines() if line.strip()]
    praise_duplicates = [text for text, count in Counter(praise).items() if count > 1]
    if praise_duplicates:
        errors.append(f"夸奖重复 {len(praise_duplicates)} 条: {praise_duplicates[:3]}")
    both = set(texts) & set(praise)
    if both:
        errors.append(f"夸奖与正文重复 {len(both)} 条: {list(both)[:3]}")

    starts = Counter(lines[0][0] for lines in threads.values())
    story_threads = sum(1 for key in threads if not key.startswith("r"))
    print(f"评论 {len(texts)} 条 / 线程 {story_threads} 条 / 夸奖 {len(praise)} 条")
    print(f"线程起始场景: {dict(starts)}")
    print(f"最长一句: {max((len(text) for text in texts), default=0)} 字")
    expected = {"comic": 114, "course": 100, "chat": 60}
    if dict(starts) != expected:
        print(f"提示: 线程数与预期 {expected} 不一致，当前 {dict(starts)}")

    if errors:
        for error in errors:
            print("错误:", error)
        return 1
    print("校验通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())