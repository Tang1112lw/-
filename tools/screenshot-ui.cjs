const { chromium } = require("playwright");
const path = require("path");

(async () => {
  const browser = await chromium.connectOverCDP("http://127.0.0.1:9222");
  const context = browser.contexts()[0];
  const page = await context.newPage();
  await page.goto("http://127.0.0.1:18080", { waitUntil: "networkidle", timeout: 30000 });
  await page.waitForTimeout(2500);
  await page.screenshot({
    path: path.resolve(__dirname, "ui.png"),
    fullPage: true,
  });
  console.log("ui screenshot saved");
  await page.close();
  process.exit(0);
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
