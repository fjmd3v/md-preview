import { createRequire } from 'module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const root = new URL('../..', import.meta.url);
const preview = new URL('mobile/shared/preview.html', root).href;
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
  isMobile: true
});

const errors = [];
page.on('pageerror', error => errors.push(error.message));
page.on('console', message => {
  if (message.type() === 'error') errors.push(message.text());
});

await page.goto(preview);
await page.waitForLoadState('domcontentloaded');
await page.evaluate(() => {
  window.MDPreview.render({
    name: 'mobile-fixture.md',
    baseHref: '',
    markdown: [
      '# Mobile fixture',
      '',
      'Inline math $a^2+b^2=c^2$ and display math:',
      '',
      '$$E=mc^2$$',
      '',
      '```mermaid',
      'graph TD',
      '  A[Open] --> B[Preview]',
      '```',
      '',
      '[bad](javascript:window.__bad=1)'
    ].join('\n')
  });
});

await page.waitForSelector('.katex', { timeout: 5000 });
await page.waitForSelector('.mdp-mermaid svg', { timeout: 5000 });
await page.locator('#search-toggle').click();
await page.locator('#search-input').fill('math');
await page.waitForSelector('mark.search-hit.current', { timeout: 1000 });
await page.locator('a[href^="javascript:"]').click();

const result = await page.evaluate(() => ({
  title: document.getElementById('title').textContent,
  katex: document.querySelectorAll('.katex').length,
  mermaidSvg: document.querySelectorAll('.mdp-mermaid svg').length,
  searchHits: document.querySelectorAll('mark.search-hit').length,
  bad: window.__bad === 1
}));

await browser.close();

if (errors.length) {
  throw new Error(`Renderer console errors:\n${errors.join('\n')}`);
}
if (result.title !== 'mobile-fixture.md') {
  throw new Error(`Unexpected title: ${result.title}`);
}
if (!result.katex || !result.mermaidSvg || !result.searchHits) {
  throw new Error(`Renderer feature check failed: ${JSON.stringify(result)}`);
}
if (result.bad) {
  throw new Error('javascript: link executed');
}

console.log('[mobile-renderer] OK');
