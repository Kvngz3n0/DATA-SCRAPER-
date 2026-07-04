import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("duke2_enhanced", ROOT / "Duke2_Enhanced.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class OnlyFansSupportTests(unittest.TestCase):
    def test_build_onlyfans_headers_includes_cookie_and_auth_fields(self):
        headers = MODULE.build_onlyfans_headers(
            sess="sess-value",
            auth_id="auth-id",
            auth_uid_="auth-uid",
            user_agent="Mozilla/5.0",
            app_token="app-token",
        )

        self.assertEqual(headers["cookie"], "sess=sess-value; auth_id=auth-id; auth_uid_=auth-uid")
        self.assertEqual(headers["app-token"], "app-token")
        self.assertEqual(headers["user-agent"], "Mozilla/5.0")


if __name__ == "__main__":
    unittest.main()
