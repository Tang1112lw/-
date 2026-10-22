const { chromium } = require("playwright");
const path = require("path");

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  await page.goto("http://127.0.0.1:18080", { waitUntil: "networkidle", timeout: 20000 });
  await page.waitForTimeout(900);

  const info = await page.evaluate(() => {
    const logo = document.querySelector(".brand-logo");
    return {
      title: document.title,
      logoLoaded: logo instanceof HTMLImageElement && logo.naturalWidth > 0,
      status: [...document.querySelectorAll(".status-item")].map((el) =>
        el.innerText.replace(/\s+/g, " ").trim()
      ),
      plan: {
        mode: document.querySelector(".panel:nth-child(2) .panel-head span")?.innerText,
        time: document.querySelector("#root .plan-grid > div:nth-child(1) b")?.innerText,
        countdown: document.querySelector("#root .plan-grid > div:nth-child(2) b")?.innerText,
        interval: document.querySelector("#root .plan-grid > div:nth-child(3) b")?.innerText,
        action: document.querySelector("#root .plan-grid > div:nth-child(4) b")?.innerText,
        content: document.querySelector(".plan-content")?.innerText,
      },
      hasOldLogPanel: document.body.innerText.includes("运行日志"),
      hasOldQuota: document.body.innerText.includes("剩余评论额度"),
    };
  });
  console.log(JSON.stringify(info, null, 2));
  await page.screenshot({ path: path.resolve(__dirname, "ui-final.png"), fullPage: true });
  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
