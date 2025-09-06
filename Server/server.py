from flask import Flask, request, jsonify
import subprocess, tempfile, os, re

app = Flask(__name__)

# Optional: set these to full paths if needed
KOTLINC = os.environ.get("KOTLINC", "kotlinc")
JAVA    = os.environ.get("JAVA", "java")
PYTHON  = os.environ.get("PYTHON", "python")

ERR_RE_KOTLIN = re.compile(r'^.*?:(\d+):(\d+): (error|warning): (.*)$')

def run_cmd(cmd, cwd=None, timeout=20):
    try:
        out = subprocess.check_output(cmd, stderr=subprocess.STDOUT, text=True, cwd=cwd, timeout=timeout)
        return True, out
    except subprocess.CalledProcessError as e:
        return False, e.output
    except Exception as e:
        return False, str(e)

@app.post("/compile")
def compile_and_run():
    data = request.get_json(force=True) or {}

    # ---- Accept BOTH schemas ----
    # New schema: {"lang": "python|java|kotlin", "code": "..."}
    # Old schema: {"language":"kotlin", "source":"...", "filename":"Main.kt"}
    lang = (data.get("lang") or data.get("language") or "kotlin").lower()
    code = data.get("code") or data.get("source") or ""

    # Fallback: detect from filename extension if provided
    filename = data.get("filename") or ""
    if not lang and filename.endswith(".py"): lang = "python"
    if not lang and filename.endswith(".java"): lang = "java"
    if not lang and filename.endswith(".kt"): lang = "kotlin"

    if not code.strip():
        return jsonify(success=False, output="No code provided.", errors=[])

    with tempfile.TemporaryDirectory() as tmp:
        if lang == "python":
            src = os.path.join(tmp, "main.py")
            with open(src, "w", encoding="utf-8") as f: f.write(code)
            ok, out = run_cmd([PYTHON, src])
            return jsonify(success=ok, output=out, errors=[])

        elif lang == "java":
            src = os.path.join(tmp, "Main.java")
            with open(src, "w", encoding="utf-8") as f: f.write(code)
            ok, out = run_cmd(["javac", src])
            if not ok:
                return jsonify(success=False, output=out, errors=[])
            ok, out = run_cmd([JAVA, "-cp", tmp, "Main"])
            return jsonify(success=ok, output=out, errors=[])

        elif lang == "kotlin":
            src = os.path.join(tmp, "Main.kt")
            with open(src, "w", encoding="utf-8") as f: f.write(code)
            jar = os.path.join(tmp, "out.jar")
            ok, out = run_cmd([KOTLINC, src, "-include-runtime", "-d", jar])
            if not ok:
                # Parse kotlinc diagnostics to line/col for your app
                errors = []
                for line in out.splitlines():
                    m = ERR_RE_KOTLIN.match(line.strip())
                    if m:
                        ln, col, kind, msg = m.groups()
                        errors.append({"line": int(ln), "col": int(col), "message": msg})
                return jsonify(success=False, output=out, errors=errors)
            ok, out = run_cmd([JAVA, "-jar", jar])
            return jsonify(success=ok, output=out, errors=[])

        else:
            return jsonify(success=False, output=f"Language '{lang}' not supported.", errors=[])

@app.get("/")
def root():
    return "OK"

if __name__ == "__main__":
    # pip install flask
    app.run(host="0.0.0.0", port=8123, debug=False)
