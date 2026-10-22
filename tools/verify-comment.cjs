const { chromium } = require("playwright");

(async () => {
  const browser = await chromium.connectOverCDP("http://127.0.0.1:9222");
  const pages = browser.contexts()[0].pages();
  for (const page of pages) {
    if (!page.url().includes("live.douyin.com")) continue;
    const items = await page.evaluate(() =>
      [...document.querySelectorAll(".webcast-chatroom___item")]
        .slice(-30)
        .map((el) => (el.innerText || "").replace(/\s+/g, " ").trim())
    );
    console.log(page.url());
    console.log(JSON.stringify(items, null, 2));
  }
  process.exit(0);
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
