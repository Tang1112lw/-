const { chromium } = require("playwright");

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  await page.goto("http://127.0.0.1:18080", { waitUntil: "networkidle" });
  await page.waitForTimeout(800);

  const input = page.locator(".room-bar input");
  const apply = page.locator(".room-bar button");
  const original = await input.inputValue();
  const testUrl = "https://live.douyin.com/537108818552?from=final-test";

  await input.fill(testUrl);
  await apply.click();
  await page.waitForTimeout(900);
  console.log("test toast", JSON.stringify(await page.locator(".toast").innerText()));
  console.log("test input", JSON.stringify(await input.inputValue()));
  console.log(
    "test api",
    JSON.stringify(
      (await (await fetch("http://127.0.0.1:18080/api/sessions")).json())[0].roomUrl,
    ),
  );

  await input.fill(original);
  await apply.click();
  await page.waitForTimeout(900);
  console.log("restore toast", JSON.stringify(await page.locator(".toast").innerText()));
  console.log("restore input", JSON.stringify(await input.inputValue()));
  console.log(
    "restore api",
    JSON.stringify(
      (await (await fetch("http://127.0.0.1:18080/api/sessions")).json())[0].roomUrl,
    ),
  );

  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
