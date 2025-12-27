#!/usr/bin/env python3
"""
Simple Flask server for hosting metadata.edn files.

This server provides two endpoints:
- GET /list : Returns a JSON list of available metadata.edn files
- GET /download/<filename> : Downloads a specific metadata.edn file

The server looks for metadata.edn files in the 'metadata_store' directory,
where each subdirectory represents a template/document that can be imported.
"""

from flask import Flask, jsonify, send_file, abort
from flask_cors import CORS
import os
from pathlib import Path

app = Flask(__name__)
CORS(app)  # Enable CORS for all routes to allow Field frontend to access

# Directory where metadata.edn files are stored
METADATA_DIR = Path(__file__).parent / "metadata_store"
METADATA_DIR.mkdir(exist_ok=True)


@app.route('/list', methods=['GET'])
def list_files():
    """
    List all available metadata.edn files.
    
    Returns a JSON array of objects containing:
    - name: The directory name (identifier for the template)
    - has_edn: Whether metadata.edn exists
    """
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
    
    return send_file(target_file, as_attachment=True, download_name="metadata.edn")


if __name__ == '__main__':
    # Allow port to be configured via environment variable
    port = int(os.environ.get('METADATA_SERVER_PORT', 5001))
    # Allow debug mode to be configured via environment variable
    debug_mode = os.environ.get('METADATA_SERVER_DEBUG', 'true').lower() == 'true'
    
    print(f"Metadata server starting...")
    print(f"Storing files in: {METADATA_DIR.absolute()}")
    print(f"Available at: http://localhost:{port}")
    print(f"  - List files: http://localhost:{port}/list")
    print(f"  - Download: http://localhost:{port}/download/<template-name>")
    if debug_mode:
        print(f"  - Debug mode: enabled (set METADATA_SERVER_DEBUG=false to disable)")
    app.run(host='0.0.0.0', port=port, debug=debug_mode)
