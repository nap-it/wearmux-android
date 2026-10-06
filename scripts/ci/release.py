#!/usr/bin/env python3
"""Create release metadata and publish it as a CodeNap GitLab release.

The codenap command links to signed APK downloads hosted on GitHub. The links
become available after the GitHub release is published. It never downloads,
rebuilds, or signs release artifacts.
"""

from __future__ import print_function

import argparse
import json
import os
import re
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener


ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
NOTES_DIR = os.path.join(ROOT, "docs", "releases")
TAG_PATTERN = re.compile(r"^v[0-9]+\.[0-9]+\.[0-9]+\Z")
REPOSITORY = "nap-it/wearmux-android"
PHONE_TEMPLATE = "wearmux-phone-{0}.apk"
WEAR_TEMPLATE = "wearmux-wear-{0}.apk"
TIMEOUT_SECONDS = 20
NEW_RELEASE_NOTE = "Signed APKs and checksums are hosted on GitHub. Download links work once the corresponding GitHub release is published."


class ReleaseError(Exception):
    pass


class NoRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise HTTPError(request.full_url, code, "redirect refused", headers, None)


API_OPENER = build_opener(NoRedirectHandler())


def fail(message):
    print("release: {0}".format(message), file=sys.stderr)
    return 1


def validate_tag(tag):
    if not TAG_PATTERN.match(tag or ""):
        raise ReleaseError("tag must match vMAJOR.MINOR.PATCH")


def release_title(tag):
    if tag == "v1.0.0":
        return "WearMux Android {0} — Initial research release".format(tag)
    return "WearMux Android {0} — Research release".format(tag)


def asset_links(tag):
    base = "https://github.com/{0}/releases/download/{1}/".format(REPOSITORY, tag)
    names = [PHONE_TEMPLATE.format(tag), WEAR_TEMPLATE.format(tag), "SHA256SUMS"]
    return [{"name": name, "url": base + name, "link_type": "other"} for name in names]


def notes_for_tag(tag, from_tag=False):
    notes_path = os.path.join(NOTES_DIR, "{0}.md".format(tag))
    if not from_tag:
        try:
            with open(notes_path, "r", encoding="utf-8") as notes_file:
                description = notes_file.read().strip()
        except (IOError, OSError):
            raise ReleaseError("missing release notes file {0}".format(notes_path))
        if not description:
            raise ReleaseError("release notes file {0} is empty".format(notes_path))
        return description

    source = "refs/tags/{0}:docs/releases/{0}.md".format(tag)
    try:
        description = subprocess.check_output(
            ["git", "show", source], cwd=ROOT, stderr=subprocess.PIPE
        ).decode("utf-8").strip()
        if description:
            return description
    except (OSError, subprocess.CalledProcessError, UnicodeDecodeError):
        pass

    # v1.0.0 predates versioned notes in the tagged snapshot. Its matching
    # file in the current checkout is the only permitted compatibility fallback.
    if tag == "v1.0.0":
        try:
            with open(notes_path, "r", encoding="utf-8") as notes_file:
                description = notes_file.read().strip()
        except (IOError, OSError):
            description = ""
        if description:
            return description
    raise ReleaseError("tag {0} has no nonempty docs/releases/{0}.md; add the versioned release notes before publishing".format(tag))


def metadata(tag, include_new_note=False, from_tag=False):
    validate_tag(tag)
    description = notes_for_tag(tag, from_tag=from_tag)
    if include_new_note:
        description += "\n\n> " + NEW_RELEASE_NOTE
    return {
        "name": release_title(tag),
        "tag_name": tag,
        "description": description,
        "assets": {"links": asset_links(tag)},
    }


def api_url(base, path):
    parsed = urlparse(base)
    if parsed.scheme != "https":
        raise ReleaseError("CI_API_V4_URL must use HTTPS")
    return base.rstrip("/") + path


def api_request(base, token, method, path, payload=None):
    body = None
    headers = {"Accept": "application/json", "JOB-TOKEN": token}
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = Request(api_url(base, path), data=body, headers=headers, method=method)
    try:
        response = API_OPENER.open(request, timeout=TIMEOUT_SECONDS)
        return response.getcode(), response.read()
    except HTTPError as error:
        # Read and discard the body. It may contain server details, and must
        # never be copied into CI logs where it could include sensitive data.
        try:
            error.read()
        except Exception:
            pass
        return error.code, b""
    except (URLError, OSError):
        raise ReleaseError("CodeNap API request failed")


def decode_json(body, context):
    try:
        return json.loads(body.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        raise ReleaseError("CodeNap returned invalid JSON for {0}".format(context))


def project_path(project_id):
    if not re.match(r"^[0-9]+\Z", project_id or ""):
        raise ReleaseError("CI_PROJECT_ID must be numeric")
    return "/projects/{0}".format(project_id)


def local_tag_commit(tag):
    ref = "refs/tags/{0}^{{commit}}".format(tag)
    try:
        commit = subprocess.check_output(
            ["git", "rev-parse", "--verify", ref], cwd=ROOT, stderr=subprocess.PIPE
        ).decode("ascii").strip()
    except (OSError, subprocess.CalledProcessError):
        raise ReleaseError("tag {0} is not available locally; ensure tags are fetched and GIT_DEPTH=0 is used".format(tag))
    if not re.match(r"^[0-9a-fA-F]{40}\Z", commit):
        raise ReleaseError("local tag {0} did not resolve to a commit".format(tag))
    return commit


def verify_tag(tag, manual):
    commit = local_tag_commit(tag)
    if manual:
        return
    if os.environ.get("CI_COMMIT_TAG") != tag:
        raise ReleaseError("CI_COMMIT_TAG does not match RELEASE_TAG")
    if os.environ.get("CI_COMMIT_SHA") != commit:
        raise ReleaseError("CI_COMMIT_SHA does not match the local tag commit")


def publish(tag):
    validate_tag(tag)
    token = os.environ.get("CI_JOB_TOKEN")
    if not token:
        raise ReleaseError("CI_JOB_TOKEN is required")
    base = os.environ.get("CI_API_V4_URL")
    if not base:
        raise ReleaseError("CI_API_V4_URL is required")
    parsed_base = urlparse(base)
    if parsed_base.scheme != "https" or parsed_base.hostname != "code.nap.av.it.pt":
        raise ReleaseError("CI_API_V4_URL must point to CodeNap over HTTPS")
    project_id = os.environ.get("CI_PROJECT_ID")
    project = project_path(project_id)
    manual = not bool(os.environ.get("CI_COMMIT_TAG"))
    verify_tag(tag, manual)
    path_tag = quote(tag, safe="")
    release_path = project + "/releases/" + path_tag
    status, body = api_request(base, token, "GET", release_path)
    if status == 404:
        current = None
    elif status == 200:
        current = decode_json(body, "release")
    else:
        raise ReleaseError("could not inspect CodeNap release (HTTP {0})".format(status))

    payload = metadata(tag, include_new_note=True, from_tag=True)
    if current is None:
        release_payload = {
            "name": payload["name"],
            "tag_name": payload["tag_name"],
            "description": payload["description"],
        }
        status, _ = api_request(base, token, "POST", project + "/releases", release_payload)
        if status not in (200, 201):
            raise ReleaseError("could not create CodeNap release (HTTP {0})".format(status))
    else:
        status, _ = api_request(base, token, "PUT", release_path, {
            "name": payload["name"],
            "tag_name": payload["tag_name"],
            "description": payload["description"],
        })
        if status != 200:
            raise ReleaseError("could not update CodeNap release (HTTP {0})".format(status))

    links = ((current or {}).get("assets") or {}).get("links") or []
    for desired in payload["assets"]["links"]:
        exact = next((link for link in links if link.get("name") == desired["name"] and link.get("url") == desired["url"]), None)
        if exact is not None:
            continue
        old = next((link for link in links if link.get("name") == desired["name"]), None)
        if old is not None and old.get("id") is not None:
            link_path = release_path + "/assets/links/" + str(old["id"])
            status, _ = api_request(base, token, "PUT", link_path, desired)
            if status != 200:
                raise ReleaseError("could not update CodeNap asset link (HTTP {0})".format(status))
            links = [desired if link is old else link for link in links]
        else:
            status, _ = api_request(base, token, "POST", release_path + "/assets/links", desired)
            if status not in (200, 201):
                raise ReleaseError("could not create CodeNap asset link (HTTP {0})".format(status))
            links.append(desired)
    return payload


def main(argv):
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command")
    metadata_parser = subparsers.add_parser("metadata")
    metadata_parser.add_argument("--tag", required=True)
    codenap_parser = subparsers.add_parser("codenap")
    codenap_parser.add_argument("--tag", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "metadata":
            print(json.dumps(metadata(args.tag), sort_keys=True, ensure_ascii=True))
            return 0
        if args.command == "codenap":
            publish(args.tag)
            print("Published CodeNap release {0}".format(args.tag))
            return 0
        parser.error("a command is required")
    except (IOError, OSError) as error:
        return fail("could not read release notes")
    except ReleaseError as error:
        return fail(str(error))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
