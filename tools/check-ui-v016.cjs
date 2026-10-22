const { chromium } = require("playwright");
const path = require("path");

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage();
  await page.goto("http://127.0.0.1:18080", { waitUntil: "domcontentloaded" });
  await page.waitForTimeout(1200);

  const info = await page.evaluate(() => ({
    hasQuota: document.body.innerText.includes("剩余评论额度"),
    statLabels: [...document.querySelectorAll(".stat .k")].map((el) => el.innerText),
    sentValue: document.querySelector(".stat.sent .v")?.innerText,
    startDisabled: document.querySelector("[data-act=start]")?.disabled,
    stopDisabled: document.querySelector("[data-act=stop]")?.disabled,
    tickDisabled: document.querySelector("[data-act=tick]")?.disabled,
    recentRows: [...document.querySelectorAll(".comment-row")]
      .slice(0, 5)
      .map((el) => el.innerText.replace(/\s+/g, " ").trim()),
  }));
  console.log(JSON.stringify(info, null, 2));

  await page.locator("[data-act=start]").click();
  await page.waitForTimeout(700);
  console.log("toast:", await page.locator("#toast").innerText());
  await page.screenshot({ path: path.resolve(__dirname, "ui-v016.png"), fullPage: true });
  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
