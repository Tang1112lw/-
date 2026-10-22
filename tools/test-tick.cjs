const { chromium } = require("playwright");
const path = require("path");

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage();
  await page.goto("http://127.0.0.1:18080", { waitUntil: "domcontentloaded" });
  await page.waitForTimeout(1300);

  const snapshot = () =>
    page.evaluate(() => ({
      badges: [...document.querySelectorAll(".badge")].map((el) => el.innerText),
      timeline: document.querySelector(".timeline")?.innerText,
      tickDisabled: document.querySelector("[data-act=tick]")?.disabled,
      logs: document.querySelector(".logs")?.innerText?.slice(-500),
    }));

  console.log("before", JSON.stringify(await snapshot()));
  await page.locator("[data-act=tick]").click({ timeout: 5000 });
  console.log("clicked tick");
  await page.waitForTimeout(5000);
  console.log("after", JSON.stringify(await snapshot()));
  await page.screenshot({ path: path.resolve(__dirname, "ui-tick.png"), fullPage: true });
  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
