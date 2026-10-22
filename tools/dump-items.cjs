const { chromium } = require("playwright");
const path = require("path");

async function main() {
  const root = path.resolve(__dirname, "..");
  const context = await chromium.launchPersistentContext(path.join(root, ".inspect-profile"), {
    channel: "msedge",
    headless: false,
    viewport: { width: 1440, height: 900 },
    args: [
      "--disable-blink-features=AutomationControlled",
      "--no-first-run",
      "--mute-audio",
    ],
  });
  const page = context.pages()[0] || (await context.newPage());
  await page.goto("https://live.douyin.com/537108818552", {
    waitUntil: "domcontentloaded",
    timeout: 60000,
  });
  await page.waitForTimeout(15000);

  const result = await page.evaluate(() => {
    const lists = [...document.querySelectorAll(".webcast-chatroom___list")].map((el) => ({
      cls: el.className,
      count: el.querySelectorAll(".webcast-chatroom___item").length,
      text: (el.innerText || "").slice(0, 200),
    }));
    const items = [...document.querySelectorAll(".webcast-chatroom___item")]
      .slice(-15)
      .map((el) => ({
        text: (el.innerText || "").replace(/\s+/g, " ").slice(0, 200),
        html: el.outerHTML.slice(0, 3000),
      }));
    return { lists, items };
  });

  console.log(JSON.stringify(result, null, 2));
  await context.close();
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
