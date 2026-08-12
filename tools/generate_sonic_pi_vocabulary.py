#!/usr/bin/env python3
"""Generate the Sonic Pi vocabulary that the `#sonicpi` editor extension completes against.

The vocabulary is *extracted from the installed Sonic Pi*, never hand-maintained. Sonic Pi already
carries a complete, structured description of its own language: every `play`, `live_loop` and
`with_fx` is registered through `SonicPi::Lang::Support::DocSystem`, which keeps one shared registry
of names, summaries, argument lists, opts and examples, and `SynthInfo` does the same for synths, FX
and samples. Typing several hundred function signatures into a JavaScript file by hand would be a
transcription error waiting to happen and would silently rot on the next Sonic Pi release; asking the
app produces a vocabulary that is correct by construction and regenerates in a second.

The extraction runs a small Ruby program under Sonic Pi's *own bundled Ruby* -- the same interpreter
and load path `bin/qt-doc.rb` uses to build the GUI's help pane -- because these modules are only
loadable in that environment.

Usage:
    tools/generate_sonic_pi_vocabulary.py [--app <Sonic Pi.app>] [--out <path>] [--check]

`--check` regenerates into memory and diffs against the committed file, which is what CI wants: it
fails when the committed vocabulary no longer matches the installed Sonic Pi.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = REPO_ROOT / "src/commonMain/resources/sonicpi-vocabulary.json"
DEFAULT_APPS = [
    Path("/Applications/Sonic Pi.app"),
    Path.home() / "Applications/Sonic Pi.app",
]

# Ruby, not a Python regex over Ruby. The doc blocks are method calls evaluated at load time, so the
# only way to read them correctly -- including every `doc` added by a plugin or a newer release -- is
# to load the modules and ask.
DUMPER = r"""
root = ARGV[0]
ruby_root = File.join(root, "Contents", "Resources", "app", "server", "ruby")

$LOAD_PATH.unshift File.join(ruby_root, "lib")

require 'json'
require 'cgi'
require File.join(ruby_root, "core.rb")
require File.join(ruby_root, "paths.rb")
require File.join(ruby_root, "lib", "sonicpi", "util")
require File.join(ruby_root, "lib", "sonicpi", "synths", "synthinfo")
require File.join(ruby_root, "lib", "sonicpi", "runtime")
require File.join(ruby_root, "lib", "sonicpi", "lang", "core")
require File.join(ruby_root, "lib", "sonicpi", "lang", "sound")
require File.join(ruby_root, "lib", "sonicpi", "lang", "midi")
require File.join(ruby_root, "lib", "sonicpi", "note")
require File.join(ruby_root, "lib", "sonicpi", "chord")
require File.join(ruby_root, "lib", "sonicpi", "scale")

begin
  require File.join(ruby_root, "lib", "sonicpi", "lang", "western_theory")
rescue LoadError
end
begin
  require File.join(ruby_root, "lib", "sonicpi", "lang", "maths")
rescue LoadError
end

def first_paragraph(text)
  return "" if text.nil?
  # Doc bodies are markdown; the first paragraph is what fits in a completion popup.
  para = text.to_s.strip.split(/\n\s*\n/).first.to_s
  para.gsub(/\s+/, " ").strip
end

# DocSystem stores every module's docs in one shared class variable, so Core.docs is already the
# whole language. Merging the others is harmless and survives that ever changing.
registry = {}
mods = [SonicPi::Lang::Core, SonicPi::Lang::Sound]
mods << SonicPi::Lang::WesternTheory if defined?(SonicPi::Lang::WesternTheory)
mods << SonicPi::Lang::Maths if defined?(SonicPi::Lang::Maths)
mods << SonicPi::Lang::Midi if defined?(SonicPi::Lang::Midi)
mods.each do |mod|
  next unless mod.respond_to?(:docs)
  mod.docs.each { |name, info| registry[name.to_s] = info }
end

fns = []
registry.each do |name, info|
  next if info[:hide]
  args = (info[:args] || []).map do |a|
    n, t = *a
    { "name" => n.to_s, "type" => t.to_s }
  end
  opts = {}
  (info[:opts] || {}).each { |k, v| opts[k.to_s] = v.is_a?(String) ? v : v.inspect }
  fns << {
    "name" => name,
    "summary" => (info[:summary] || "").to_s.strip,
    "args" => args,
    "opts" => opts.keys.sort,
    "optDocs" => opts,
    "returns" => (info[:returns] || "").to_s,
    "acceptsBlock" => !!info[:accepts_block],
    "introduced" => (info[:introduced] ? info[:introduced].to_s : ""),
    "doc" => first_paragraph(info[:doc]),
    "examples" => (info[:examples] || []).first(2).map { |e| e.to_s.strip }
  }
end
fns.sort_by! { |f| f["name"] }

def synth_entries(map)
  out = []
  (map || {}).each do |name, info|
    next if name.to_s.start_with?("sound_in") && false
    out << {
      "name" => name.to_s,
      "summary" => (info.respond_to?(:doc) ? first_paragraph(info.doc) : ""),
      "opts" => (info.respond_to?(:arg_info) ? info.arg_info.keys.map(&:to_s).sort : [])
    }
  end
  out.sort_by { |s| s["name"] }
end

synths = synth_entries(SonicPi::Synths::SynthInfo.all_synths.each_with_object({}) { |n, h|
  h[n] = SonicPi::Synths::SynthInfo.get_info(n)
}) rescue []

fx = synth_entries(SonicPi::Synths::SynthInfo.all_fx.each_with_object({}) { |n, h|
  h[n] = SonicPi::Synths::SynthInfo.get_info("fx_#{n}")
}) rescue []

samples = begin
  groups = {}
  SonicPi::Synths::SynthInfo.grouped_samples.each do |group, info|
    groups[group.to_s] = {
      "desc" => (info[:desc] || "").to_s,
      "samples" => (info[:samples] || []).map(&:to_s).sort
    }
  end
  groups
rescue
  {}
end

puts JSON.pretty_generate({
  "fns" => fns,
  "synths" => synths,
  "fx" => fx,
  "sampleGroups" => samples
})
"""


def find_app(explicit: str | None) -> Path:
    if explicit:
        app = Path(explicit)
        if not app.exists():
            sys.exit(f"no Sonic Pi at {app}")
        return app
    for candidate in DEFAULT_APPS:
        if candidate.exists():
            return candidate
    sys.exit(
        "could not find Sonic Pi.app; pass --app. Looked in:\n"
        + "\n".join(f"  {c}" for c in DEFAULT_APPS)
    )


def app_version(app: Path) -> str:
    """The bundle's version.

    Read here rather than asked of Ruby: Sonic Pi's own version is assembled at boot from settings
    and an update-check path rather than exposed as a plain constant, and the bundle string is both
    stable and exactly what a reader wants to see.
    """
    plist = app / "Contents/Info.plist"
    if not plist.exists():
        return ""
    proc = subprocess.run(
        ["/usr/libexec/PlistBuddy", "-c", "Print :CFBundleShortVersionString", str(plist)],
        capture_output=True,
        text=True,
    )
    return proc.stdout.strip() if proc.returncode == 0 else ""


def extract(app: Path) -> dict:
    ruby = app / "Contents/Resources/app/server/native/ruby/bin/ruby"
    if not ruby.exists():
        sys.exit(f"no bundled ruby at {ruby}")
    proc = subprocess.run(
        [str(ruby), "-e", DUMPER, str(app)],
        capture_output=True,
        text=True,
        timeout=300,
    )
    if proc.returncode != 0:
        sys.exit(
            f"the Sonic Pi vocabulary dumper failed (exit {proc.returncode}).\n"
            f"--- stderr ---\n{proc.stderr}\n--- stdout ---\n{proc.stdout[:2000]}"
        )
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as e:
        sys.exit(f"dumper produced unparseable JSON ({e}).\nstdout head:\n{proc.stdout[:2000]}")


def render(data: dict) -> str:
    return json.dumps(data, indent=1, sort_keys=False) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", default=None, help="path to Sonic Pi.app")
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--check", action="store_true", help="fail if the committed file is stale")
    args = parser.parse_args()

    app = find_app(args.app)
    data = {"sonicPiVersion": app_version(app), **extract(app)}
    text = render(data)
    out = Path(args.out)

    counts = (
        f"{len(data['fns'])} fns, {len(data['synths'])} synths, {len(data['fx'])} fx, "
        f"{sum(len(g['samples']) for g in data['sampleGroups'].values())} samples "
        f"in {len(data['sampleGroups'])} groups"
    )

    if args.check:
        if not out.exists():
            print(f"MISSING: {out} has never been generated", file=sys.stderr)
            return 1
        if out.read_text() != text:
            print(
                f"STALE: {out} does not match the installed Sonic Pi "
                f"{data['sonicPiVersion']} ({counts}).\n"
                f"Regenerate with tools/generate_sonic_pi_vocabulary.py",
                file=sys.stderr,
            )
            return 1
        print(f"up to date: {out.name} matches Sonic Pi {data['sonicPiVersion']} ({counts})")
        return 0

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text)
    print(f"wrote {out} from Sonic Pi {data['sonicPiVersion']}: {counts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
