#!/usr/bin/env python3
"""
Guide Chat Server - WebSocket-based chat server for Field4 slideshows.

This server provides real-time chat functionality for interactive guides/slideshows.
Students can post messages to individual slides, identified by slideshow_id + slide_number.

Endpoints:
- WebSocket /ws : Real-time chat message posting and subscription
- GET /chat/<slideshow_id>/<slide_number> : Retrieve chat history for a slide
- POST /chat/<slideshow_id>/<slide_number> : Post a message to a slide (alternative to WebSocket)

Message format (WebSocket):
- Subscribe: {"action": "subscribe", "slideshow_id": "xxx", "slide_number": 1}
- Post: {"action": "post", "slideshow_id": "xxx", "slide_number": 1, "username": "student1", "message": "text"}
- Broadcast: {"action": "message", "slideshow_id": "xxx", "slide_number": 1, "username": "student1", "message": "text", "timestamp": "..."}

Storage:
- Messages are stored in JSON files under chat_data/<slideshow_id>/<slide_number>.json
- Each message includes: username, message, timestamp
"""

from flask import Flask, jsonify, request, abort
from flask_cors import CORS
from flask_socketio import SocketIO, emit, join_room, leave_room
import os
import json
from pathlib import Path
from datetime import datetime, timezone
from threading import Lock

app = Flask(__name__)
CORS(app)  # Enable CORS for all routes
socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')

# Directory where chat messages are stored
CHAT_DATA_DIR = Path(__file__).parent.parent / "chat_data"
CHAT_DATA_DIR.mkdir(exist_ok=True)

# Lock for file operations
file_lock = Lock()


def get_chat_file_path(slideshow_id: str, slide_number: int) -> Path:
    """Get the path to the chat file for a specific slide."""
    # Sanitize slideshow_id to prevent directory traversal
    safe_id = "".join(c for c in slideshow_id if c.isalnum() or c in ('-', '_'))
    slideshow_dir = CHAT_DATA_DIR / safe_id
    slideshow_dir.mkdir(exist_ok=True)
    return slideshow_dir / f"slide_{slide_number}.json"


def load_messages(slideshow_id: str, slide_number: int) -> list:
    """Load chat messages for a specific slide."""
    chat_file = get_chat_file_path(slideshow_id, slide_number)
    if not chat_file.exists():
        return []
    
    with file_lock:
        try:
            with open(chat_file, 'r') as f:
                return json.load(f)
        except Exception as e:
            print(f"Error loading messages: {e}")
            return []


def save_message(slideshow_id: str, slide_number: int, username: str, message: str) -> dict:
    """Save a new message and return the message object."""
    chat_file = get_chat_file_path(slideshow_id, slide_number)
    
    # Create message object
    msg_obj = {
        "username": username or "anonymous",
        "message": message,
        "timestamp": datetime.now(timezone.utc).isoformat()
    }
    
    with file_lock:
        # Load existing messages
        messages = []
        if chat_file.exists():
            try:
                with open(chat_file, 'r') as f:
                    messages = json.load(f)
            except Exception as e:
                print(f"Error loading existing messages: {e}")
                messages = []
        
        # Append new message
        messages.append(msg_obj)
        
        # Save back to file
        try:
            with open(chat_file, 'w') as f:
                json.dump(messages, f, indent=2)
        except Exception as e:
            print(f"Error saving messages: {e}")
            raise
    
    return msg_obj


def get_room_name(slideshow_id: str, slide_number: int) -> str:
    """Get the WebSocket room name for a specific slide."""
    return f"{slideshow_id}:{slide_number}"


# HTTP Endpoints

@app.route('/chat/<slideshow_id>/<int:slide_number>', methods=['GET'])
def get_chat_messages(slideshow_id: str, slide_number: int):
    """
    Get all chat messages for a specific slide.
    
    Returns:
        JSON array of message objects
    """
    messages = load_messages(slideshow_id, slide_number)
    return jsonify(messages)


@app.route('/chat/<slideshow_id>/<int:slide_number>', methods=['POST'])
def post_chat_message(slideshow_id: str, slide_number: int):
    """
    Post a new chat message to a specific slide (HTTP alternative to WebSocket).
    
    Request body:
        {
            "username": "student1",  // optional, defaults to "anonymous"
            "message": "message text"
        }
    
    Returns:
        The created message object
    """
    data = request.get_json()
    if not data or 'message' not in data:
        abort(400, description="Missing 'message' field")
    
    username = data.get('username', 'anonymous')
    message = data['message']
    
    if not message.strip():
        abort(400, description="Message cannot be empty")
    
    try:
        msg_obj = save_message(slideshow_id, slide_number, username, message)
        
        # Broadcast to WebSocket clients
        room = get_room_name(slideshow_id, slide_number)
        socketio.emit('message', {
            'action': 'message',
            'slideshow_id': slideshow_id,
            'slide_number': slide_number,
            'username': msg_obj['username'],
            'message': msg_obj['message'],
            'timestamp': msg_obj['timestamp']
        }, room=room)
        
        return jsonify(msg_obj), 201
    except Exception as e:
        abort(500, description=f"Error saving message: {str(e)}")


@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint."""
    return jsonify({"status": "ok", "service": "guide-chat-server"})


# WebSocket Handlers

@socketio.on('connect')
def handle_connect():
    """Handle client connection."""
    print(f"Client connected: {request.sid}")


@socketio.on('disconnect')
def handle_disconnect():
    """Handle client disconnection."""
    print(f"Client disconnected: {request.sid}")


@socketio.on('subscribe')
def handle_subscribe(data):
    """
    Subscribe to chat updates for a specific slide.
    
    Message format:
        {
            "slideshow_id": "xxx",
            "slide_number": 1
        }
    """
    try:
        slideshow_id = data.get('slideshow_id')
        slide_number = data.get('slide_number')
        
        if not slideshow_id or slide_number is None:
            emit('error', {'message': 'Missing slideshow_id or slide_number'})
            return
        
        room = get_room_name(slideshow_id, slide_number)
        join_room(room)
        
        print(f"Client {request.sid} subscribed to {room}")
        
        # Send existing messages
        messages = load_messages(slideshow_id, slide_number)
        emit('history', {
            'slideshow_id': slideshow_id,
            'slide_number': slide_number,
            'messages': messages
        })
    except Exception as e:
        print(f"Error in subscribe: {e}")
        emit('error', {'message': str(e)})


@socketio.on('unsubscribe')
def handle_unsubscribe(data):
    """
    Unsubscribe from chat updates for a specific slide.
    
    Message format:
        {
            "slideshow_id": "xxx",
            "slide_number": 1
        }
    """
    try:
        slideshow_id = data.get('slideshow_id')
        slide_number = data.get('slide_number')
        
        if not slideshow_id or slide_number is None:
            return
        
        room = get_room_name(slideshow_id, slide_number)
        leave_room(room)
        
        print(f"Client {request.sid} unsubscribed from {room}")
    except Exception as e:
        print(f"Error in unsubscribe: {e}")


@socketio.on('post')
def handle_post(data):
    """
    Post a new message to a slide.
    
    Message format:
        {
            "slideshow_id": "xxx",
            "slide_number": 1,
            "username": "student1",  // optional
            "message": "message text"
        }
    """
    try:
        slideshow_id = data.get('slideshow_id')
        slide_number = data.get('slide_number')
        username = data.get('username', 'anonymous')
        message = data.get('message', '').strip()
        
        if not slideshow_id or slide_number is None:
            emit('error', {'message': 'Missing slideshow_id or slide_number'})
            return
        
        if not message:
            emit('error', {'message': 'Message cannot be empty'})
            return
        
        # Save message
        msg_obj = save_message(slideshow_id, slide_number, username, message)
        
        # Broadcast to all clients in the room
        room = get_room_name(slideshow_id, slide_number)
        socketio.emit('message', {
            'action': 'message',
            'slideshow_id': slideshow_id,
            'slide_number': slide_number,
            'username': msg_obj['username'],
            'message': msg_obj['message'],
            'timestamp': msg_obj['timestamp']
        }, room=room)
        
        print(f"Message posted to {room} by {username}: {message[:50]}...")
        
    except Exception as e:
        print(f"Error in post: {e}")
        emit('error', {'message': str(e)})


if __name__ == '__main__':
    # Allow port and debug mode to be configured via environment variables
    port = int(os.environ.get('CHAT_SERVER_PORT', 5002))
    debug_mode = os.environ.get('CHAT_SERVER_DEBUG', 'true').lower() == 'true'
    
    print(f"Guide Chat Server starting...")
    print(f"Chat data directory: {CHAT_DATA_DIR.absolute()}")
    print(f"Available at: http://localhost:{port}")
    print(f"  - WebSocket: ws://localhost:{port}/ws")
    print(f"  - HTTP GET: http://localhost:{port}/chat/<slideshow_id>/<slide_number>")
    print(f"  - HTTP POST: http://localhost:{port}/chat/<slideshow_id>/<slide_number>")
    print(f"  - Health check: http://localhost:{port}/health")
    if debug_mode:
        print(f"  - Debug mode: enabled")
    
    socketio.run(app, host='0.0.0.0', port=port, debug=debug_mode, allow_unsafe_werkzeug=True)
