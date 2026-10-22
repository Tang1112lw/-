const { chromium } = require("playwright");

(async () => {
  const browser = await chromium.connectOverCDP("http://127.0.0.1:9222");
  const context = browser.contexts()[0];
  const pages = context.pages();
  console.log("pages", pages.length);
  for (const page of pages) {
    const info = { url: page.url(), title: await page.title() };
    try {
      info.needLogin = await page.evaluate(() =>
        document.body.innerText.includes("需先登录")
      );
      info.editors = await page.locator("div[contenteditable='true']").count();
      info.placeholder = await page
        .locator("div[contenteditable='true']")
        .first()
        .getAttribute("data-placeholder");
      info.tail = (await page.evaluate(() => document.body.innerText)).slice(-500);
    } catch (error) {
      info.err = error.message;
    }
    console.log(JSON.stringify(info));
  }
  process.exit(0);
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
