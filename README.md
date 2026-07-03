# DATA-SCRAPER-

A lightweight Python scraper for downloading images, videos, audio, documents, and archives from a starting URL.

This repo now includes `Duke2_Enhanced.py`, a more capable version with Cloudflare bypass support, proxy support, gallery generation, and interactive crawl controls.

## Features

- Cloudflare bypass engine selection
- TLS fingerprint impersonation via `curl_cffi`
- HTTP/2 transport via `httpx`
- Headless browser fallback via `pyppeteer`
- Fallback to `cloudscraper` or standard `requests`
- Interactive URL entry and scraping options
- Max crawl depth and max page limits
- Same-domain crawl option
- Proxy support
- Smart retry with exponential backoff
- Media extraction from lazy-loaded content and meta tags
- Download queue with threaded downloads
- Save path detection for Linux and Android/Termux

## Install

```bash
pip install -r requirements.txt
```

## Usage

```bash
python Duke2_Enhanced.py
```

If you want, make the script executable first:

```bash
chmod +x Duke2_Enhanced.py
./Duke2_Enhanced.py
```

## Interactive tutorial

1. **Enter starting URL**
   - The scraper asks for a URL in the URL bar prompt.
   - If you omit `http://` or `https://`, it automatically adds `https://`.

2. **Choose media types**
   - Select one or more options by number.
   - Example: `1,2` for images and videos.
   - Choose `7` to download all supported media types.

3. **Min file size**
   - Enter the smallest file size to download in KB.
   - Use `0` to disable this filter.

4. **Max crawl depth**
   - This limits how many link levels the scraper will follow.
   - `0` means only the starting page is crawled.
   - `1` means the start page plus links found on that page.
   - `2` means the start page, first-level links, and second-level links.
   - Use a lower number to keep the crawl focused and faster.

5. **Max pages to crawl**
   - This limits the total number of pages visited during the crawl.
   - If the scraper reaches this page count, it stops even if more links remain.
   - Use it to prevent long, unbounded crawls on large sites.

6. **Stay in same domain**
   - Choose `y` to restrict crawling to the initial domain.
   - Choose `n` to allow external links to be followed.

7. **Proxy URL (optional)**
   - Enter a proxy like `http://user:pass@host:port` if you want requests routed through a proxy.
   - Leave blank to crawl without a proxy.

8. **Cloudflare bypass engine**
   - `Auto` uses the best available engine.
   - `curl_cffi` is the fastest bypass option when installed.
   - `cloudscraper` solves Cloudflare JS challenges.
- `httpx` uses HTTP/2 and browser-like transport for better success on heavy sites.
- `browser` uses a headless browser fallback for JS-heavy or challenge-protected pages.
9. **External viewer**
   - Choose whether downloaded files should open automatically after download.
   - This is useful on Android/Termux with `termux-open`.

10. **Per-type download limit**
    - For each chosen media type, you can limit the number of files downloaded.
    - Enter `0` for no limit.

## Output location

- Android/Termux: `/sdcard/Download/Duke2`
- Linux: `~/Downloads/Duke2` or `~/Duke2`

## Notes

- Use the updated `Duke2_Enhanced.py` script for the latest features.
- The old `Duke.py` script is no longer required.
