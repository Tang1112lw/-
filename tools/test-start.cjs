const { chromium } = require("playwright");
const path = require("path");

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage();
  await page.goto("http://127.0.0.1:18080", { waitUntil: "domcontentloaded" });
  await page.waitForTimeout(1200);

  const snapshot = () =>
    page.evaluate(() => ({
      badges: [...document.querySelectorAll(".badge")].map((el) => el.innerText),
      startText: document.querySelector("[data-act=start]")?.innerText,
      startDisabled: document.querySelector("[data-act=start]")?.disabled,
      logs: document.querySelector(".logs")?.innerText?.slice(-300),
    }));

  console.log("before", JSON.stringify(await snapshot()));
  try {
    await page.locator("[data-act=start]").click({ timeout: 4000 });
    console.log("clicked");
  } catch (error) {
    console.log("click failed:", error.message);
  }
  await page.waitForTimeout(3000);
  console.log("after", JSON.stringify(await snapshot()));
  await page.screenshot({
    path: path.resolve(__dirname, "ui-after-start.png"),
    fullPage: true,
  });
  await browser.close();
  process.exit(0);
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
