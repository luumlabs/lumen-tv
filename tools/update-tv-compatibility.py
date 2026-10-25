#!/usr/bin/env python3
"""Propose README updates (English and Spanish) from structured GitHub TV compatibility issues.

Only Issue Form fields are parsed. Free-form notes are intentionally excluded.
Output is a *community self-report* table, NEVER a maintainer-verified verdict.
GitHub Actions opens a PR for review rather than committing to main.
"""
import html
import json
import os
import re
import urllib.request
from pathlib import Path

REPO = os.environ.get("GITHUB_REPOSITORY", "luumlabs/lumen-tv")
BASE = "https://api.github.com/repos/" + REPO
START = "<!-- TV_COMPATIBILITY_AUTO_START -->"
END = "<!-- TV_COMPATIBILITY_AUTO_END -->"
# Issue Form headings, current first; the bilingual Chinese ones come from forms filed before the
# form was translated, and still parse.
KEYS = {
    "brand": ("Brand / Marca", "品牌 / Brand"),
    "model": ("Model / Modelo", "型号 / Model"),
    "android": ("Android version / Versión de Android", "Android 版本 / Android version"),
    "version": ("Lumen version / Versión de Lumen", "Lumen 版本 / Lumen version"),
    "apk": ("APK type / Tipo de APK", "安装包 / APK type"),
    "outcome": ("Installation result / Resultado de la instalación", "安装结果 / Installation result"),
}
# Each value is (Spanish, English), matching the `english` flag of build() as an index.
OUTCOMES = {
    "Installed and launches": ("Instalado y se abre (informado)", "Installed and launched (reported)"),
    "Installed but crashes": ("Instalado pero se cierra (informado)", "Installed but crashes (reported)"),
    "Installation rejected": ("Instalación rechazada (informado)", "Install rejected (reported)"),
    "Not tested": ("Sin probar", "Not tested"),
}
APKS = {
    "Standard": ("Estándar", "Standard"),
    "Guard": ("Guard", "Guard"),
    "Custom fork": ("Fork propio", "Custom fork"),
}


def request_issues():
    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        raise RuntimeError("GITHUB_TOKEN missing: refusing to overwrite the table")
    output = []
    page = 1
    while True:
        req = urllib.request.Request(
            BASE + f"/issues?state=all&per_page=100&page={page}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": "Bearer " + token,
                "User-Agent": "Lumen-tv-compat-readme-sync",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        with urllib.request.urlopen(req, timeout=25) as resp:
            batch = json.load(resp)
        if not isinstance(batch, list):
            raise RuntimeError("Unexpected GitHub Issues response")
        output.extend(i for i in batch if "pull_request" not in i)
        if len(batch) < 100:
            break
        page += 1
    return output


def form_fields(body):
    # Only matching GitHub Issue Form headings are used.
    fields = {}
    for title, value in re.findall(r"(?ms)^### ([^\n]+)\n(.*?)(?=^### |\Z)", (body or "").replace("\r\n", "\n")):
        fields[title.strip()] = value.strip()
    values = {key: next((fields[l] for l in labels if fields.get(l)), "") for key, labels in KEYS.items()}
    if any(not val or val in {"_No response_", "No response"} for val in values.values()):
        return None
    outcome = values["outcome"].split(" / ", 1)[0]
    apk = values["apk"].split(" / ", 1)[0]
    if outcome not in OUTCOMES or apk not in APKS:
        return None
    values["outcome"] = outcome
    values["apk"] = apk
    return values


def clean(value, limit=70):
    # Never render arbitrary user-supplied Markdown/HTML as links or table markup.
    value = re.sub(r"[\x00-\x1f\x7f-\x9f]", " ", str(value))
    value = re.sub(r"\s+", " ", value).strip()[:limit]
    value = html.escape(value, quote=False)
    return value.replace("|", "&#124;").replace("[", "&#91;").replace("]", "&#93;").replace("`", "&#96;")


def build(issues, english):
    rows = []
    for issue in issues:
        # GitHub's Issue Form does not force a title prefix or attach a label.
        # A complete, valid structured form is sufficient, even for custom titles.
        record = form_fields(issue.get("body"))
        if not record:
            continue
        no = issue["number"]
        locale = 1 if english else 0
        device = clean(record["brand"]) + " " + clean(record["model"])
        platform = clean(record["android"])
        version = clean(record["version"]) + " / " + APKS[record["apk"]][locale]
        outcome = OUTCOMES[record["outcome"]][locale]
        # This is GitHub issue state, not evidence that the bug has been fixed.
        issue_state = (("Cerrado (arreglo sin verificar)", "Closed (fix unverified)") if
                       issue.get("state") == "closed" else ("Abierto", "Open"))[locale]
        link = f"[#{no}](https://github.com/{REPO}/issues/{no})"
        rows.append((no, f"| {device} | {platform} | {version} | {outcome} | {issue_state} | {link} |"))
    rows.sort(key=lambda item: item[0], reverse=True)
    if not rows:
        return ("No structured community reports yet. [Submit a report](https://github.com/"
                + REPO + "/issues/new/choose)."
                if english else
                "Todavía no hay informes de la comunidad. [Envía un informe](https://github.com/"
                + REPO + "/issues/new/choose).")
    header = ("| TV model | Android | Lumen / APK | Installation result | Issue state | Source |\n"
              "|---|---|---|---|---|---|" if english else
              "| Modelo de TV | Android | Lumen / APK | Resultado de la instalación | Estado | Fuente |\n"
              "|---|---|---|---|---|---|")
    return header + "\n" + "\n".join(row for _, row in rows)


def update(path, content):
    original = Path(path).read_text(encoding="utf-8")
    if original.count(START) != 1 or original.count(END) != 1:
        raise RuntimeError(f"{path}: missing or duplicated markers")
    a = original.index(START) + len(START)
    b = original.index(END)
    if a > b:
        raise RuntimeError(f"{path}: markers out of order")
    result = original[:a] + "\n" + content + "\n" + original[b:]
    if result != original:
        Path(path).write_text(result, encoding="utf-8")
        print(f"Updated {path}")
    else:
        print(f"No change: {path}")


if __name__ == "__main__":
    issues = request_issues()
    for path, lang in (("README.md", True), ("README.es.md", False)):
        update(path, build(issues, lang))
