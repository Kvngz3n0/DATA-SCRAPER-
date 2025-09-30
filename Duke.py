# media_scraper_termux.py (Requests fallback version for Termux)

import os
import csv
import json
import random
import requests
import threading
import zipfile
from bs4 import BeautifulSoup as _BeautifulSoup
from urllib.parse import urljoin, urlparse
from queue import Queue

MEDIA_EXTENSIONS = {
    'images': ['.jpg', '.jpeg', '.png', '.gif', '.webp', '.svg'],
    'videos': ['.mp4', '.webm'],
    'audio':  ['.mp3', '.ogg', '.wav'],
    'documents': ['.pdf', '.epub', '.docx', '.txt']
}

visited_urls = set()
downloaded_media = []
download_queue = Queue()
lock = threading.Lock()
MAX_THREADS = 8


class BeautifulSoup(_BeautifulSoup):
    pass

def get_random_headers():
    agents = [
        "Mozilla/5.0 (Linux; Android 10; Mobile)",
        "Mozilla/5.0 (Windows NT 10.0; Win64)",
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64)"
    ]
    return {"User-Agent": random.choice(agents)}


def get_page_content(url):
    r = requests.get(url, headers=get_random_headers(), timeout=10)
    r.raise_for_status()
    return r.text


def is_media_file(url, extensions):
    return any(url.lower().endswith(ext) for ext in extensions)


def download_worker(save_dir, min_size_kb, max_media_per_type):
    media_count = {}
    while not download_queue.empty():
        item = download_queue.get()
        url, mtype, source_url = item

        if max_media_per_type.get(mtype, 0) and media_count.get(mtype, 0) >= max_media_per_type[mtype]:
            download_queue.task_done()
            continue

        try:
            r = requests.get(url, headers=get_random_headers(), stream=True, timeout=10)
            r.raise_for_status()

            size_kb = int(r.headers.get("Content-Length", 0)) / 1024
            if size_kb < min_size_kb:
                continue

            filename = os.path.basename(urlparse(url).path)
            if not filename or '.' not in filename:
                ext = os.path.splitext(url)[1] or '.bin'
                filename = f"file_{random.randint(1000,9999)}{ext}"

            folder = os.path.join(save_dir, mtype)
            os.makedirs(folder, exist_ok=True)
            filepath = os.path.join(folder, filename)

            with open(filepath, 'wb') as f:
                for chunk in r.iter_content(8192):
                    f.write(chunk)

            with lock:
                downloaded_media.append({
                    "url": url,
                    "type": mtype,
                    "size_kb": int(size_kb),
                    "filename": filename,
                    "source_page": source_url
                })
                media_count[mtype] = media_count.get(mtype, 0) + 1

            print(f"Downloaded: {filename} ({int(size_kb)}KB)")

        except Exception as e:
            print(f"Failed: {url} -> {e}")
        finally:
            download_queue.task_done()


def extract_media_links(soup, base_url, mtype):
    links = set()
    if mtype == 'images':
        tags = soup.find_all(['img', 'source', 'meta'])
        for tag in tags:
            for attr in ['src', 'data-src', 'content']:
                src = tag.get(attr)
                if src:
                    links.add(urljoin(base_url, src))
    elif mtype in ['videos', 'audio']:
        tags = soup.find_all(['video', 'audio', 'source'])
        for tag in tags:
            src = tag.get('src')
            if src:
                links.add(urljoin(base_url, src))
    elif mtype == 'documents':
        for tag in soup.find_all('a', href=True):
            href = tag.get('href')
            if is_media_file(href, MEDIA_EXTENSIONS['documents']):
                links.add(urljoin(base_url, href))
    return links


def extract_links(soup, base_url, same_domain):
    links = set()
    base_domain = urlparse(base_url).netloc
    for tag in soup.find_all('a', href=True):
        href = tag.get('href')
        full_url = urljoin(base_url, href)
        if same_domain and urlparse(full_url).netloc != base_domain:
            continue
        links.add(full_url)
    return links


def crawl(url, media_types, save_dir, min_size_kb, max_depth, max_pages, same_domain, current_depth=0):
    if url in visited_urls or current_depth > max_depth or len(visited_urls) >= max_pages:
        return

    try:
        print(f"Crawling: {url} [Depth: {current_depth}]")
        html = get_page_content(url)
        soup = BeautifulSoup(html, 'html.parser')
        visited_urls.add(url)

        for mtype in media_types:
            links = extract_media_links(soup, url, mtype)
            for link in links:
                download_queue.put((link, mtype, url))

        for link in extract_links(soup, url, same_domain):
            crawl(link, media_types, save_dir, min_size_kb, max_depth, max_pages, same_domain, current_depth + 1)

    except Exception as e:
        print(f"Failed to crawl {url}: {e}")


def save_results(save_dir):
    with open(os.path.join(save_dir, "results.json"), 'w') as jf:
        json.dump(downloaded_media, jf, indent=2)
    with open(os.path.join(save_dir, "results.csv"), 'w', newline='') as cf:
        writer = csv.DictWriter(cf, fieldnames=['url', 'type', 'size_kb', 'filename', 'source_page'])
        writer.writeheader()
        writer.writerows(downloaded_media)

    zip_path = os.path.join(save_dir, "media.zip")
    with zipfile.ZipFile(zip_path, 'w') as zipf:
        for root, _, files in os.walk(save_dir):
            for file in files:
                if file != "media.zip":
                    full_path = os.path.join(root, file)
                    rel_path = os.path.relpath(full_path, save_dir)
                    zipf.write(full_path, rel_path)


def main():
    print("\n🕷️ WEB SCRAPPER \n")
    url = input("🔗 Enter starting URL: ").strip()
    print("\n🎯 Choose media types: 1. Images 2. Videos 3. Audio 4. Documents 5. All")
    choice = input("➡️ Enter choice (1-5): ").strip()
    media_map = {'1': ['images'], '2': ['videos'], '3': ['audio'], '4': ['documents'], '5': ['images', 'videos', 'audio', 'documents']}
    media_types = media_map.get(choice, ['images'])
    min_size_kb = int(input("📏 Min file size in KB (0 for none): ") or 0)
    max_depth = int(input("📚 Max crawl depth: ") or 2)
    max_pages = int(input("📄 Max pages to crawl: ") or 100)
    same_domain = input("🌐 Stay in same domain? (y/n): ").lower() == 'y'
    max_per_type = {}
    for mtype in media_types:
        n = input(f"🛡️ Max number of {mtype} to download (0 = no limit): ")
        max_per_type[mtype] = int(n or 0)

    save_dir = "/sdcard/Download/media"
    os.makedirs(save_dir, exist_ok=True)

    crawl(url, media_types, save_dir, min_size_kb, max_depth, max_pages, same_domain)

    threads = []
    for _ in range(min(MAX_THREADS, download_queue.qsize())):
        t = threading.Thread(target=download_worker, args=(save_dir, min_size_kb, max_per_type))
        t.start()
        threads.append(t)

    for t in threads:
        t.join()

    save_results(save_dir)
    print("\n✅ Done! Media saved in /sdcard/Download/media")
    print(f"📦 Total downloaded: {len(downloaded_media)} files")


if __name__ == "__main__":
    main()

