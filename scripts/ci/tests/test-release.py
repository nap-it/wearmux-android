#!/usr/bin/env python3
"""Offline tests for the CodeNap-driven release helper."""

import importlib.util
import json
import os
import pathlib
import tempfile
import unittest
from unittest import mock
from urllib.error import HTTPError, URLError


ROOT = pathlib.Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("release_helper", ROOT / "scripts/ci/release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)

COMMIT = "a" * 40
BASE = "https://code.nap.av.it.pt"


class FakeApi(object):
    def __init__(self, existing=None, release_status=404):
        self.calls = []
        self.existing = existing
        self.release_status = release_status
        self.state = {"release": existing}

    def __call__(self, base, token, method, path, payload=None):
        self.calls.append({"method": method, "path": path, "payload": payload, "token": token})
        if path.endswith("/repository/tags/v1.0.0"):
            return 200, json.dumps({"commit": {"id": COMMIT}}).encode("utf-8")
        if path.endswith("/releases/v1.0.0") and method == "GET":
            if self.release_status == 404:
                return 404, b""
            return self.release_status, json.dumps(self.existing).encode("utf-8")
        if path.endswith("/releases") and method == "POST":
            self.state["release"] = json.loads(json.dumps(payload))
            self.state["release"]["assets"] = {"links": []}
            self.existing = self.state["release"]
            return 201, json.dumps(self.state["release"]).encode("utf-8")
        if path.endswith("/releases/v1.0.0") and method == "PUT":
            self.existing.update(payload)
            return 200, json.dumps(self.existing).encode("utf-8")
        if "/assets/links/" in path and method == "PUT":
            link_id = int(path.rsplit("/", 1)[1])
            for link in self.existing["assets"]["links"]:
                if link.get("id") == link_id:
                    link.update(payload)
            return 200, json.dumps(payload).encode("utf-8")
        if "/assets/links" in path and method == "POST":
            self.existing["assets"]["links"].append(payload)
            return 201, json.dumps(payload).encode("utf-8")
        raise AssertionError("unexpected API call: {0} {1}".format(method, path))


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.notes_directory = tempfile.TemporaryDirectory()
        notes_path = pathlib.Path(self.notes_directory.name)
        (notes_path / "v1.0.0.md").write_text("v1 notes", encoding="utf-8")
        (notes_path / "v2.0.0.md").write_text("v2 notes", encoding="utf-8")
        self.notes_patch = mock.patch.object(release, "NOTES_DIR", self.notes_directory.name)
        self.notes_patch.start()
        self.environment = {
            "CI_JOB_TOKEN": "job-token-secret",
            "CI_API_V4_URL": BASE + "/api/v4",
            "CI_PROJECT_ID": "123",
            "CI_COMMIT_TAG": "v1.0.0",
            "CI_COMMIT_SHA": COMMIT,
        }

    def tearDown(self):
        self.notes_patch.stop()
        self.notes_directory.cleanup()

    def run_publish(self, fake, tag="v1.0.0", **overrides):
        values = dict(self.environment)
        values.update(overrides)
        with mock.patch.dict(os.environ, values, clear=False):
            with mock.patch.object(release, "api_request", side_effect=fake):
                with mock.patch.object(release, "local_tag_commit", return_value=COMMIT):
                    return release.publish(tag)

    def test_metadata_contains_title_notes_and_canonical_assets(self):
        result = release.metadata("v1.0.0")
        self.assertEqual(result["name"], "WearMux Android v1.0.0 — Initial research release")
        self.assertEqual(result["tag_name"], "v1.0.0")
        self.assertEqual(result["description"], "v1 notes")
        self.assertEqual([link["name"] for link in result["assets"]["links"]], [
            "wearmux-phone-v1.0.0.apk", "wearmux-wear-v1.0.0.apk", "SHA256SUMS"])
        self.assertTrue(all(link["url"].startswith("https://github.com/nap-it/wearmux-android/releases/download/v1.0.0/") for link in result["assets"]["links"]))

    def test_metadata_selects_matching_local_version_file(self):
        self.assertEqual(release.metadata("v1.0.0")["description"], "v1 notes")
        self.assertEqual(release.metadata("v2.0.0")["description"], "v2 notes")

    def test_missing_or_empty_local_notes_fail(self):
        path = pathlib.Path(self.notes_directory.name) / "v3.0.0.md"
        with self.assertRaisesRegex(release.ReleaseError, "missing release notes file"):
            release.metadata("v3.0.0")
        path.write_text("", encoding="utf-8")
        with self.assertRaisesRegex(release.ReleaseError, "is empty"):
            release.metadata("v3.0.0")

    def test_future_tag_reads_notes_from_selected_tag(self):
        with mock.patch.object(release.subprocess, "check_output", return_value=b"tag-specific notes\n") as command:
            result = release.metadata("v2.0.0", from_tag=True)
        self.assertEqual(result["description"], "tag-specific notes")
        self.assertEqual(command.call_args[0][0], ["git", "show", "refs/tags/v2.0.0:docs/releases/v2.0.0.md"])

    def test_future_tag_without_notes_fails_clearly(self):
        with mock.patch.object(release.subprocess, "check_output", side_effect=release.subprocess.CalledProcessError(128, "git")):
            with self.assertRaisesRegex(release.ReleaseError, "has no nonempty docs/releases/v2.0.0.md"):
                release.metadata("v2.0.0", from_tag=True)

    def test_create_uses_actual_payload_and_new_release_note(self):
        fake = FakeApi()
        payload = self.run_publish(fake)
        create = next(call for call in fake.calls if call["method"] == "POST" and call["path"].endswith("/releases"))
        self.assertEqual(create["payload"]["name"], payload["name"])
        self.assertEqual(create["payload"]["tag_name"], "v1.0.0")
        self.assertIn("Signed APKs and checksums are hosted on GitHub.", create["payload"]["description"])
        self.assertNotIn("assets", create["payload"])
        link_payloads = [call["payload"] for call in fake.calls if "/assets/links" in call["path"]]
        self.assertEqual(len(link_payloads), 3)
        self.assertEqual([link["name"] for link in link_payloads], ["wearmux-phone-v1.0.0.apk", "wearmux-wear-v1.0.0.apk", "SHA256SUMS"])
        self.assertTrue(all(call["token"] == "job-token-secret" for call in fake.calls))

    def test_update_preserves_unrelated_and_replaces_old_owned_link(self):
        unrelated = {"id": 99, "name": "docs", "url": "https://example.invalid/docs"}
        old_phone = {"id": 7, "name": "wearmux-phone-v1.0.0.apk", "url": "https://example.invalid/old.apk"}
        exact_wear = {"id": 8, "name": "wearmux-wear-v1.0.0.apk", "url": "https://github.com/nap-it/wearmux-android/releases/download/v1.0.0/wearmux-wear-v1.0.0.apk"}
        sums = {"id": 9, "name": "SHA256SUMS", "url": "https://github.com/nap-it/wearmux-android/releases/download/v1.0.0/SHA256SUMS"}
        existing = {"name": "old", "tag_name": "v1.0.0", "description": "old", "assets": {"links": [unrelated, old_phone, exact_wear, sums]}}
        fake = FakeApi(existing=existing, release_status=200)
        self.run_publish(fake)
        self.assertEqual(existing["name"], "WearMux Android v1.0.0 — Initial research release")
        self.assertIn("Signed APKs and checksums are hosted on GitHub.", existing["description"])
        self.assertEqual(next(link for link in existing["assets"]["links"] if link["id"] == 99), unrelated)
        self.assertEqual(next(link for link in existing["assets"]["links"] if link["id"] == 7)["url"], "https://github.com/nap-it/wearmux-android/releases/download/v1.0.0/wearmux-phone-v1.0.0.apk")
        self.assertEqual(len([call for call in fake.calls if "/assets/links" in call["path"]]), 1)

    def test_repeat_publish_is_idempotent_with_stored_links(self):
        first = FakeApi()
        self.run_publish(first)
        existing = first.state["release"]
        first_metadata = json.loads(json.dumps(existing))
        second = FakeApi(existing=existing, release_status=200)
        self.run_publish(second)
        self.assertEqual(len([call for call in second.calls if "/assets/links" in call["path"]]), 0)
        self.assertEqual(len(existing["assets"]["links"]), 3)
        self.assertEqual(existing["name"], first_metadata["name"])
        self.assertEqual(existing["description"], first_metadata["description"])
        self.assertEqual(existing["tag_name"], first_metadata["tag_name"])

    def test_existing_release_update_omits_new_only_note(self):
        existing = {"name": "old", "tag_name": "v1.0.0", "description": "old", "assets": {"links": []}}
        fake = FakeApi(existing=existing, release_status=200)
        self.run_publish(fake)
        update = next(call for call in fake.calls if call["method"] == "PUT" and call["path"].endswith("/releases/v1.0.0"))
        self.assertIn("Signed APKs and checksums are hosted on GitHub.", update["payload"]["description"])

    def test_http_errors_do_not_create(self):
        for status in (401, 500):
            fake = FakeApi(existing=None, release_status=status)
            with self.assertRaises(release.ReleaseError):
                self.run_publish(fake)
            self.assertFalse(any(call["method"] in ("POST", "PUT") for call in fake.calls))

    def test_invalid_tag_missing_token_and_mismatch_do_not_write(self):
        with self.assertRaises(release.ReleaseError):
            release.metadata("1.0.0")
        fake = FakeApi()
        with self.assertRaises(release.ReleaseError):
            self.run_publish(fake, tag="bad")
        with mock.patch.dict(os.environ, {"CI_JOB_TOKEN": "", "CI_API_V4_URL": BASE + "/api/v4", "CI_PROJECT_ID": "123"}, clear=False):
            with self.assertRaises(release.ReleaseError):
                release.publish("v1.0.0")
        mismatch = FakeApi()
        with self.assertRaises(release.ReleaseError):
            self.run_publish(mismatch, CI_COMMIT_SHA="b" * 40)
        self.assertFalse(any(call["method"] in ("POST", "PUT") for call in mismatch.calls))

    def test_invalid_url_and_project_id_rejected(self):
        for base in ("http://code.nap.av.it.pt/api/v4", "https://code.nap.av.it.pt.evil/api/v4"):
            with self.assertRaises(release.ReleaseError):
                self.run_publish(FakeApi(), CI_API_V4_URL=base)
        with self.assertRaises(release.ReleaseError):
            self.run_publish(FakeApi(), CI_PROJECT_ID="group/project")

    def test_manual_backfill_skips_ci_commit_match(self):
        fake = FakeApi()
        self.run_publish(fake, CI_COMMIT_TAG="", CI_COMMIT_SHA="different")
        self.assertTrue(any(call["method"] == "POST" for call in fake.calls))

    def test_missing_local_tag_has_fetch_instruction(self):
        with mock.patch.object(release.subprocess, "check_output", side_effect=release.subprocess.CalledProcessError(128, "git")):
            with self.assertRaisesRegex(release.ReleaseError, "tags are fetched"):
                release.local_tag_commit("v1.0.0")

    def test_transport_request_is_bounded(self):
        captured = {}

        def failing_urlopen(request, timeout=None):
            captured["timeout"] = timeout
            captured["headers"] = dict((key.lower(), value) for key, value in request.headers.items())
            raise URLError("offline")

        with mock.patch.object(release.API_OPENER, "open", side_effect=failing_urlopen):
            with self.assertRaises(release.ReleaseError):
                release.api_request(BASE, "job-token-secret", "GET", "/api/v4/projects/123/repository/tags/v1.0.0")
        self.assertEqual(captured["timeout"], release.TIMEOUT_SECONDS)
        self.assertEqual(captured["headers"]["job-token"], "job-token-secret")
        self.assertLessEqual(release.TIMEOUT_SECONDS, 30)

    def test_http_error_body_is_discarded_and_redirects_are_refused(self):
        error = HTTPError("https://code.nap.av.it.pt/api/v4", 500, "server", {}, None)
        error.read = lambda: b"job-token-secret"
        with mock.patch.object(release.API_OPENER, "open", side_effect=error):
            status, body = release.api_request(BASE, "job-token-secret", "GET", "/api/v4")
        self.assertEqual(status, 500)
        self.assertEqual(body, b"")
        with self.assertRaises(HTTPError):
            release.NoRedirectHandler().redirect_request(release.Request("https://code.nap.av.it.pt/api/v4"), None, 302, "redirect", {}, "http://evil.invalid")


if __name__ == "__main__":
    unittest.main()
