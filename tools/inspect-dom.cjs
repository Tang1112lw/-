const { chromium } = require("playwright");
const path = require("path");

const ROOM_URL =
  "https://www.douyin.com/jingxuan/search/%E7%9B%B4%E6%92%AD?aid=7855e78b-0ecb-4e61-a667-e16b3c720b2e&from_search=true&live_web_rid=537108818552&type=general";

async function main() {
  const root = path.resolve(__dirname, "..");
  const context = await chromium.launchPersistentContext(path.join(root, ".inspect-profile"), {
    channel: "msedge",
    headless: false,
    viewport: { width: 1440, height: 900 },
    args: ["--disable-blink-features=AutomationControlled", "--no-first-run"],
  });
  const page = context.pages()[0] || (await context.newPage());
  await page.goto(ROOM_URL, { waitUntil: "domcontentloaded", timeout: 60000 });
  await page.waitForTimeout(12000);

  const result = await page.evaluate(() => {
    const describe = (el) => ({
      tag: el.tagName,
      cls: typeof el.className === "string" ? el.className.slice(0, 200) : "",
      id: el.id,
      role: el.getAttribute("role"),
      e2e: el.getAttribute("data-e2e"),
      text: (el.innerText || "").replace(/\s+/g, " ").slice(0, 160),
      children: el.children.length,
    });

    const chain = (el) => {
      const out = [];
      let cur = el;
      for (let i = 0; i < 8 && cur; i++) {
        out.push(describe(cur));
        cur = cur.parentElement;
      }
      return out;
    };

    const all = [...document.querySelectorAll("*")];
    const hits = [];
    for (const el of all) {
      const own = [...el.childNodes]
        .filter((n) => n.nodeType === Node.TEXT_NODE)
        .map((n) => n.textContent.trim())
        .join(" ");
      if (own.includes("为主播点赞了") || own.includes("加入了直播间")) {
        hits.push({ ownText: own, chain: chain(el) });
      }
      if (hits.length >= 4) break;
    }

    const editable = all
      .filter(
        (el) =>
          el.isContentEditable ||
          el.tagName === "TEXTAREA" ||
          (el.tagName === "INPUT" && ["text", "search"].includes(el.type))
      )
      .slice(0, 20)
      .map((el) => ({ ...describe(el), placeholder: el.getAttribute("placeholder") }));

    const buttons = all
      .filter((el) => el.tagName === "BUTTON" || el.getAttribute("role") === "button")
      .slice(0, 80)
      .map((el) => ({
        ...describe(el),
        aria: el.getAttribute("aria-label"),
        title: el.getAttribute("title"),
      }));

    const chatLike = all
      .filter((el) => {
        const c = typeof el.className === "string" ? el.className : "";
        return /chat|comment|danmaku|message|webcast/i.test(c);
      })
      .slice(0, 80)
      .map(describe);

    return {
      total: all.length,
      hits,
      editable,
      buttons,
      chatLike,
      shadowHosts: all.filter((el) => el.shadowRoot).slice(0, 20).map(describe),
    };
  });

  console.log(JSON.stringify(result, null, 2));
  await context.close();
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
