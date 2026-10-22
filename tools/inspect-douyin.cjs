const { chromium } = require("playwright");
const path = require("path");

const ROOM_URL =
  "https://www.douyin.com/jingxuan/search/%E7%9B%B4%E6%92%AD?aid=7855e78b-0ecb-4e61-a667-e16b3c720b2e&from_search=true&live_web_rid=537108818552&type=general";

async function main() {
  const root = path.resolve(__dirname, "..");
  const userDataDir = path.join(root, ".inspect-profile");
  const context = await chromium.launchPersistentContext(userDataDir, {
    channel: "msedge",
    headless: false,
    viewport: { width: 1440, height: 900 },
    args: ["--disable-blink-features=AutomationControlled", "--no-first-run"],
  });

  const page = context.pages()[0] || (await context.newPage());
  await page.goto(ROOM_URL, { waitUntil: "domcontentloaded", timeout: 60000 });
  await page.waitForTimeout(12000);

  console.log("URL:", page.url());
  console.log("TITLE:", await page.title());

  const info = await page.evaluate(() => {
    const attrs = {};
    document.querySelectorAll("[data-e2e]").forEach((el) => {
      const key = el.getAttribute("data-e2e") || "";
      attrs[key] = (attrs[key] || 0) + 1;
    });

    const iframes = [...document.querySelectorAll("iframe")].map((f) => f.src);
    const bodyText = (document.body.innerText || "").slice(0, 6000);
    const candidates = [...document.querySelectorAll("[class*='chat'], [class*='comment'], [class*='message']")]
      .slice(0, 40)
      .map((el) => ({
        tag: el.tagName,
        cls: (el.className || "").toString().slice(0, 160),
        e2e: el.getAttribute("data-e2e"),
        text: (el.innerText || "").slice(0, 120),
      }));

    return {
      attrs,
      iframes,
      bodyText,
      candidates,
      htmlLength: document.documentElement.outerHTML.length,
    };
  });

  console.log(JSON.stringify(info, null, 2));
  await page.screenshot({ path: path.join(root, "tools", "douyin-live.png") });
  await context.close();
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
