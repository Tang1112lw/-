const { chromium } = require("playwright");
const path = require("path");

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  await page.goto("http://127.0.0.1:18080", { waitUntil: "networkidle" });
  await page.waitForTimeout(800);

  const getState = () =>
    page.evaluate(async () => {
      const sessions = await (await fetch("/api/sessions")).json();
      return {
        roomUrl: sessions[0]?.roomUrl,
        state: sessions[0]?.state,
        commentCount: sessions[0]?.commentCount,
      };
    });

  const initial = await getState();
  console.log("initial", JSON.stringify(initial));

  const input = page.locator(".room-bar input");
  const apply = page.locator(".room-bar button");
  console.log("input value", JSON.stringify(await input.inputValue()));

  await input.fill("https://example.com");
  await apply.click();
  await page.waitForTimeout(700);
  console.log("invalid toast", JSON.stringify(await page.locator(".toast").innerText()));
  console.log("after invalid", JSON.stringify(await getState()));

  await input.fill(initial.roomUrl);
  await apply.click();
  await page.waitForTimeout(1200);
  console.log("valid toast", JSON.stringify(await page.locator(".toast").innerText()));
  console.log("after valid", JSON.stringify(await getState()));

  await page.screenshot({ path: path.resolve(__dirname, "ui-room-input.png"), fullPage: true });
  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
