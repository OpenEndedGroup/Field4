"""Alternate one edit in Main.kt and rebuild incrementally, N times; count the failed rebuilds.

usage: python toggle.py default|disabled   (run from this directory; needs a Gradle wrapper here,
e.g. copied from the Field checkout: cp -R ../../../gradle ../../../gradlew .)
"""
import subprocess, sys, pathlib, hashlib, re
mode = sys.argv[1]  # default | disabled
main = pathlib.Path("src/jsMain/kotlin/Main.kt")
base = main.read_text()
with_td = re.sub(r"\+ td([xy])(?!\.toDouble)\b", r"+ td\1.toDouble()", base)
without_td = with_td.replace("+ tdx.toDouble()", "+ tdx").replace("+ tdy.toDouble()", "+ tdy")
assert with_td != without_td
build = pathlib.Path("build.gradle.kts")
original = build.read_text()
text = original.replace(", \"-Xklib-ir-inliner=disabled\"", "")
if mode == "disabled":
    text = text.replace('freeCompilerArgs.addAll("-Xcollection-literals")', 'freeCompilerArgs.addAll("-Xcollection-literals", "-Xklib-ir-inliner=disabled")')
    assert "klib-ir-inliner=disabled" in text
build.write_text(text)
subprocess.run(["rm", "-rf", "build"])
main.write_text(with_td)
def gradle(tag):
    r = subprocess.run(["./gradlew", "--no-daemon", "compileKotlinJs"], capture_output=True, text=True)
    out = r.stdout + r.stderr
    sig = re.search(r"Cannot deserialize inline function|is unbound", out)
    executed = re.search(r"(\d+) actionable tasks?: (\d+) executed", out)
    print(f"{tag}: rc={r.returncode} {sig.group(0) if sig else ''} executed={executed.group(2) if executed else '?'}", flush=True)
    return r.returncode
gradle("clean build")
crashes = 0
for i in range(6):
    target = without_td if i % 2 == 0 else with_td
    before = hashlib.sha1(main.read_bytes()).hexdigest()
    main.write_text(target)
    assert hashlib.sha1(main.read_bytes()).hexdigest() != before, "edit did not change the file"
    crashes += gradle(f"edit {i+1} ({'remove' if i % 2 == 0 else 'restore'} .toDouble())") != 0
print(f"{mode}: {crashes}/6 incremental rebuilds failed")
build.write_text(original)
main.write_text(base)
