# Getting Started

## Run Locally

From the repo root:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r docs\requirements.txt
$env:PYTHONUTF8="1"
.\.venv\Scripts\mkdocs serve
```

Then open the URL shown in the terminal (usually `http://127.0.0.1:8000/`).

If you see a warning about MkDocs 2.0 compatibility, that's expected (the docs toolchain is pinned to MkDocs 1.x for now).

## Deploy to GitHub Pages

1. Push this repo to GitHub (or commit/push these files if it's already there).
2. In GitHub, open **Settings -> Pages**.
3. Under **Build and deployment**, set **Source** to **GitHub Actions**.
4. Push to the `1.21.1` (or `main`/`master`) branch to trigger the workflow.

The deployed site URL will show up in the workflow run summary and in **Settings -> Pages** once published.
