#!/bin/bash
set -e
mkdir -p out
echo "Compiling..."
javac src/EcommercePlatform.java -d out/
echo "Running ArchRails E-Commerce Demo..."
echo ""
java -cp out EcommercePlatform
