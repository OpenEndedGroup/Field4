FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# ── System packages ──────────────────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl wget gnupg software-properties-common \
        git unzip zip \
        # X11 / Vulkan / Mesa (headless GPU for LWJGL)
        libx11-6 libxrandr2 libxinerama1 libxcursor1 libxi6 libxxf86vm1 \
        libgl1 libvulkan1 mesa-vulkan-drivers vulkan-tools \
        # Python
        python3 python3-pip python3-venv \
        # Playwright system deps (Chromium)
        libnss3 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 \
        libdrm2 libxcomposite1 libxdamage1 libxfixes3 libxkbcommon0 \
        libpango-1.0-0 libcairo2 libasound2t64 libgbm1 \
    && rm -rf /var/lib/apt/lists/*

# ── Adoptium Temurin JDK 25 ──────────────────────────────────────────────────
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | \
        gpg --dearmor -o /usr/share/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb noble main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-25-jdk && \
    rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# ── Python packages (system-wide via pip, fine for containers) ────────────────
RUN pip3 install --break-system-packages --no-cache-dir \
        # Field backdoor
        websockets \
        # Docs syntax highlighting (Zensical docs deps come from `uv --group docs`)
        pygments \
        # Docs runner / test runner
        Pillow \
        # Guide chat server
        Flask flask-cors flask-socketio python-socketio \
        # Playwright for screenshots / browser automation
        playwright

# Install Playwright Chromium browser binary
RUN playwright install chromium

# ── Gradle wrapper warm-up (optional but saves first-run time) ────────────────
# We copy just enough to let gradlew download the distribution.
WORKDIR /workspace
COPY gradlew gradlew
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN chmod +x gradlew && \
    ./gradlew --no-daemon help 2>/dev/null || true

# ── Default shell ─────────────────────────────────────────────────────────────
CMD ["bash"]
