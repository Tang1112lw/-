const { chromium } = require("playwright");
const path = require("path");

async function main() {
  const browser = await chromium.connectOverCDP("http://127.0.0.1:9222");
  const contexts = browser.contexts();
  console.log("contexts:", contexts.length);
  for (const ctx of contexts) {
    console.log("pages:", ctx.pages().map((p) => p.url()));
  }

  const context = contexts[0];
  if (!context) throw new Error("no browser context");

  const page = await context.newPage();
  await page.goto("https://live.douyin.com/537108818552", {
    waitUntil: "domcontentloaded",
    timeout: 60000,
  });
  await page.waitForTimeout(15000);

  const info = await page.evaluate(() => {
    const all = [...document.querySelectorAll("*")];
    const describe = (el) => ({
      tag: el.tagName,
      cls: typeof el.className === "string" ? el.className.slice(0, 160) : "",
      e2e: el.getAttribute("data-e2e"),
      aria: el.getAttribute("aria-label"),
      title: el.getAttribute("title"),
      placeholder: el.getAttribute("placeholder"),
      editable: el.isContentEditable,
      text: (el.innerText || el.textContent || "").replace(/\s+/g, " ").slice(0, 80),
    });

    return {
      url: location.href,
      title: document.title,
      needLogin: (document.body.innerText || "").includes("需先登录"),
      loginButtons: all
        .filter((el) => (el.innerText || "").trim() === "登录")
        .slice(0, 10)
        .map(describe),
      editable: all
        .filter(
          (el) =>
            el.isContentEditable ||
            el.tagName === "TEXTAREA" ||
            (el.tagName === "INPUT" && ["text", "search"].includes(el.type))
        )
        .slice(0, 30)
        .map(describe),
      likeCandidates: all
        .filter((el) => {
          const s = `${el.className || ""} ${el.getAttribute("aria-label") || ""} ${
            el.getAttribute("data-e2e") || ""
          }`;
          return /like|heart|点赞|zan/i.test(s);
        })
        .slice(0, 30)
        .map(describe),
      chatItems: [...document.querySelectorAll(".webcast-chatroom___item")]
        .slice(-6)
        .map((el) => (el.innerText || "").replace(/\s+/g, " ").slice(0, 160)),
      bodyTail: (document.body.innerText || "").slice(-800),
    };
  });

  console.log(JSON.stringify(info, null, 2));
  await page.screenshot({
    path: path.resolve(__dirname, "existing-douyin.png"),
    fullPage: false,
  });
  await page.close();
  // Important: do not close the user's browser.
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
