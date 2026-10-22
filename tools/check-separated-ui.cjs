const { chromium } = require("playwright");
const path = require("path");

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  await page.goto("http://127.0.0.1:18080", { waitUntil: "networkidle", timeout: 20000 });
  await page.waitForTimeout(800);

  const info = await page.evaluate(() => {
    const logo = document.querySelector(".brand-logo");
    return {
      title: document.title,
      logoLoaded: logo instanceof HTMLImageElement && logo.naturalWidth > 0,
      logoSize: logo instanceof HTMLImageElement ? [logo.naturalWidth, logo.naturalHeight] : null,
      buttons: [...document.querySelectorAll("button")].map((el) => el.innerText.trim()).filter(Boolean),
      intervalInputs: [...document.querySelectorAll(".interval-bar input")].map((el) => el.value),
      panels: [...document.querySelectorAll(".panel-head h2")].map((el) => el.innerText),
      hasOldLogPanel: document.body.innerText.includes("运行日志"),
      hasOldQuota: document.body.innerText.includes("剩余评论额度"),
    };
  });
  console.log(JSON.stringify(info, null, 2));
  await page.screenshot({ path: path.resolve(__dirname, "ui-separated.png"), fullPage: true });
  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
