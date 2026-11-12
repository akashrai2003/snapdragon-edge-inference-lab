"""Protocol checks run without starting or stopping any phone services."""

import os
import subprocess
import unittest
from pathlib import Path

HANDLER = Path(__file__).with_name("controller_handler.sh")


class ControllerProtocolTest(unittest.TestCase):
    def request(self, method, path, headers=""):
        env = dict(os.environ, CUE_CONTROL_TOKEN="test-token")
        return subprocess.run(
            ["sh", str(HANDLER)],
            input=f"{method} {path} HTTP/1.1\r\n{headers}\r\n",
            text=True,
            capture_output=True,
            env=env,
            check=True,
        ).stdout

    def test_requires_post_for_mutations(self):
        for path in ["/start", "/stop"]:
            self.assertIn("405 Method Not Allowed", self.request("GET", path))

    def test_requires_matching_token(self):
        for headers in ["", "X-Cue-Control: wrong\r\n"]:
            self.assertIn("403 Forbidden", self.request("POST", "/start", headers))

    def test_blocks_browser_origin_even_with_token(self):
        self.assertIn(
            "403 Forbidden",
            self.request(
                "POST", "/start", "Origin: https://example.com\r\nX-Cue-Control: test-token\r\n"
            ),
        )

    def test_uses_exact_routes(self):
        for path in ["/start-extra", "/start?x=1", "/stop/anything"]:
            self.assertIn(
                "404 Not Found", self.request("POST", path, "X-Cue-Control: test-token\r\n")
            )

    def test_session_has_no_cors_and_is_not_cached(self):
        response = self.request("GET", "/session")
        self.assertIn('"token":"test-token"', response)
        self.assertIn("Cache-Control: no-store", response)
        self.assertNotIn("Access-Control-Allow-Origin", response)


if __name__ == "__main__":
    unittest.main()
