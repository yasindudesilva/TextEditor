from flask import Flask, request, jsonify
import subprocess, tempfile, os, re, uuid, time, logging
from colorama import init, Fore, Style



# --- Logging setup ---
init(autoreset=True)
logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("CompilerServer")
logging.getLogger("werkzeug").setLevel(logging.WARNING)

def pretty_log(stage, message):
    color = {
        "INFO": Fore.CYAN,
        "CMD": Fore.YELLOW,
        "ERROR": Fore.RED,
        "FAIL": Fore.RED,
        "OK": Fore.GREEN,
    }.get(stage, Fore.WHITE)
    logger.info(f"{color}[{stage}]{Style.RESET_ALL} {message}")

app = Flask(__name__)

# Optional: set these to full paths if needed
KOTLINC = os.environ.get("KOTLINC", "kotlinc.bat")
JAVA    = os.environ.get("JAVA", "java")
PYTHON  = os.environ.get("PYTHON", "python")

ERR_RE_KOTLIN = re.compile(r'^.*?:(\d+):(\d+): (error|warning): (.*)$')

def run_cmd(cmd, cwd=None, timeout=20):
    def short_cmd(cmd):
        # Only show the tool name (kotlinc, java, etc.) and action
        if isinstance(cmd, list) and cmd:
            return cmd[0] + (" (compiling)" if "kotlinc" in cmd[0] else (" (running)" if "java" in cmd[0] else ""))
        return str(cmd)
    try:
        out = subprocess.check_output(cmd, stderr=subprocess.STDOUT, text=True, cwd=cwd, timeout=timeout)
        pretty_log("CMD", f"Success: {short_cmd(cmd)}\nOutput:\n{out.strip()}")
        return True, out
    except subprocess.CalledProcessError as e:
        pretty_log("CMD", f"Error: {short_cmd(cmd)}\nOutput:\n{e.output.strip()}")
        return False, e.output
    except Exception as e:
        pretty_log("CMD", f"Exception: {short_cmd(cmd)}\nError: {str(e)}")
        return False, f"[run_cmd error] Command: {short_cmd(cmd)}\nError: {str(e)}"

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

    # Store temp files in the same directory as server.py for easier access
    base_dir = os.path.dirname(os.path.abspath(__file__))
    if lang == "python":
        src = os.path.join(base_dir, "main.py")
        with open(src, "w", encoding="utf-8") as f: f.write(code)
        ok, out = run_cmd([PYTHON, src])
        return jsonify(success=ok, output=out, errors=[])

    elif lang == "java":
        src = os.path.join(base_dir, "Main.java")
        with open(src, "w", encoding="utf-8") as f: f.write(code)
        ok, out = run_cmd(["javac", src])
        if not ok:
            return jsonify(success=False, output=out, errors=[])
        ok, out = run_cmd([JAVA, "-cp", base_dir, "Main"])
        return jsonify(success=ok, output=out, errors=[])

    elif lang == "kotlin":
        # Use unique filenames for each request
        req_id = str(uuid.uuid4())[:8]
        stamp = f"{int(time.time())}_{req_id}"
        src = os.path.join(base_dir, f"compile_{stamp}.kt")
        jar = os.path.join(base_dir, f"program_{stamp}.jar")
        with open(src, "w", encoding="utf-8") as f:
            f.write(code)
        print(end="")  # Add a blank line before each compile process, but not after each command
        pretty_log("INFO", "Compiling your code...")
        ok, out = run_cmd([KOTLINC, src, "-include-runtime", "-d", jar])
        if not ok:
            errors = []
            for line in out.splitlines():
                m = ERR_RE_KOTLIN.match(line.strip())
                if m:
                    ln, col, kind, msg = m.groups()
                    errors.append({"line": int(ln), "col": int(col), "message": msg})
            pretty_log("ERROR", "Compilation failed. Please check your code.")
            for p in (src, jar):
                try:
                    if os.path.exists(p): os.remove(p)
                except: pass
            return jsonify(success=False, output=out, errors=errors)
        pretty_log("INFO", "Compilation successful. Running your program...")
        ok2, out2 = run_cmd([JAVA, "-jar", jar])[:2]
        pretty_log("INFO", "Execution finished.")
        for p in (src, jar):
            try:
                if os.path.exists(p): os.remove(p)
            except: pass
        return jsonify(success=ok2, output=out2, errors=[])

    else:
        return jsonify(success=False, output=f"Language '{lang}' not supported.", errors=[])

@app.get("/")
def root():
    return "OK"

if __name__ == "__main__":
    # pip install flask
    app.run(host="0.0.0.0", port=8123, debug=False)
from flask import Flask, request, jsonify
import subprocess, tempfile, os, re

app = Flask(__name__)

# Optional: set these to full paths if needed
KOTLINC = os.environ.get("KOTLINC", "kotlinc.bat")
JAVA    = os.environ.get("JAVA", "java")
PYTHON  = os.environ.get("PYTHON", "python")

ERR_RE_KOTLIN = re.compile(r'^.*?:(\d+):(\d+): (error|warning): (.*)$')

def run_cmd(cmd, cwd=None, timeout=20):
    try:
        out = subprocess.check_output(cmd, stderr=subprocess.STDOUT, text=True, cwd=cwd, timeout=timeout)
        print(f"[run_cmd] Success: {cmd}\nOutput:\n{out}")
        return True, out
    except subprocess.CalledProcessError as e:
        print(f"[run_cmd] CalledProcessError: {cmd}\nOutput:\n{e.output}")
        return False, e.output
    except Exception as e:
        print(f"[run_cmd] Exception: {cmd}\nError: {str(e)}")
        return False, f"[run_cmd error] Command: {cmd}\nError: {str(e)}"

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

    # Store temp files in the same directory as server.py for easier access
    base_dir = os.path.dirname(os.path.abspath(__file__))
    if lang == "python":
        src = os.path.join(base_dir, "main.py")
        with open(src, "w", encoding="utf-8") as f: f.write(code)
        ok, out = run_cmd([PYTHON, src])
        return jsonify(success=ok, output=out, errors=[])

    elif lang == "java":
        src = os.path.join(base_dir, "Main.java")
        with open(src, "w", encoding="utf-8") as f: f.write(code)
        ok, out = run_cmd(["javac", src])
        if not ok:
            return jsonify(success=False, output=out, errors=[])
        ok, out = run_cmd([JAVA, "-cp", base_dir, "Main"])
        return jsonify(success=ok, output=out, errors=[])

    elif lang == "kotlin":
        src = os.path.join(base_dir, "Main.kt")
        with open(src, "w", encoding="utf-8") as f: f.write(code)
        # Just run kotlinc <filename> in the same dir
        ok, out = run_cmd([KOTLINC, src])
        if not ok:
            # Parse kotlinc diagnostics to line/col for your app
            errors = []
            for line in out.splitlines():
                m = ERR_RE_KOTLIN.match(line.strip())
                if m:
                    ln, col, kind, msg = m.groups()
                    errors.append({"line": int(ln), "col": int(col), "message": msg})
            return jsonify(success=False, output=out, errors=errors)
        return jsonify(success=True, output=out, errors=[])

    else:
        return jsonify(success=False, output=f"Language '{lang}' not supported.", errors=[])

@app.get("/")
def root():
    return "OK"

if __name__ == "__main__":
    # pip install flask
    app.run(host="0.0.0.0", port=8123, debug=False)
