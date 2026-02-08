# Agent Demo Workspace

This workspace demonstrates Field4's self-healing metadata system for external AI agents.

## Try It Out

### 1. Open this workspace in Field4

```bash
# Start Field4 and load this workspace
./gradlew jvmRun
# Then load: metadata_store/agent_demo
```

### 2. Create a new file while Field is running

```bash
cd metadata_store/agent_demo

# Create a new Kotlin box
echo 'println("Hello from external agent!")' > my-test_code_.kt
```

### 3. Watch the magic happen

- Wait 5-10 seconds
- Field detects the orphaned file
- Auto-creates a box named "My Test"
- Assigns it a non-overlapping position
- Saves to metadata.edn
- Syncs to the browser UI

### 4. Edit the file

```bash
echo 'println("Updated code!")
println("The box updates automatically!")' > my-test_code_.kt
```

- Field detects the change
- Reloads the content
- Updates the UI
- Persists the change

### 5. Add a shader

```bash
cat > shader-demo_fragment_.glsl << 'EOF'
void main() {
    vec2 uv = gl_FragCoord.xy / vec2(1920.0, 1080.0);
    gl_FragColor = vec4(uv.x, 1.0 - uv.y, 0.5, 1.0);
}
EOF
```

- Field creates "Shader Demo" box
- Loads the shader code
- Ready to render!

## What's Included

- **welcome** - Introduction to the demo
- **instructions** - Step-by-step guide
- **example-shader** - Sample shader box

## More Information

- [AGENT_GUIDE.md](../../AGENT_GUIDE.md) - Full documentation
- [AGENT_QUICK_REF.md](../../AGENT_QUICK_REF.md) - Quick reference
- [FileWatchManager.kt](../../src/jvmMain/kotlin/afield/FileWatchManager.kt) - Implementation

## Features Demonstrated

✅ Auto-detection of orphaned files
✅ Auto-creation of boxes with sensible defaults
✅ Live file change synchronization
✅ Automatic frame positioning
✅ Metadata persistence
✅ Multi-file box support (code + shader + metadata)

Enjoy exploring Field4's agent-friendly features!
