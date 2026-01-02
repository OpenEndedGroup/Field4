#!/usr/bin/env python3
"""
Simple Flask server for hosting metadata.edn files.

This server provides three endpoints:
- GET /list : Returns a JSON list of available metadata.edn files
- GET /download/<filename> : Downloads a specific metadata.edn file
- GET /telemetry : Returns telemetry data about server usage

The server looks for metadata.edn files in the 'metadata_store' directory,
where each subdirectory represents a template/document that can be imported.

Telemetry tracks:
- Number of times /list endpoint is queried
- Number of times each template is downloaded
"""

from flask import Flask, jsonify, send_file, abort
from flask_cors import CORS
import os
import json
from pathlib import Path
from datetime import datetime
from collections import defaultdict
from threading import Lock

app = Flask(__name__)
CORS(app)  # Enable CORS for all routes to allow Field frontend to access

# Directory where metadata.edn files are stored
METADATA_DIR = Path(__file__).parent / "metadata_store"
METADATA_DIR.mkdir(exist_ok=True)

# Telemetry tracking
TELEMETRY_FILE = Path(__file__).parent / "metadata_server_telemetry.json"
telemetry_lock = Lock()

# Initialize telemetry with regular dict - defaultdict will be set up after loading
telemetry_data = {
    "list_queries": 0,
    "downloads": {},  # Will become defaultdict(int) after load_telemetry()
    "last_updated": None
}


def load_telemetry():
    """Load telemetry data from file if it exists."""
    global telemetry_data
    if TELEMETRY_FILE.exists():
        try:
            with open(TELEMETRY_FILE, 'r') as f:
                data = json.load(f)
                telemetry_data["list_queries"] = data.get("list_queries", 0)
                telemetry_data["downloads"] = defaultdict(int, data.get("downloads", {}))
                telemetry_data["last_updated"] = data.get("last_updated")
        except Exception as e:
            print(f"Warning: Could not load telemetry data: {e}")
    else:
        # Initialize with defaultdict for automatic key creation
        telemetry_data["downloads"] = defaultdict(int)


def save_telemetry():
    """Save telemetry data to file."""
    with telemetry_lock:
        try:
            telemetry_data["last_updated"] = datetime.now().isoformat()
            with open(TELEMETRY_FILE, 'w') as f:
                # Convert defaultdict to regular dict for JSON serialization
                data_to_save = {
                    "list_queries": telemetry_data["list_queries"],
                    "downloads": dict(telemetry_data["downloads"]),
                    "last_updated": telemetry_data["last_updated"]
                }
                json.dump(data_to_save, f, indent=2)
        except Exception as e:
            print(f"Warning: Could not save telemetry data: {e}")


def increment_list_queries():
    """Increment the count of list queries."""
    with telemetry_lock:
        telemetry_data["list_queries"] += 1
    save_telemetry()


def increment_download(template_name):
    """Increment the download count for a specific template."""
    with telemetry_lock:
        telemetry_data["downloads"][template_name] += 1
    save_telemetry()


@app.route('/list', methods=['GET'])
def list_files():
    """
    List all available metadata.edn files.
    
    Returns a JSON array of objects containing:
    - name: The directory name (identifier for the template)
    - has_edn: Whether metadata.edn exists
    """
    increment_list_queries()
    
    entries = []
    
    if not METADATA_DIR.exists():
        return jsonify(entries)
    
    for item in METADATA_DIR.iterdir():
        if item.is_dir():
            edn_file = item / "metadata.edn"
            if edn_file.exists():
                entries.append({
                    "name": item.name,
                    "has_edn": True
                })
    
    return jsonify(entries)


@app.route('/download/<path:filename>', methods=['GET'])
def download_file(filename):
    """
    Download a metadata.edn file from a specific template directory.
    
    Args:
        filename: The directory name containing the metadata.edn file
    
    Returns:
        The metadata.edn file for download
    """
    # Sanitize the filename to prevent directory traversal
    safe_filename = Path(filename).name
    target_dir = METADATA_DIR / safe_filename
    target_file = target_dir / "metadata.edn"
    
    if not target_file.exists() or not target_file.is_file():
        abort(404, description=f"File not found: {filename}/metadata.edn")
    
    increment_download(safe_filename)
    
    return send_file(target_file, as_attachment=True, download_name="metadata.edn")


@app.route('/telemetry', methods=['GET'])
def get_telemetry():
    """
    Get telemetry data about server usage.
    
    Returns a JSON object containing:
    - list_queries: Number of times /list endpoint was queried
    - downloads: Object mapping template names to download counts
    - last_updated: ISO timestamp of last telemetry update
    """
    with telemetry_lock:
        return jsonify({
            "list_queries": telemetry_data["list_queries"],
            "downloads": dict(telemetry_data["downloads"]),
            "last_updated": telemetry_data["last_updated"]
        })


if __name__ == '__main__':
    # Load existing telemetry data
    load_telemetry()
    
    # Allow port to be configured via environment variable
    port = int(os.environ.get('METADATA_SERVER_PORT', 5001))
    # Allow debug mode to be configured via environment variable
    debug_mode = os.environ.get('METADATA_SERVER_DEBUG', 'true').lower() == 'true'
    
    print(f"Metadata server starting...")
    print(f"Storing files in: {METADATA_DIR.absolute()}")
    print(f"Telemetry file: {TELEMETRY_FILE.absolute()}")
    print(f"Available at: http://localhost:{port}")
    print(f"  - List files: http://localhost:{port}/list")
    print(f"  - Download: http://localhost:{port}/download/<template-name>")
    print(f"  - Telemetry: http://localhost:{port}/telemetry")
    if debug_mode:
        print(f"  - Debug mode: enabled (set METADATA_SERVER_DEBUG=false to disable)")
    app.run(host='0.0.0.0', port=port, debug=debug_mode)
