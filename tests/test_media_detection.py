import importlib.util
import unittest
from pathlib import Path

from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("duke2_enhanced", ROOT / "Duke2_Enhanced.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MediaDetectionTests(unittest.TestCase):
    def test_extract_media_links_parses_script_json_media_urls(self):
        html = """
        <html><body>
          <script type="application/json">
            {"media": [{"url": "https://cdn.example.com/preview.jpg"}]}
          </script>
        </body></html>
        """

        links = MODULE.extract_media_links(BeautifulSoup(html, "html.parser"), "https://example.com", "images")
        self.assertIn("https://cdn.example.com/preview.jpg", links)


if __name__ == "__main__":
    unittest.main()
