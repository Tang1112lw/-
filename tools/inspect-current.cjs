const { chromium } = require("playwright");
const path = require("path");

(async () => {
  const browser = await chromium.connectOverCDP("http://127.0.0.1:9222");
  const pages = browser.contexts()[0].pages();
  console.log("pages", pages.length);
  let index = 0;
  for (const page of pages) {
    const info = { url: page.url() };
    try {
      info.title = await page.title();
      info.body = await page.evaluate(() => (document.body.innerText || "").slice(-800));
      info.needLogin = await page.evaluate(() => document.body.innerText.includes("需先登录"));
      info.editors = await page.locator("div[contenteditable='true']").count();
      info.loginTexts = await page.evaluate(() =>
        [...document.querySelectorAll("button,div,span")]
          .map((el) => (el.innerText || "").trim())
          .filter((t) => t === "登录" || t.includes("扫码"))
          .slice(0, 20)
      );
    } catch (error) {
      info.err = error.message;
    }
    console.log(JSON.stringify(info, null, 2));
    try {
      await page.screenshot({
        path: path.resolve(__dirname, `current-${index++}.png`),
        timeout: 8000,
      });
    } catch (error) {
      console.log("screenshot failed:", error.message);
    }
  }
  process.exit(0);
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
