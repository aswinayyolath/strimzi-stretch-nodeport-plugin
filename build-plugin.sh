#!/bin/bash
set -e

echo "🔨 Building Strimzi Stretch NodePort Plugin"
echo "============================================"

# Check if Strimzi is available
STRIMZI_PATH="${STRIMZI_PATH:-../strimzi-kafka-operator}"

if [ ! -d "$STRIMZI_PATH" ]; then
    echo "❌ Error: Strimzi not found at $STRIMZI_PATH"
    echo "   Set STRIMZI_PATH environment variable or clone Strimzi to ../strimzi-kafka-operator"
    exit 1
fi

echo ""
echo "📦 Step 1: Installing Strimzi cluster-operator to local Maven repo..."
cd "$STRIMZI_PATH"
echo "   (Compiling cluster-operator...)"
# Build and install cluster-operator (skip checkstyle and tests)
mvn clean install -pl cluster-operator -am -DskipTests -Dcheckstyle.skip=true -q
if [ $? -ne 0 ]; then
    echo "❌ Error: Failed to build Strimzi cluster-operator"
    echo "   Running with verbose output to see the error..."
    mvn clean install -pl cluster-operator -am -DskipTests -Dcheckstyle.skip=true
    exit 1
fi

echo "✅ Strimzi cluster-operator installed to local Maven repo"

echo ""
echo "🔨 Step 2: Building NodePort Plugin..."
cd - > /dev/null
mvn clean package -q
if [ $? -ne 0 ]; then
    echo "❌ Error: Failed to build NodePort plugin"
    echo "   Running with verbose output to see the error..."
    mvn clean package
    exit 1
fi
echo "✅ Plugin built successfully"

echo ""
echo "📋 Step 3: Verifying JAR contents..."
JAR_FILE=$(ls target/strimzi-stretch-nodeport-plugin-*.jar 2>/dev/null | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "❌ Error: JAR file not found"
    exit 1
fi

echo "   JAR: $JAR_FILE"
echo "   Size: $(du -h "$JAR_FILE" | cut -f1)"
echo ""
echo "   Contents:"
jar tf "$JAR_FILE" | grep -E "(NodePortNetworkingProvider|META-INF)" | head -10

echo ""
echo "✅ Build complete!"
echo ""
echo "📦 Plugin JAR: $JAR_FILE"
echo ""
echo "Next steps:"
echo "  1. Copy JAR to operator node: scp $JAR_FILE <operator-node>:/opt/strimzi/plugins/"
echo "  2. Update operator deployment with environment variables (see README.md):"
echo "     - STRIMZI_STRETCH_PLUGIN_CLASS_NAME=io.strimzi.plugin.stretch.NodePortNetworkingProvider"
echo "     - STRIMZI_STRETCH_PLUGIN_CLASS_PATH=/opt/strimzi/plugins/*"
echo "  3. Restart operator: kubectl rollout restart deployment/strimzi-cluster-operator"
echo ""
