const { chromium } = require("playwright");

async function main() {
  const browser = await chromium.connectOverCDP("http://127.0.0.1:9222");
  const context = browser.contexts()[0];
  if (!context) throw new Error("no context");

  const page = await context.newPage();
  await page.goto("https://live.douyin.com/537108818552", {
    waitUntil: "domcontentloaded",
    timeout: 60000,
  });
  await page.waitForTimeout(12000);

  const info = await page.evaluate(() => {
    const describe = (el) => {
      const r = el.getBoundingClientRect();
      return {
        tag: el.tagName,
        cls: typeof el.className === "string" ? el.className.slice(0, 180) : "",
        e2e: el.getAttribute("data-e2e"),
        aria: el.getAttribute("aria-label"),
        title: el.getAttribute("title"),
        text: (el.innerText || el.textContent || "").replace(/\s+/g, " ").slice(0, 100),
        box: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
      };
    };
    const chain = (el) => {
      const out = [];
      let cur = el;
      for (let i = 0; i < 7 && cur; i++) {
        out.push(describe(cur));
        cur = cur.parentElement;
      }
      return out;
    };
    const all = [...document.querySelectorAll("*")];
    const likeText = all.filter((el) =>
      [...el.childNodes]
        .filter((n) => n.nodeType === Node.TEXT_NODE)
        .some((n) => (n.textContent || "").includes("本场点赞"))
    );
    const svgLike = all.filter((el) => {
      const s = `${el.className || ""} ${el.getAttribute("aria-label") || ""} ${
        el.getAttribute("data-e2e") || ""
      }`;
      return /like|heart|digg|zan|赞/i.test(s);
    });
    const editor = document.querySelector(
      ".zone-container.editor-kit-container[contenteditable='true']"
    );
    return {
      likeText: likeText.slice(0, 4).map((el) => ({ chain: chain(el), html: el.outerHTML.slice(0, 800) })),
      svgLike: svgLike.slice(0, 20).map(describe),
      editor: editor ? { chain: chain(editor), html: editor.outerHTML.slice(0, 1500) } : null,
      chatroom: document.querySelector("#chatroom")
        ? describe(document.querySelector("#chatroom"))
        : null,
    };
  });

  console.log(JSON.stringify(info, null, 2));
  await page.close();
  process.exit(0);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
