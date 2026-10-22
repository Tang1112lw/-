const { chromium } = require("playwright");
const path = require("path");

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  await page.goto("http://127.0.0.1:18080", { waitUntil: "networkidle" });
  await page.waitForTimeout(800);

  const result = await page.evaluate(() => {
    const body = document.body.innerText;
    return {
      hints: [
        "默认自动互动",
        "输入后点应用或回车",
        "修改后下一轮生效",
        "AI 每轮只分析一次",
      ].map((text) => ({ text, present: body.includes(text) })),
      roomInput: document.querySelector(".room-bar input")?.value,
      buttons: [...document.querySelectorAll("button")].map((el) => el.innerText.trim()).filter(Boolean),
    };
  });
  console.log(JSON.stringify(result, null, 2));
  await page.screenshot({ path: path.resolve(__dirname, "ui-no-hints.png"), fullPage: true });
  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
