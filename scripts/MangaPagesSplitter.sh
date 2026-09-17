#!/bin/bash
# MangaPagesSplitter.sh - Shell script to run the MangaPagesSplitter program

# Get the directory of this script
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
JAR_PATH="${DIR}/MangaPagesSplitter.jar"

# Check if the JAR exists
if [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: Could not find MangaPagesSplitter.jar in the current directory."
    echo "Please ensure you've built the project and the JAR file is present."
    read -p "Press Enter to continue..."
    exit 1
fi

# Check that Java 17 or newer is available
if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: Java is not installed or not on the PATH."
    echo "MangaPagesSplitter needs Java 17 or newer (https://adoptium.net)."
    read -p "Press Enter to continue..."
    exit 1
fi
# Major version: "1.8.0_471" -> 8, "17.0.16" -> 17, "17-ea" -> 17, "25" -> 25.
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | sed -E 's/^1\.//; s/^([0-9]+).*$/\1/')"
case "$JAVA_MAJOR" in
    ''|*[!0-9]*)
        echo "ERROR: Could not determine the Java version from:"
        java -version
        echo "MangaPagesSplitter needs Java 17 or newer (https://adoptium.net)."
        read -p "Press Enter to continue..."
        exit 1
        ;;
esac
if [ "$JAVA_MAJOR" -lt 17 ]; then
    echo "ERROR: Java 17 or newer is required, but the installed version is:"
    java -version
    echo "Install a current Java from https://adoptium.net, or use the portable Windows bundle."
    read -p "Press Enter to continue..."
    exit 1
fi

# Run the application
echo "Starting MangaPagesSplitter..."
java -jar "$JAR_PATH" "$@"

# If we get here, the application has completed
echo "Program execution completed."
read -p "Press Enter to continue..."