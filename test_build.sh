#!/bin/bash

echo "Testing Flutter MRZ Scanner with CameraX implementation..."

cd "$(dirname "$0")"

echo "1. Running pub get..."
flutter pub get

echo "2. Building example app..."
cd example
flutter pub get

echo "3. Building Android APK..."
flutter build apk --debug

if [ $? -eq 0 ]; then
    echo "✅ Build successful! CameraX implementation is ready."
    echo ""
    echo "Changes made:"
    echo "- Replaced Fotoapparat with CameraX"
    echo "- Added proper error handling"
    echo "- Improved device compatibility"
    echo "- Should fix GrapheneOS/Pixel 8a camera issues"
else
    echo "❌ Build failed. Check the errors above."
fi