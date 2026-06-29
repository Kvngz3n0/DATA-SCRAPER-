#!/usr/bin/env python3
# Duke2 Enhanced Media Scraper
# Cloudflare Bypass | Gallery Viewer | External Viewer | Proxy Support
# Compatible with Termux/Android, Linux, Windows, macOS

import os
import sys
import csv
import json
import time
import random
import shutil
import zipfile
import hashlib
import threading
import subprocess
import base64
from urllib.parse import urljoin, urlparse, unquote
from queue import Queue

# ── CLOUDFLARE BYPASS ENGINE ──────────────────────────────────────────────
# Uses curl_cffi for TLS fingerprint impersonation (JA3/JA4 bypass)
# Falls back to requests + cloudscraper if curl_cffi unavailable

HTTP_ENGINE = "requests"  # default, will try to upgrade

try:
    from curl_cffi import requests as cf_requests
    HTTP_ENGINE = "curl_cffi"
    print("[+] curl_cffi loaded - TLS fingerprint impersonation ACTIVE")
except ImportError:
    print("[!] curl_cffi not installed - run: pip install curl-cffi")
    try:
        import cloudscraper
        HTTP_ENGINE = "cloudscraper"
        print("[+] cloudscraper loaded - Cloudflare JS challenge bypass ACTIVE")
    except ImportError:
        print("[!] cloudscraper not installed - run: pip install cloudscraper")
        import requests
        HTTP_ENGINE = "requests"
        print("[!] Using standard requests - limited Cloudflare bypass")

from bs4 import BeautifulSoup as _BeautifulSoup

# ── CONFIGURATION ─────────────────────────────────────────────────────────

MEDIA_EXTENSIONS = {
    'images':    ['.jpg', '.jpeg', '.png', '.gif', '.webp', '.svg', '.bmp', '.ico', '.tiff', '.avif'],
    'videos':    ['.mp4', '.webm', '.mkv', '.flv', '.avi', '.mov', '.m4v', '.3gp', '.ogv'],
    'audio':     ['.mp3', '.ogg', '.wav', '.flac', '.aac', '.m4a', '.opus', '.wma'],
    'documents': ['.pdf', '.epub', '.docx', '.txt', '.doc', '.rtf', '.odt'],
    'archives':  ['.zip', '.rar', '.7z', '.tar', '.gz', '.bz2', '.xz'],
    'ebooks':    ['.mobi', '.azw3', '.azw', '.cbz', '.cbr']
}

# Extended patterns for "locked" / premium content sites
LOCKED_PATTERNS = [
    '/locked/', '/premium/', '/exclusive/', '/members/',
    '/subscriber/', '/paid/', '/vip/', '/backstage/',
    '/s/', '/attachments/', '/download.php', '/file.php',
    '/content/', '/media/download/', '/get/'
]

# Lazy-load attribute patterns (for JS-heavy sites)
LAZY_ATTRIBUTES = [
    'data-src', 'data-lazy-src', 'data-original', 'data-url',
    'data-full', 'data-large', 'data-hd', 'data-source',
    'data-poster', 'data-video', 'data-audio', 'data-file',
    'data-thumb', 'data-preview', 'data-media',
    'ng-src', 'v-lazy', 'data-delayed-url', 'srcset'
]

visited_urls = set()
downloaded_media = []
download_queue = Queue()
lock = threading.Lock()
MAX_THREADS = 8

SESSION = None
COOKIE_JAR = None

# ── HTTP SESSION MANAGER ──────────────────────────────────────────────────

class BeautifulSoup(_BeautifulSoup):
    pass


def get_headers():
    """Generate realistic browser headers for stealth"""
    chrome_versions = ["120", "121", "122", "123", "124", "125", "126", "127", "128", "129", "130", "131"]
    ver = random.choice(chrome_versions)
    platforms = [
        f"Windows NT 10.0; Win64; x64",
        f"Macintosh; Intel Mac OS X 10_15_7",
        f"X11; Linux x86_64",
        f"Linux; Android 14; SM-S918B",
        f"Linux; Android 13; Pixel 7"
    ]
    platform = random.choice(platforms)
    sec_ch_ua = f'"Not_A Brand";v="8", "Chromium";v="{ver}", "Google Chrome";v="{ver}"'

    return {
        "User-Agent": f"Mozilla/5.0 ({platform}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{ver}.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language": random.choice(["en-US,en;q=0.9", "en-GB,en;q=0.9", "en-CA,en;q=0.9"]),
        "Accept-Encoding": "gzip, deflate, br",
        "Referer": "https://www.google.com/",
        "Sec-Ch-Ua": sec_ch_ua,
        "Sec-Ch-Ua-Mobile": "?0" if "Android" not in platform else "?1",
        "Sec-Ch-Ua-Platform": '"Windows"' if "Windows" in platform else '"macOS"' if "Mac" in platform else '"Linux"' if "Linux" in platform and "Android" not in platform else '"Android"',
        "Sec-Fetch-Dest": "document",
        "Sec-Fetch-Mode": "navigate",
        "Sec-Fetch-Site": "cross-site",
        "Sec-Fetch-User": "?1",
        "Upgrade-Insecure-Requests": "1",
        "Connection": "keep-alive",
        "Cache-Control": "max-age=0",
    }


def init_session(proxy=None, impersonate=True):
    """Initialize HTTP session with Cloudflare bypass capabilities"""
    global SESSION, COOKIE_JAR, HTTP_ENGINE

    headers = get_headers()

    if HTTP_ENGINE == "curl_cffi" and impersonate:
        SESSION = cf_requests.Session(impersonate="chrome")
        SESSION.headers.update(headers)
        if proxy:
            SESSION.proxies = {"http": proxy, "https": proxy}
        print("[+] Session: curl_cffi with Chrome TLS impersonation")

    elif HTTP_ENGINE == "cloudscraper":
        SESSION = cloudscraper.create_scraper(
            browser={
                'browser': 'chrome',
                'platform': 'windows',
                'mobile': False
            }
        )
        SESSION.headers.update(headers)
        if proxy:
            SESSION.proxies = {"http": proxy, "https": proxy}
        print("[+] Session: cloudscraper with JS challenge solver")

    else:
        SESSION = requests.Session()
        SESSION.headers.update(headers)
        if proxy:
            SESSION.proxies = {"http": proxy, "https": proxy}
        print("[!] Session: standard requests (limited bypass)")

    # Load saved cookies if available
    cookie_path = os.path.expanduser("~/.duke2_cookies.json")
    if os.path.exists(cookie_path):
        try:
            with open(cookie_path, 'r') as f:
                cookies = json.load(f)
                for name, value in cookies.items():
                    SESSION.cookies.set(name, value)
            print(f"[+] Loaded {len(cookies)} cookies from {cookie_path}")
        except Exception as e:
            print(f"[!] Failed to load cookies: {e}")

    return SESSION


def smart_retry_request(url, max_retries=5, backoff=2, timeout=30):
    """Smart request with exponential backoff and engine fallback"""
    global SESSION, HTTP_ENGINE

    for attempt in range(max_retries):
        try:
            if HTTP_ENGINE == "curl_cffi":
                response = SESSION.get(url, timeout=timeout, allow_redirects=True)
            else:
                response = SESSION.get(url, timeout=timeout, allow_redirects=True)

            if response.status_code == 200:
                return response
            elif response.status_code == 403:
                print(f"[!] 403 Forbidden on attempt {attempt + 1} - rotating headers...")
                SESSION.headers.update(get_headers())
            elif response.status_code == 429:
                wait_time = backoff ** attempt + random.uniform(1, 3)
                print(f"[!] 429 Rate limited - waiting {wait_time:.1f}s...")
                time.sleep(wait_time)
            elif response.status_code in [500, 502, 503, 504]:
                wait_time = backoff ** attempt
                print(f"[!] Server error {response.status_code} - retrying in {wait_time}s...")
                time.sleep(wait_time)
            else:
                print(f"[!] HTTP {response.status_code} - retrying...")
                time.sleep(backoff ** attempt)

        except Exception as e:
            wait_time = backoff ** attempt + random.uniform(0.5, 2)
            print(f"[!] Request failed (attempt {attempt + 1}/{max_retries}): {e}")
            time.sleep(wait_time)

            # Fallback: try requests if curl_cffi fails
            if HTTP_ENGINE == "curl_cffi" and attempt == max_retries - 2:
                print("[*] Attempting fallback to standard requests...")
                try:
                    r = requests.get(url, headers=get_headers(), timeout=timeout)
                    if r.status_code == 200:
                        return r
                except:
                    pass

    return None


def get_page_content(url):
    """Fetch page content with Cloudflare bypass"""
    response = smart_retry_request(url)
    if response is None:
        raise Exception(f"Failed to fetch {url} after all retries")
    return response.text


# ── ENHANCED MEDIA EXTRACTION ─────────────────────────────────────────────

def is_media_file(url, extensions):
    """Check if URL ends with known media extension"""
    url_lower = url.lower().split('?')[0]  # Strip query params
    return any(url_lower.endswith(ext) for ext in extensions)


def extract_lazy_media(soup, base_url, mtype):
    """Extract media from lazy-loaded / JS-rendered attributes"""
    links = set()
    
    # All elements with common media containers
    elements = soup.find_all(['img', 'video', 'audio', 'source', 'picture', 
                               'div', 'span', 'a', 'meta', 'link'])
    
    for elem in elements:
        # Check all lazy-load attributes
        for attr in LAZY_ATTRIBUTES:
            val = elem.get(attr)
            if val and ('.' in val or val.startswith('http')):
                if attr == 'srcset':
                    # Handle srcset: "url1 1x, url2 2x"
                    for part in val.split(','):
                        url_part = part.strip().split(' ')[0]
                        if url_part:
                            links.add(urljoin(base_url, url_part))
                else:
                    links.add(urljoin(base_url, val))
        
        # Check inline styles for background images
        style = elem.get('style', '')
        if 'url(' in style:
            import re
            urls = re.findall(r'url\(["\']?(.*?)["\']?\)', style)
            for u in urls:
                if u.startswith('data:'):
                    continue
                links.add(urljoin(base_url, u))
    
    # Filter by media type extensions
    filtered = set()
    for link in links:
        if is_media_file(link, MEDIA_EXTENSIONS.get(mtype, [])):
            filtered.add(link)
    
    return filtered


def extract_media_links(soup, base_url, mtype):
    """Enhanced media extraction with lazy-load and locked content support"""
    links = set()
    
    if mtype == 'images':
        # Standard img tags
        tags = soup.find_all(['img', 'source', 'picture'])
        for tag in tags:
            for attr in ['src', 'data-src', 'data-lazy-src', 'data-original', 
                         'data-full', 'data-large', 'data-hd', 'srcset']:
                src = tag.get(attr)
                if src:
                    if attr == 'srcset':
                        for part in src.split(','):
                            url_part = part.strip().split(' ')[0]
                            if url_part:
                                links.add(urljoin(base_url, url_part))
                    else:
                        links.add(urljoin(base_url, src))
        
        # Meta og:image
        for meta in soup.find_all('meta', property=['og:image', 'og:image:secure_url', 'twitter:image']):
            content = meta.get('content')
            if content:
                links.add(urljoin(base_url, content))
                
    elif mtype in ['videos', 'audio']:
        tags = soup.find_all(['video', 'audio', 'source'])
        for tag in tags:
            for attr in ['src', 'data-src', 'data-video', 'data-source', 'data-poster']:
                src = tag.get(attr)
                if src:
                    links.add(urljoin(base_url, src))
        
        # Video meta tags
        for meta in soup.find_all('meta', property=['og:video', 'og:video:secure_url', 
                                                     'twitter:player', 'og:audio']):
            content = meta.get('content')
            if content:
                links.add(urljoin(base_url, content))
        
        # iframe video embeds (YouTube, Vimeo, etc.)
        for iframe in soup.find_all('iframe', src=True):
            src = iframe.get('src', '')
            if any(x in src for x in ['youtube', 'youtu.be', 'vimeo', 'dailymotion']):
                links.add(urljoin(base_url, src))
    
    else:  # documents, archives, ebooks
        for tag in soup.find_all('a', href=True):
            href = tag.get('href')
            if is_media_file(href, MEDIA_EXTENSIONS[mtype]):
                links.add(urljoin(base_url, href))
    
    # Also check for locked/premium content patterns
    for tag in soup.find_all(['a', 'link'], href=True):
        href = tag.get('href', '')
        if any(p in href for p in LOCKED_PATTERNS):
            if is_media_file(href, MEDIA_EXTENSIONS.get(mtype, [])):
                links.add(urljoin(base_url, href))
    
    # Add lazy-loaded media
    lazy_links = extract_lazy_media(soup, base_url, mtype)
    links.update(lazy_links)
    
    return links


def extract_links(soup, base_url, same_domain):
    """Extract navigation links with same-domain filtering"""
    links = set()
    base_domain = urlparse(base_url).netloc
    
    for tag in soup.find_all('a', href=True):
        href = tag.get('href')
        full_url = urljoin(base_url, href)
        
        # Skip non-HTTP URLs
        if not full_url.startswith(('http://', 'https://')):
            continue
            
        if same_domain and urlparse(full_url).netloc != base_domain:
            continue
            
        links.add(full_url)
    
    return links


# ── DOWNLOAD WORKER ───────────────────────────────────────────────────────

def download_worker(save_dir, min_size_kb, max_media_per_type, use_external_viewer=False):
    """Download worker with external viewer support"""
    media_count = {}
    
    while not download_queue.empty():
        try:
            url, mtype, source_url = download_queue.get(timeout=3)
        except:
            break

        if max_media_per_type.get(mtype, 0) and media_count.get(mtype, 0) >= max_media_per_type[mtype]:
            download_queue.task_done()
            continue

        try:
            response = smart_retry_request(url, max_retries=3, timeout=20)
            if response is None:
                download_queue.task_done()
                continue

            size_kb = int(response.headers.get("Content-Length", 0)) / 1024
            if size_kb < min_size_kb:
                download_queue.task_done()
                continue

            # Generate filename
            parsed = urlparse(url)
            filename = os.path.basename(unquote(parsed.path))
            if not filename or '.' not in filename:
                ext = os.path.splitext(url)[1] or '.bin'
                ext = ext.split('?')[0]
                filename = f"file_{hashlib.md5(url.encode()).hexdigest()[:8]}{ext}"

            folder = os.path.join(save_dir, mtype)
            os.makedirs(folder, exist_ok=True)
            filepath = os.path.join(folder, filename)

            with open(filepath, 'wb') as f:
                for chunk in response.iter_content(16384):
                    f.write(chunk)

            with lock:
                downloaded_media.append({
                    "url": url,
                    "type": mtype,
                    "size_kb": int(size_kb),
                    "filename": filename,
                    "filepath": filepath,
                    "source_page": source_url
                })
                media_count[mtype] = media_count.get(mtype, 0) + 1

            print(f"[+] Downloaded: {filename} ({int(size_kb)}KB) [{mtype}]")
            
            # Optionally open with external viewer
            if use_external_viewer:
                open_with_external_viewer(filepath)

        except Exception as e:
            print(f"[!] Failed: {url} -> {e}")
        finally:
            download_queue.task_done()


# ── EXTERNAL VIEWER ───────────────────────────────────────────────────────

def open_with_external_viewer(filepath):
    """Open downloaded file with system's default/external viewer"""
    try:
        if sys.platform == "linux" or os.path.exists("/data/data/com.termux"):
            # Termux/Android - use termux-open
            result = subprocess.run(
                ["termux-open", filepath],
                capture_output=True, timeout=10
            )
            if result.returncode == 0:
                return
            # Fallback to xdg-open
            subprocess.run(
                ["xdg-open", filepath],
                capture_output=True, timeout=10
            )
        elif sys.platform == "darwin":
            subprocess.run(["open", filepath], timeout=10)
        elif sys.platform == "win32":
            os.startfile(filepath)
    except Exception as e:
        print(f"[!] External viewer error: {e}")


def open_gallery_in_browser(gallery_path):
    """Open the gallery HTML in default browser"""
    try:
        if sys.platform == "linux" or os.path.exists("/data/data/com.termux"):
            subprocess.run(["termux-open", gallery_path], capture_output=True, timeout=10)
        elif sys.platform == "darwin":
            subprocess.run(["open", gallery_path], timeout=10)
        elif sys.platform == "win32":
            os.startfile(gallery_path)
    except Exception as e:
        print(f"[!] Could not open gallery: {e}")


# ── GALLERY VIEWER GENERATOR ──────────────────────────────────────────────

def generate_gallery_html(save_dir):
    """Generate an interactive HTML gallery viewer for downloaded content"""
    
    # Group media by type
    media_by_type = {}
    for item in downloaded_media:
        mtype = item['type']
        if mtype not in media_by_type:
            media_by_type[mtype] = []
        media_by_type[mtype].append(item)
    
    if not media_by_type:
        print("[!] No media to display in gallery")
        return None
    
    gallery_path = os.path.join(save_dir, "gallery.html")
    
    # Generate HTML
    html_parts = []
    html_parts.append("""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Duke2 Media Gallery</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: #0a0a0f; color: #e0e0e0;
        }
        .header {
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
            padding: 20px; text-align: center;
            border-bottom: 2px solid #e94560;
            position: sticky; top: 0; z-index: 100;
        }
        .header h1 { color: #e94560; font-size: 28px; }
        .header p { color: #888; margin-top: 5px; }
        .stats {
            display: flex; justify-content: center; gap: 20px;
            margin-top: 10px; flex-wrap: wrap;
        }
        .stat { background: rgba(233,69,96,0.15); padding: 5px 15px; 
                border-radius: 20px; font-size: 13px; }
        .tabs {
            display: flex; justify-content: center; gap: 5px;
            padding: 15px; background: #12121f;
            position: sticky; top: 90px; z-index: 99;
            flex-wrap: wrap;
        }
        .tab {
            padding: 8px 20px; border: none; border-radius: 20px;
            background: #1a1a2e; color: #888; cursor: pointer;
            font-size: 14px; transition: all 0.3s;
        }
        .tab:hover { background: #e94560; color: white; }
        .tab.active { background: #e94560; color: white; }
        .gallery {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
            gap: 15px; padding: 20px;
        }
        .media-card {
            background: #16162a; border-radius: 12px;
            overflow: hidden; transition: transform 0.3s, box-shadow 0.3s;
            border: 1px solid #2a2a4a;
        }
        .media-card:hover {
            transform: translateY(-5px);
            box-shadow: 0 10px 30px rgba(233,69,96,0.3);
        }
        .media-card img, .media-card video {
            width: 100%; height: 200px; object-fit: cover;
            display: block; background: #0a0a0f;
        }
        .media-card audio {
            width: 100%; padding: 10px;
        }
        .media-card .doc-icon {
            width: 100%; height: 200px;
            display: flex; align-items: center; justify-content: center;
            font-size: 60px; background: #1a1a2e;
        }
        .media-info {
            padding: 12px;
        }
        .media-info .filename {
            font-size: 13px; font-weight: 600;
            white-space: nowrap; overflow: hidden;
            text-overflow: ellipsis; color: #fff;
        }
        .media-info .meta {
            font-size: 11px; color: #888; margin-top: 5px;
            display: flex; justify-content: space-between;
        }
        .media-actions {
            display: flex; gap: 8px; padding: 0 12px 12px;
        }
        .btn {
            flex: 1; padding: 8px; border: none; border-radius: 6px;
            background: #e94560; color: white; cursor: pointer;
            font-size: 12px; text-align: center; text-decoration: none;
            transition: background 0.3s;
        }
        .btn:hover { background: #ff6b81; }
        .btn-secondary { background: #2a2a4a; }
        .btn-secondary:hover { background: #3a3a5a; }
        .section { display: none; }
        .section.active { display: block; }
        .empty-msg {
            text-align: center; padding: 60px 20px; color: #555;
        }
        .filter-bar {
            display: flex; gap: 10px; padding: 10px 20px;
            background: #12121f; flex-wrap: wrap;
        }
        .filter-bar input {
            flex: 1; min-width: 200px; padding: 10px 15px;
            border: 1px solid #2a2a4a; border-radius: 8px;
            background: #1a1a2e; color: #fff; font-size: 14px;
        }
        .filter-bar select {
            padding: 10px 15px; border: 1px solid #2a2a4a;
            border-radius: 8px; background: #1a1a2e; color: #fff;
        }
        @media (max-width: 600px) {
            .gallery { grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 8px; padding: 10px; }
            .media-card img, .media-card video { height: 120px; }
            .header h1 { font-size: 20px; }
        }
    </style>
</head>
<body>
    <div class="header">
        <h1>🕷️ Duke2 Media Gallery</h1>
        <p>Downloaded Content Viewer</p>
        <div class="stats">
            <div class="stat">📁 Total: """ + str(len(downloaded_media)) + """ files</div>
            <div class="stat">📊 Size: """ + str(sum(i['size_kb'] for i in downloaded_media) // 1024) + """ MB</div>
        </div>
    </div>
    
    <div class="tabs">""")
    
    # Generate tabs
    type_icons = {'images': '🖼️', 'videos': '🎬', 'audio': '🎵', 
                  'documents': '📄', 'archives': '📦', 'ebooks': '📚'}
    
    for idx, mtype in enumerate(media_by_type.keys()):
        icon = type_icons.get(mtype, '📁')
        count = len(media_by_type[mtype])
        active = 'active' if idx == 0 else ''
        html_parts.append(f'        <button class="tab {active}" onclick="showSection(\'{mtype}\')">{icon} {mtype.title()} ({count})</button>')
    
    html_parts.append("""    </div>
    
    <div class="filter-bar">
        <input type="text" id="searchBox" placeholder="🔍 Search files..." onkeyup="filterGallery()">
        <select id="sortBy" onchange="sortGallery()">
            <option value="name">Sort by Name</option>
            <option value="size">Sort by Size</option>
            <option value="type">Sort by Type</option>
        </select>
    </div>
""")
    
    # Generate sections
    for idx, (mtype, items) in enumerate(media_by_type.items()):
        active = 'active' if idx == 0 else ''
        html_parts.append(f'    <div id="section-{mtype}" class="section {active}">')
        html_parts.append(f'        <div class="gallery" id="gallery-{mtype}">')
        
        for item in items:
            filepath = item['filepath']
            rel_path = os.path.relpath(filepath, save_dir)
            filename = item['filename']
            size = item['size_kb']
            
            if mtype == 'images':
                html_parts.append(f"""
            <div class="media-card" data-name="{filename}" data-size="{size}">
                <img src="{rel_path}" alt="{filename}" loading="lazy" 
                     onclick="this.requestFullscreen()">
                <div class="media-info">
                    <div class="filename">{filename}</div>
                    <div class="meta"><span>{size}KB</span><span>image</span></div>
                </div>
                <div class="media-actions">
                    <a href="{rel_path}" class="btn" download>⬇️ Download</a>
                    <button class="btn btn-secondary" onclick="navigator.share?navigator.share({{files:[],title:'{filename}'}}):alert('Share not supported')">🔗 Share</button>
                </div>
            </div>""")
            
            elif mtype == 'videos':
                html_parts.append(f"""
            <div class="media-card" data-name="{filename}" data-size="{size}">
                <video controls preload="metadata" poster="">
                    <source src="{rel_path}">
                </video>
                <div class="media-info">
                    <div class="filename">{filename}</div>
                    <div class="meta"><span>{size}KB</span><span>video</span></div>
                </div>
                <div class="media-actions">
                    <a href="{rel_path}" class="btn" download>⬇️ Download</a>
                </div>
            </div>""")
            
            elif mtype == 'audio':
                html_parts.append(f"""
            <div class="media-card" data-name="{filename}" data-size="{size}">
                <div class="doc-icon">🎵</div>
                <audio controls src="{rel_path}"></audio>
                <div class="media-info">
                    <div class="filename">{filename}</div>
                    <div class="meta"><span>{size}KB</span><span>audio</span></div>
                </div>
                <div class="media-actions">
                    <a href="{rel_path}" class="btn" download>⬇️ Download</a>
                </div>
            </div>""")
            
            else:
                ext = os.path.splitext(filename)[1].upper()
                html_parts.append(f"""
            <div class="media-card" data-name="{filename}" data-size="{size}">
                <div class="doc-icon">{ext}</div>
                <div class="media-info">
                    <div class="filename">{filename}</div>
                    <div class="meta"><span>{size}KB</span><span>{mtype}</span></div>
                </div>
                <div class="media-actions">
                    <a href="{rel_path}" class="btn" download>⬇️ Download</a>
                </div>
            </div>""")
        
        html_parts.append("""        </div>
    </div>""")
    
    # Add JavaScript
    html_parts.append("""
    <script>
        function showSection(type) {
            document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            document.getElementById('section-' + type).classList.add('active');
            event.target.classList.add('active');
        }
        
        function filterGallery() {
            const query = document.getElementById('searchBox').value.toLowerCase();
            document.querySelectorAll('.media-card').forEach(card => {
                const name = card.getAttribute('data-name').toLowerCase();
                card.style.display = name.includes(query) ? '' : 'none';
            });
        }
        
        function sortGallery() {
            const sortBy = document.getElementById('sortBy').value;
            document.querySelectorAll('.gallery').forEach(gallery => {
                const cards = Array.from(gallery.querySelectorAll('.media-card'));
                cards.sort((a, b) => {
                    if (sortBy === 'name') return a.getAttribute('data-name').localeCompare(b.getAttribute('data-name'));
                    if (sortBy === 'size') return parseInt(b.getAttribute('data-size')) - parseInt(a.getAttribute('data-size'));
                    return 0;
                });
                cards.forEach(c => gallery.appendChild(c));
            });
        }
    </script>
</body>
</html>""")
    
    with open(gallery_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(html_parts))
    
    print(f"[+] Gallery generated: {gallery_path}")
    return gallery_path


# ── CRAWLER ───────────────────────────────────────────────────────────────

def crawl(url, media_types, save_dir, min_size_kb, max_depth, max_pages, 
          same_domain, current_depth=0, respect_robots=True):
    """Enhanced crawler with better stealth and coverage"""
    
    if url in visited_urls or current_depth > max_depth or len(visited_urls) >= max_pages:
        return
    
    # Add delay for stealth
    time.sleep(random.uniform(0.5, 2.0))
    
    try:
        print(f"[{'=' * current_depth}> Crawling: {url} [Depth: {current_depth}]")
        html = get_page_content(url)
        soup = BeautifulSoup(html, 'html.parser')
        visited_urls.add(url)
        
        # Extract and queue media
        for mtype in media_types:
            links = extract_media_links(soup, url, mtype)
            for link in links:
                download_queue.put((link, mtype, url))
        
        # Follow links
        for link in extract_links(soup, url, same_domain):
            crawl(link, media_types, save_dir, min_size_kb, max_depth, 
                  max_pages, same_domain, current_depth + 1)
                  
    except Exception as e:
        print(f"[!] Failed to crawl {url}: {e}")


# ── RESULTS SAVING ────────────────────────────────────────────────────────

def save_results(save_dir):
    """Save results as JSON, CSV, ZIP, and HTML gallery"""
    
    # JSON
    with open(os.path.join(save_dir, "results.json"), 'w') as jf:
        json.dump(downloaded_media, jf, indent=2)
    
    # CSV
    with open(os.path.join(save_dir, "results.csv"), 'w', newline='') as cf:
        writer = csv.DictWriter(cf, fieldnames=['url', 'type', 'size_kb', 'filename', 'source_page'])
        writer.writeheader()
        for item in downloaded_media:
            writer.writerow({k: v for k, v in item.items() if k != 'filepath'})
    
    # ZIP
    zip_path = os.path.join(save_dir, "media.zip")
    with zipfile.ZipFile(zip_path, 'w') as zipf:
        for root, _, files in os.walk(save_dir):
            for file in files:
                if file not in ["media.zip", "gallery.html"]:
                    full_path = os.path.join(root, file)
                    rel_path = os.path.relpath(full_path, save_dir)
                    zipf.write(full_path, rel_path)
    
    # Gallery
    gallery_path = generate_gallery_html(save_dir)
    
    # Save cookies for future sessions
    if SESSION:
        try:
            cookie_path = os.path.expanduser("~/.duke2_cookies.json")
            cookies = {c.name: c.value for c in SESSION.cookies}
            with open(cookie_path, 'w') as f:
                json.dump(cookies, f)
        except Exception as e:
            print(f"[!] Failed to save cookies: {e}")
    
    return gallery_path


# ── MAIN ──────────────────────────────────────────────────────────────────

def main():
    print("""
    ╔══════════════════════════════════════════════════════════════╗
    ║           🕷️  DUKE2 ENHANCED MEDIA SCRAPER                  ║
    ║     Cloudflare Bypass | Gallery Viewer | Proxy Support      ║
    ╚══════════════════════════════════════════════════════════════╝
    
    Features:
    • TLS fingerprint impersonation (bypass Cloudflare & anti-bot)
    • Gallery viewer (interactive HTML gallery of downloads)
    • External viewer support (open files in system apps)
    • Smart retry with exponential backoff
    • Proxy support for IP rotation
    • Enhanced media extraction (lazy-load, locked content)
    • Session cookie persistence
    """)
    
    # URL input
    url = input("🔗 Enter starting URL: ").strip()
    if not url.startswith(('http://', 'https://')):
        url = 'https://' + url
    
    # Media type selection
    print("\n🎯 Choose media types (comma separated):")
    print("1. Images\n2. Videos\n3. Audio\n4. Documents\n5. Archives\n6. eBooks\n7. All")
    choice = input("➡️ Enter choice(s): ").strip()
    
    media_map = {
        '1': 'images', '2': 'videos', '3': 'audio',
        '4': 'documents', '5': 'archives', '6': 'ebooks'
    }
    
    if '7' in choice:
        media_types = list(MEDIA_EXTENSIONS.keys())
    else:
        media_types = [media_map[c.strip()] for c in choice.split(',') if c.strip() in media_map]
    
    # Scraping parameters
    min_size_kb = int(input("\n📏 Min file size in KB (0 for none): ") or 0)
    max_depth = int(input("📚 Max crawl depth (default 2): ") or 2)
    max_pages = int(input("📄 Max pages to crawl (default 100): ") or 100)
    same_domain = input("🌐 Stay in same domain? (y/n, default y): ").lower() != 'n'
    
    # Proxy configuration
    proxy = input("\n🌐 Proxy URL (optional, e.g., http://user:pass@host:port): ").strip()
    proxy = proxy if proxy else None
    
    # Cloudflare bypass options
    print("\n🛡️ Cloudflare bypass engine:")
    print("1. Auto (best available)")
    print("2. curl_cffi (TLS impersonation - fastest)")
    print("3. cloudscraper (JS challenge solver)")
    print("4. Standard requests (no bypass)")
    bypass_choice = input("➡️ Select (default 1): ").strip() or "1"
    
    # External viewer option
    print("\n📱 External viewer:")
    print("1. No - download only")
    print("2. Yes - open each file after download (Android: termux-open)")
    viewer_choice = input("➡️ Select (default 1): ").strip() or "1"
    use_external_viewer = (viewer_choice == "2")
    
    # Per-type limits
    max_per_type = {}
    for mtype in media_types:
        n = input(f"\n🛡️ Max {mtype} to download (0 = no limit): ")
        max_per_type[mtype] = int(n or 0)
    
    # Initialize session
    print("\n" + "=" * 60)
    init_session(proxy=proxy)
    
    # Set save directory
    save_dir = "/sdcard/Download/Duke2"
    os.makedirs(save_dir, exist_ok=True)
    
    # Start crawl
    print("\n🚀 Starting crawl...")
    crawl(url, media_types, save_dir, min_size_kb, max_depth, max_pages, same_domain)
    
    # Download
    print(f"\n⬇️ Downloading {download_queue.qsize()} files with {MAX_THREADS} threads...")
    threads = []
    for _ in range(min(MAX_THREADS, download_queue.qsize())):
        t = threading.Thread(
            target=download_worker, 
            args=(save_dir, min_size_kb, max_per_type, use_external_viewer)
        )
        t.start()
        threads.append(t)
    
    for t in threads:
        t.join()
    
    # Save results and generate gallery
    print("\n💾 Saving results...")
    gallery_path = save_results(save_dir)
    
    # Summary
    print("\n" + "=" * 60)
    print("✅ SCRAPE COMPLETE!")
    print(f"📁 Save location: {save_dir}")
    print(f"📦 Total downloaded: {len(downloaded_media)} files")
    print(f"📊 Total size: {sum(i['size_kb'] for i in downloaded_media) // 1024} MB")
    
    counts = {}
    for item in downloaded_media:
        counts[item['type']] = counts.get(item['type'], 0) + 1
    print("📊 Breakdown:", counts)
    
    if gallery_path:
        print(f"\n🖼️ Gallery: {gallery_path}")
        open_gal = input("Open gallery now? (y/n): ").lower()
        if open_gal == 'y':
            open_gallery_in_browser(gallery_path)
    
    # Save as ZIP option
    zip_path = os.path.join(save_dir, "media.zip")
    if os.path.exists(zip_path):
        print(f"📦 ZIP archive: {zip_path}")
    
    print("\n💡 Tip: Re-run to use saved cookies for authenticated sites!")


if __name__ == "__main__":
    main()
